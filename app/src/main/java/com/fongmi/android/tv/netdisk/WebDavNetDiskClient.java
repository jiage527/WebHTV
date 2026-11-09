package com.fongmi.android.tv.netdisk;

import com.thegrizzlylabs.sardineandroid.DavResource;
import com.thegrizzlylabs.sardineandroid.Sardine;
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;

/**
 * 基于 sardine 的 WebDAV 客户端。
 *
 * <p><b>为什么要自己拼 URL。</b>sardine 的所有方法都要完整的 URL，所以这里维护一个
 * 账号根地址，路径一律先经 {@link NetDiskCodec#encodeSegment} 再拼 —— 直接把中文或空格
 * 放进 URL 会让某些服务端返回 400，而转义漏了就变成访问另一个文件。
 *
 * <p><b>读区间。</b>sardine 的 {@code get} 不带 Range，但它内部就是 OkHttp，所以这里
 * 直接用同一个 OkHttp 客户端发带 {@code Range} 的请求，避免把整个文件拉下来再丢弃。
 */
public final class WebDavNetDiskClient implements NetDiskClient {

    private final NetDiskAccount account;
    private final Sardine sardine;
    private final OkHttpClient http;

    private String base;

    public WebDavNetDiskClient(NetDiskAccount account) {
        this.account = account;
        this.http = new OkHttpClient.Builder().build();
        this.sardine = new OkHttpSardine(http);
        if (!account.getUsername().isEmpty()) {
            sardine.setCredentials(account.getUsername(), account.getPassword());
        }
        this.base = buildBase();
    }

    private String buildBase() {
        String scheme = account.isSecure() ? "https" : "http";
        boolean defaultPort = account.getPort() == account.getType().defaultPort(account.isSecure());
        String authority = account.getHost() + (defaultPort ? "" : ":" + account.getPort());
        StringBuilder builder = new StringBuilder(scheme).append("://").append(authority);
        for (String segment : NetDiskCodec.split(account.getRoot(), NetDiskType.WEBDAV.separator())) {
            builder.append('/').append(NetDiskCodec.encodeSegment(segment));
        }
        return builder.toString();
    }

    private String url(String path) {
        StringBuilder builder = new StringBuilder(base);
        for (String segment : NetDiskCodec.split(path, NetDiskType.WEBDAV.separator())) {
            builder.append('/').append(NetDiskCodec.encodeSegment(segment));
        }
        // 目录要带结尾斜杠，否则不少服务端会 301 到别处或返回 404。
        if (!path.isEmpty() && !path.endsWith(String.valueOf(NetDiskType.WEBDAV.separator()))) builder.append('/');
        return builder.toString();
    }

    private String fileUrl(String path) {
        StringBuilder builder = new StringBuilder(base);
        for (String segment : NetDiskCodec.split(path, NetDiskType.WEBDAV.separator())) {
            builder.append('/').append(NetDiskCodec.encodeSegment(segment));
        }
        return builder.toString();
    }

    @Override
    public void connect() throws Exception {
        // PROPFIND 根目录就是最轻的探活方式，顺带验证凭据；只认 207/200。
        List<DavResource> resources = sardine.list(base + "/", 1);
        if (resources == null) throw new IllegalStateException("服务器没有返回目录内容");
    }

    @Override
    public List<NetDiskEntry> list(String path) throws Exception {
        String target = path == null ? "" : path;
        List<DavResource> resources = sardine.list(url(target), 1);
        List<NetDiskEntry> entries = new ArrayList<>();
        if (resources == null) return entries;
        for (DavResource resource : resources) {
            String name = resource.getName();
            if (name == null || name.isEmpty()) continue;
            // PROPFIND 的响应含目录自身，必须按名字剔掉，否则列表里会出现自己。
            boolean directory = resource.isDirectory();
            // 资源路径可能被服务端转义过，按未转义版本比较更稳。
            String resourcePath = NetDiskCodec.decodeSegment(resource.getPath() == null ? "" : resource.getPath());
            if (samePath(resourcePath, target)) continue;
            String entryPath = NetDiskCodec.join(target, name, NetDiskType.WEBDAV.separator());
            long size = !directory && resource.getContentLength() != null ? resource.getContentLength() : 0;
            long modified = resource.getModified() == null ? 0 : resource.getModified().getTime();
            entries.add(new NetDiskEntry(name, entryPath, directory, size, modified));
        }
        entries.sort(NetDiskEntry::compareTo);
        return entries;
    }

    private boolean samePath(String resourcePath, String target) {
        String left = NetDiskCodec.normalize(resourcePath, '/');
        String right = NetDiskCodec.normalize(target, '/');
        return left.equals(right) || left.endsWith("/" + right);
    }

    @Override
    public long length(String path) throws Exception {
        List<DavResource> resources = sardine.list(fileUrl(path), 0);
        if (resources != null) {
            for (DavResource resource : resources) {
                if (resource.getContentLength() != null) return resource.getContentLength();
            }
        }
        return -1;
    }

    @Override
    public InputStream open(String path, long start, long end) throws Exception {
        String url = fileUrl(path);
        String range = "bytes=" + start + "-" + end;
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(url)
                .header("Range", range)
                .get()
                .build();
        okhttp3.Response response = http.newCall(request).execute();
        // 206 是我们想要的；部分服务端忽略 Range 直接回 200 全量，这时也能读，
        // 只是会多传字节——为了让播放器能 seek，这里必须把流对齐到 start。
        int code = response.code();
        if (code != 206 && code != 200) {
            response.close();
            throw new IllegalStateException("服务器返回 " + code);
        }
        InputStream stream = response.body() == null ? null : response.body().byteStream();
        if (stream == null) {
            response.close();
            throw new IllegalStateException("服务器没有返回内容");
        }
        if (code == 200 && start > 0) skipFully(stream, start);
        return stream;
    }

    private static void skipFully(InputStream stream, long count) throws Exception {
        long remaining = count;
        while (remaining > 0) {
            long skipped = stream.skip(remaining);
            if (skipped <= 0) {
                if (stream.read() < 0) return;
                remaining--;
                continue;
            }
            remaining -= skipped;
        }
    }

    @Override
    public void close() {
        try {
            http.dispatcher().executorService().shutdown();
        } catch (Throwable ignored) {
        }
        try {
            http.connectionPool().evictAll();
        } catch (Throwable ignored) {
        }
    }
}
