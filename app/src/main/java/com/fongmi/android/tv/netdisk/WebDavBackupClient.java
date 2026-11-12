package com.fongmi.android.tv.netdisk;

import com.fongmi.android.tv.utils.ProgressRequestBody;
import com.thegrizzlylabs.sardineandroid.DavQuota;
import com.thegrizzlylabs.sardineandroid.DavResource;
import com.thegrizzlylabs.sardineandroid.Sardine;
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * WebDAV 云备份专用的读写客户端。
 *
 * <p><b>为什么不扩 {@link NetDiskClient}。</b>那个接口服务于播放代理，只需要「列目录 +
 * 读一段字节」，而备份还要写、要建目录、要按流下载。把写能力塞进只读接口，会让
 * SMB / FTP 两个实现被迫实现它们暂时用不到的方法。这里单开一个类，只服务备份这一个功能。
 *
 * <p><b>上传与下载为什么绕过 sardine 的 put/get。</b>sardine 的 {@code put} 内部用
 * {@code RequestBody.create(File)}，没有进度回调；备份包动辄上百 MB，用户需要一个能看出
 * 「还在动」的进度。所以这两条路直接用同一个 OkHttp 客户端发请求，并用
 * {@link ProgressRequestBody} 与手工拷贝字节来报进度。列目录、建目录、删除、配额仍然走 sardine，
 * 那些接口已经处理好了 WebDAV 的 XML 细节。
 */
public final class WebDavBackupClient {

    private static final MediaType ZIP = MediaType.parse("application/zip");
    private static final int BUFFER_SIZE = 128 * 1024;

    private final NetDiskAccount account;
    private final Sardine sardine;
    private final OkHttpClient http;
    private final String base;
    private final String authorization;

    public WebDavBackupClient(NetDiskAccount account) {
        this.account = account;
        this.http = new OkHttpClient.Builder().build();
        this.sardine = new OkHttpSardine(http);
        if (!account.getUsername().isEmpty()) {
            sardine.setCredentials(account.getUsername(), account.getPassword());
            this.authorization = Credentials.basic(account.getUsername(), account.getPassword());
        } else {
            this.authorization = "";
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

    /** 目录 URL（带结尾斜杠，否则不少服务端会 301 或 404）。 */
    private String dirUrl(String path) {
        StringBuilder builder = new StringBuilder(base);
        for (String segment : NetDiskCodec.split(path, NetDiskType.WEBDAV.separator())) {
            builder.append('/').append(NetDiskCodec.encodeSegment(segment));
        }
        builder.append('/');
        return builder.toString();
    }

    /** 文件 URL（不带结尾斜杠）。 */
    private String fileUrl(String path) {
        StringBuilder builder = new StringBuilder(base);
        for (String segment : NetDiskCodec.split(path, NetDiskType.WEBDAV.separator())) {
            builder.append('/').append(NetDiskCodec.encodeSegment(segment));
        }
        return builder.toString();
    }

    /** 探活并校验凭据。 */
    public void connect() throws Exception {
        List<DavResource> resources = sardine.list(base + "/", 1);
        if (resources == null) throw new IllegalStateException("服务器没有返回目录内容");
    }

    /** 列目录下的文件名（只返回名字，排序交给 {@link WebDavBackupStore}）。 */
    public List<String> names(String dir) throws Exception {
        List<String> result = new ArrayList<>();
        String target = WebDavBackupStore.normalizeDir(dir);
        List<DavResource> resources = sardine.list(dirUrl(target), 1);
        if (resources == null) return result;
        for (DavResource resource : resources) {
            String name = resource.getName();
            if (name == null || name.isEmpty()) continue;
            // PROPFIND 的响应含目录自身，按路径剔掉，否则列表里会多出一条同名的项。
            String resourcePath = NetDiskCodec.decodeSegment(resource.getPath() == null ? "" : resource.getPath());
            String normalized = NetDiskCodec.normalize(resourcePath, '/');
            String expected = NetDiskCodec.normalize(target, '/');
            if (normalized.equals(expected) || normalized.endsWith("/" + expected)) continue;
            result.add(name);
        }
        return result;
    }

    /**
     * 列目录下每个文件的字节数。
     *
     * <p>列表本身只需要名字，体积是单独一次请求：不是为了快，而是为了失败可分离 ——
     * 某些服务端不返回 {@code getcontentlength}，那次请求会失败，但不该让整个面板
     * 连文件名都列不出来。
     */
    public Map<String, Long> sizes(String dir) throws Exception {
        Map<String, Long> result = new java.util.HashMap<>();
        String target = WebDavBackupStore.normalizeDir(dir);
        List<DavResource> resources = sardine.list(dirUrl(target), 1);
        if (resources == null) return result;
        for (DavResource resource : resources) {
            String name = resource.getName();
            if (name == null || name.isEmpty()) continue;
            String resourcePath = NetDiskCodec.decodeSegment(resource.getPath() == null ? "" : resource.getPath());
            String normalized = NetDiskCodec.normalize(resourcePath, '/');
            String expected = NetDiskCodec.normalize(target, '/');
            if (normalized.equals(expected) || normalized.endsWith("/" + expected)) continue;
            result.put(name, resource.getContentLength());
        }
        return result;
    }

    public boolean exists(String path) throws Exception {
        try {
            return sardine.exists(fileUrl(path));
        } catch (com.thegrizzlylabs.sardineandroid.impl.SardineException e) {
            // 部分服务端对不存在的路径回 404 而不是「存在=false」，这里按不存在处理。
            if (WebDavBackupError.of(e) == WebDavBackupError.NOT_FOUND) return false;
            throw e;
        }
    }

    /**
     * 逐级创建目录。
     *
     * <p>sardine 的 {@code createDirectory} 只发一次 MKCOL，父目录不存在时服务端回 409。
     * 所以这里按层级依次创建，已存在的层级跳过 —— 重名目录再去 MKCOL 会得到 405。
     */
    public void mkdirs(String dir) throws Exception {
        List<String> segments = NetDiskCodec.split(WebDavBackupStore.normalizeDir(dir), NetDiskType.WEBDAV.separator());
        StringBuilder current = new StringBuilder();
        for (String segment : segments) {
            if (current.length() > 0) current.append(NetDiskType.WEBDAV.separator());
            current.append(segment);
            String path = current.toString();
            if (exists(path)) continue;
            try {
                sardine.createDirectory(dirUrl(path));
            } catch (com.thegrizzlylabs.sardineandroid.impl.SardineException e) {
                // 并发上传时别人可能刚建好，405/301 表示已经有了，不算失败。
                int status = e.getStatusCode();
                if (status == 405 || status == 301) continue;
                if (exists(path)) continue;
                throw e;
            }
        }
    }

    /**
     * 流式上传，边传边报进度。
     *
     * @param listener 进度回调，运行在上传线程上，界面侧要自己切回主线程
     */
    public void upload(File file, String remotePath, ProgressRequestBody.Listener listener) throws Exception {
        RequestBody body = new ProgressRequestBody(file, ZIP, listener);
        Request.Builder builder = new Request.Builder().url(fileUrl(remotePath)).put(body);
        if (!authorization.isEmpty()) builder.header("Authorization", authorization);
        Request request = builder.build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new com.thegrizzlylabs.sardineandroid.impl.SardineException(
                        "上传备份失败", response.code(), response.message());
            }
        }
    }

