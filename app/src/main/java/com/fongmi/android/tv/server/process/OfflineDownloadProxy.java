package com.fongmi.android.tv.server.process;

import static fi.iki.elonen.NanoHTTPD.MIME_PLAINTEXT;
import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

import com.fongmi.android.tv.download.OfflineDownloadCache;
import com.fongmi.android.tv.download.OfflineDownloadManifest;
import com.fongmi.android.tv.download.OfflineDownloadManager;
import com.fongmi.android.tv.download.OfflineDownloadProxyPath;
import com.fongmi.android.tv.netdisk.NetDiskRange;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.server.impl.Process;
import com.github.catvod.crawler.SpiderDebug;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

/**
 * 把「已经离线下载好的内容」经本机 HTTP 服务交给播放器。
 *
 * <p><b>为什么不能只在 Exo 那层接。</b>Exo 内核可以从
 * {@code MediaSourceFactory} 拿到本地缓存，但 MPV 内核用的是 {@code MpvPlayer}，
 * 它自己读 URI、不走 MediaSource——只给 Exo 接的结果是「换个内核就得重新联网」。
 * 真机验证时正是这样：下载完整成功，关掉源站后 MPV 报「连接超时」。所以本地内容
 * 必须有一个内核无关的出口，而项目里既有做法就是本机 HTTP 端点
 * （{@code file://} 走 {@code /file/}、{@code smb://} 走 {@code /netdisk}）。
 *
 * <p><b>Range 必须如实支持。</b>拖动进度条时播放器会发 {@code Range}；不能正确回
 * 206 与 {@code Content-Range}，Exo 与 mpv 都会判定该源不可 seek，表现为拖完回到开头
 * 或直接报错。这里按缓存里的实际分片拼出区间，缺一段就回源，绝不给出静默损坏的流。
 *
 * <p><b>回源是兜底而不是常态。</b>缓存被用户删掉一部分、或下载还没完成时，端点会
 * 302 回原始地址，让播放照旧能看；同时这也保证了「下到一半的剧集」不会变成死链。
 */
public class OfflineDownloadProxy implements Process {

    private static final String TAG = "offline-download";

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith(OfflineDownloadProxyPath.PREFIX);
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        String original = OfflineDownloadProxyPath.originalOf(url);
        if (original.isEmpty()) return Nano.error(Status.NOT_FOUND, "离线地址无法解析");
        OfflineDownloadManager manager;
        try {
            manager = OfflineDownloadManager.get();
        } catch (Throwable e) {
            return redirect(original);
        }
        String key = OfflineDownloadCache.keyFor(manager.cache(), original);
        if (key == null) return redirect(original);

