package com.fongmi.android.tv.server.process;

import static fi.iki.elonen.NanoHTTPD.MIME_PLAINTEXT;
import static fi.iki.elonen.NanoHTTPD.getMimeTypeForFile;
import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

import com.fongmi.android.tv.netdisk.NetDiskAccount;
import com.fongmi.android.tv.netdisk.NetDiskCredentialStore;
import com.fongmi.android.tv.netdisk.NetDiskProxyPath;
import com.fongmi.android.tv.netdisk.NetDiskRange;
import com.fongmi.android.tv.netdisk.NetDiskSession;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.github.catvod.crawler.SpiderDebug;

import java.io.InputStream;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

/**
 * 把网络存储（SMB / WebDAV）的文件经本机 HTTP 服务中转出去。
 *
 * <p><b>为什么需要这个端点。</b>播放器读不了 {@code smb://}：随包 {@code libmpv.so}
 * 里没有 libsmbclient 也没有 {@code smb://} 字样（双 ABI 都用 {@code grep -c} 核对过，
 * 命中均为 0），Exo 也没有 SMB 的 DataSource。所以浏览到文件后，交给播放器的是
 * 指向本端点的普通 {@code http://127.0.0.1:port} 地址，播放器完全无需改动。
 *
 * <p><b>Range 必须如实支持。</b>拖动进度条时播放器会发 {@code Range}，若不能正确回
 * 206 与 {@code Content-Range}，Exo 与 mpv 都会判定该源不可 seek，表现为拖动后回到
 * 开头或直接报错。网盘读接口本就支持随机读，所以这里只是转达区间，不缓存整文件。
 */
public class NetDiskProxy implements Process {

    private static final String TAG = "netdisk";

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith(NetDiskProxyPath.PREFIX);
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        Map<String, String> params = session.getParms();
        String accountId = params.get("account");
        String path = params.get("path") == null ? "" : params.get("path");
        NetDiskAccount account = NetDiskCredentialStore.get().get(accountId);
        if (account == null) return Nano.error(Status.NOT_FOUND, "网盘账号不存在");
        try {
            long total = NetDiskSession.get().client(account).length(path);
            NetDiskRange.Result range = NetDiskRange.parse(first(session.getHeaders(), "range"), total);
            if (range.isUnsatisfiable()) return rangeNotSatisfiable(total);
            long start = range.getStart();
            long end = range.isValid() ? range.getEnd() : (total > 0 ? total - 1 : Long.MAX_VALUE - 1);
            if (total > 0) {
                if (start > total - 1) return rangeNotSatisfiable(total);
                if (end > total - 1) end = total - 1;
            }
            if (start < 0) start = 0;
            if (end < start) return rangeNotSatisfiable(total);

            if (SpiderDebug.isEnabled()) {
                SpiderDebug.log(TAG, "open account=%s type=%s path=%s range=%d-%d/%d",
                        account.getId(), account.getType().id(), path, start, end, total);
            }
            InputStream stream = NetDiskSession.get().client(account).open(path, start, end);
            long length = end - start + 1;
            String mime = getMimeTypeForFile(nameOf(path, account));
            Response response;
            if (range.isValid() && (start > 0 || total <= 0 || length < total)) {
                response = newFixedLengthResponse(Status.PARTIAL_CONTENT, mime, stream, length);
                String contentRange = NetDiskRange.contentRange(start, end, total);
                if (contentRange != null) response.addHeader("Content-Range", contentRange);
            } else {
                response = newFixedLengthResponse(Status.OK, mime, stream, total > 0 ? total : length);
            }
            response.addHeader("Accept-Ranges", "bytes");
            if (total > 0) response.addHeader("Content-Length", String.valueOf(length));
            return response;
        } catch (Exception e) {
            // 连接坏了就丢掉缓存，让下一次请求重新建连，而不是一直复用坏会话。
            NetDiskSession.get().invalidate(account.getId());
            SpiderDebug.log(TAG, e);
            return Nano.error(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static String nameOf(String path, NetDiskAccount account) {
        int index = path.lastIndexOf(account.getType().separator());
        String name = index < 0 ? path : path.substring(index + 1);
        return name.isEmpty() ? "media" : name;
    }

    private static Response rangeNotSatisfiable(long total) {
        Response response = newFixedLengthResponse(Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "");
        response.addHeader("Content-Range", NetDiskRange.unsatisfiedContentRange(total));
        return response;
    }

    private static String first(Map<String, String> headers, String name) {
        if (headers == null || name == null) return null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return null;
    }
}