    /**
     * 流式下载到本地文件，边下边报进度。
     *
     * <p>先写 {@code file.part} 再改名：下载中断时不会留下一个看起来完整的备份包，
     * 用户下次也不会误点它去恢复。
     */
    public void download(String remotePath, File target, ProgressListener listener) throws Exception {
        Request.Builder builder = new Request.Builder().url(fileUrl(remotePath)).get();
        if (!authorization.isEmpty()) builder.header("Authorization", authorization);
        File part = new File(target.getParentFile(), target.getName() + ".part");
        long total = 0;
        try (Response response = http.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new com.thegrizzlylabs.sardineandroid.impl.SardineException(
                        "下载备份失败", response.code(), response.message());
            }
            if (response.body() == null) throw new IOException("服务器没有返回内容");
            long length = response.body().contentLength();
            try (InputStream input = response.body().byteStream();
                 FileOutputStream output = new FileOutputStream(part)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    total += read;
                    if (listener != null) listener.onProgress(total, length);
                }
                output.flush();
            }
        } catch (Exception e) {
            com.github.catvod.utils.Path.clear(part);
            throw e;
        }
        if (total <= 0) {
            com.github.catvod.utils.Path.clear(part);
            throw new IOException("备份内容为空");
        }
        com.github.catvod.utils.Path.clear(target);
        if (!part.renameTo(target)) {
            com.github.catvod.utils.Path.clear(part);
            throw new IOException("无法写入备份文件");
        }
    }

    public void delete(String remotePath) throws Exception {
        sardine.delete(fileUrl(remotePath));
    }

    /** 服务端剩余空间；服务端不支持配额时返回 -1。 */
    public long freeBytes(String dir) {
        try {
            DavQuota quota = sardine.getQuota(dirUrl(WebDavBackupStore.normalizeDir(dir)));
            return quota == null ? -1 : quota.getQuotaAvailableBytes();
        } catch (Throwable e) {
            return -1;
        }
    }

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

    public interface ProgressListener {

        void onProgress(long written, long total);
    }
}