        String rangeHeader = first(session.getHeaders(), "range");
        try {
            List<OfflineDownloadCache.Span> spans = OfflineDownloadCache.spans(manager.cache(), key);
            long total = OfflineDownloadCache.total(spans);
            boolean complete = OfflineDownloadCache.complete(manager.cache(), original);
            // 播放列表（m3u8/mpd）是文本，分片地址必须改写成指向本端点，否则播放器
            // 还是会绕开本地去联网取分片，断网时表现为「列表读到了、画面出不来」。
            if (isManifest(original) && complete) return serveManifest(manager, original, key, spans, total);
            if (!complete) {
                // 只下了一部分：本地没有的东西仍要有出处，交给原始地址去补。
                return redirect(original);
            }
            NetDiskRange.Result range = NetDiskRange.parse(rangeHeader, total);
            if (range.isUnsatisfiable()) return rangeNotSatisfiable(total);
            long start = range.getStart();
            long end = range.isValid() ? range.getEnd() : total - 1;
            if (end > total - 1) end = total - 1;
            if (start < 0) start = 0;
            if (end < start) return rangeNotSatisfiable(total);

            List<OfflineDownloadCache.Chunk> chunks = OfflineDownloadCache.plan(spans, start, end);
            if (chunks.isEmpty()) return redirect(original);
            if (SpiderDebug.isEnabled()) {
                SpiderDebug.log(TAG, "serve key=%s range=%d-%d/%d chunks=%d", key, start, end, total, chunks.size());
            }
            InputStream stream = open(chunks);
            long length = end - start + 1;
            String mime = mimeOf(original);
            Response response;
            if (range.isValid() && (start > 0 || length < total)) {
                response = newFixedLengthResponse(Status.PARTIAL_CONTENT, mime, stream, length);
                String contentRange = NetDiskRange.contentRange(start, end, total);
                if (contentRange != null) response.addHeader("Content-Range", contentRange);
            } else {
                response = newFixedLengthResponse(Status.OK, mime, stream, length);
            }
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Content-Length", String.valueOf(length));
            return response;
        } catch (Throwable e) {
            // 本地读失败不该让播放直接断掉：回源总能给用户一个能看的画面。
            SpiderDebug.log(TAG, e);
            return redirect(original);
        }
    }

    /**
     * 播放列表：读出正文并把里面的子资源地址改写成指向本端点。
     *
     * <p>清单通常很小（几 KB），整段读进内存改写比流式改写简单得多，也不会因为
     * 跨分片边界把一行切断。
     */
    private Response serveManifest(OfflineDownloadManager manager, String original, String key, List<OfflineDownloadCache.Span> spans, long total) {
        try {
            List<OfflineDownloadCache.Chunk> chunks = OfflineDownloadCache.plan(spans, 0, total - 1);
            if (chunks.isEmpty()) return redirect(original);
            byte[] raw;
            try (InputStream stream = open(chunks)) {
                raw = readAll(stream);
            }
            if (raw.length == 0) return redirect(original);
            String text = new String(raw, StandardCharsets.UTF_8);
            String rewritten = OfflineDownloadManifest.rewrite(text, original, Server.get().getAddress(true));
            byte[] body = rewritten.getBytes(StandardCharsets.UTF_8);
            if (SpiderDebug.isEnabled()) {
                SpiderDebug.log(TAG, "manifest key=%s bytes=%d uris=%d", key, body.length, OfflineDownloadManifest.uris(rewritten).size());
            }
            Response response = newFixedLengthResponse(Status.OK, mimeOf(original), new ByteArrayInputStream(body), body.length);
            response.addHeader("Accept-Ranges", "none");
            return response;
        } catch (Throwable e) {
            SpiderDebug.log(TAG, e);
            return redirect(original);
        }
    }

    /** 把多个分片按序拼成一个流。 */
    private static InputStream open(List<OfflineDownloadCache.Chunk> chunks) throws IOException {
        if (chunks.size() == 1) {
            OfflineDownloadCache.Chunk only = chunks.get(0);
            return slice(new FileInputStream(only.file()), only.offset(), only.length());
        }
        List<InputStream> streams = new ArrayList<>();
        for (OfflineDownloadCache.Chunk chunk : chunks) streams.add(slice(new FileInputStream(chunk.file()), chunk.offset(), chunk.length()));
        return new SequenceInputStream(java.util.Collections.enumeration(streams));
    }

    /** 从一个文件流里只放出 {@code [offset, offset+length)} 这一段。 */
    private static InputStream slice(FileInputStream stream, long offset, long length) throws IOException {
        long skipped = 0;
        while (skipped < offset) {
            long step = stream.skip(offset - skipped);
            if (step <= 0) break;
            skipped += step;
        }
        return new BoundedInputStream(stream, length);
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = stream.read(buffer)) > 0) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    private static boolean isManifest(String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        int query = lower.indexOf('?');
        if (query >= 0) lower = lower.substring(0, query);
        return lower.endsWith(".m3u8") || lower.endsWith(".mpd");
    }

    private static String mimeOf(String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        int query = lower.indexOf('?');
        if (query >= 0) lower = lower.substring(0, query);
        if (lower.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (lower.endsWith(".mpd")) return "application/dash+xml";
        if (lower.endsWith(".ts")) return "video/mp2t";
        if (lower.endsWith(".m4s") || lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".aac")) return "audio/aac";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        return "application/octet-stream";
    }

    /**
     * 回源：把播放器送到原始地址去。
     *
     * <p>用 302 而不是自己代理出去，是因为「回源」只应在缓存不全时发生；交给播放器与它
     * 原生的请求头处理，比在这里再实现一遍转发更可靠（签名地址、防盗链都原样保留）。
     */
    private static Response redirect(String original) {
        Response response = newFixedLengthResponse(Status.REDIRECT, MIME_PLAINTEXT, "");
        response.addHeader("Location", original);
        return response;
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

    /** 只放出指定长度的包装流，避免把后面的分片内容也吐出去。 */
    private static final class BoundedInputStream extends InputStream {

        private final InputStream delegate;
        private long remaining;

        private BoundedInputStream(InputStream delegate, long remaining) {
            this.delegate = delegate;
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int value = delegate.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            int read = delegate.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) remaining -= read;
            return read;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
