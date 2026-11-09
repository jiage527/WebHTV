package com.fongmi.android.tv.server.process;

import static fi.iki.elonen.NanoHTTPD.MIME_PLAINTEXT;
import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

import com.fongmi.android.tv.netdisk.NetDiskRange;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.webdav.WebDavPath;
import com.fongmi.android.tv.webdav.WebDavRoot;
import com.fongmi.android.tv.webdav.WebDavSetting;
import com.fongmi.android.tv.webdav.WebDavXml;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Path;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

/**
 * 把本机文件经 WebDAV 协议共享给同一局域网内的设备。
 *
 * <p><b>与既有的 {@code /file} 端点有何不同。</b>{@code Local} 提供的是「本 App 自己的
 * 简易文件浏览接口」，只有本 App 的界面会调它。WebDAV 是标准协议：Windows 资源管理器、
 * macOS Finder、nPlayer / Infuse / Kodi 这类播放器都能直接「映射网络驱动器」来访问，
 * 无需安装本 App。用户于是能在电视盒子上就地播手机里的片子，反之亦可。
 *
 * <p><b>为什么复用 NanoHTTPD 而不是自己写 socket。</b>NanoHTTPD 已经支持
 * {@code PROPFIND} / {@code MKCOL} / {@code MOVE} / {@code COPY} 等方法（已用
 * {@code javap} 确认方法枚举里有这些值），也已经有完整的请求解析、Range 处理与
 * 响应写出。自己写一套只会引入新的解析漏洞，而安全性恰恰是这里最要紧的事。
 *
 * <p><b>写入必须由用户单独授权。</b>只读场景（对方来播我这里的片子）足够覆盖绝大多数
 * 需要；而写入意味着对方能上传、覆盖、删除我们的文件。所以写入走
 * {@link WebDavSetting#isWritable()}，默认关闭，且关闭服务时会一起收回。
 *
 * <p><b>始终回 {@code Multi-Status} 而不是漂亮的目录 HTML。</b>WebDAV 客户端只认
 * 207 + XML；返回 HTML 会让它们认为这不是可挂载的资源。
 */
public class WebDavServer implements Process {

    private static final String TAG = "webdav";

    /** 请求路径前缀。挂在 {@code /dav} 下，避免与既有端点冲突。 */
    private static final String PREFIX = "/dav";

    /** 单次 PUT 允许的最大字节数，防止别人把磁盘写满。 */
    private static final long MAX_UPLOAD_BYTES = 8L * 1024 * 1024 * 1024;

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        // "*" 只在 OPTIONS 探测全局能力时出现；不加这个限定会把 /davfoo 之外的
        // 无关请求（如 GET *）也引到这里来。
        if ("*".equals(url)) return session.getMethod() == Method.OPTIONS;
        // 必须精确匹配前缀或前缀 + "/"，否则 /davfoo 这类无关路径也会被接过来。
        return url.equals(PREFIX) || url.startsWith(PREFIX + "/");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        // 未开启时对外完全等同「此路径不存在」：不回 403，是为了不向局域网暴露
        // 「这台设备存在但被关掉了」这一信息。
        if (!WebDavSetting.isEnabled()) return notFound();
        String path = WebDavPath.normalize(stripPrefix(url));
        if (path == null) {
            SpiderDebug.log(TAG, "rejected unsafe path: %s", url);
            return Nano.error(Status.FORBIDDEN, "路径不合法");
        }
        Method method = session.getMethod();
        try {
            switch (method) {
                case OPTIONS:
                    return options();
                case PROPFIND:
                    return propfind(session, path);
                case GET:
                case HEAD:
                    return get(session, path, method == Method.HEAD);
                case PUT:
                    return put(session, path);
                case MKCOL:
                    return mkcol(path);
                case DELETE:
                    return delete(path);
                case MOVE:
                    return move(session, path);
                default:
                    // 其余方法（PROPPATCH / LOCK / COPY…）明确回 405，
                    // 而不是落到 assets 兜底逻辑去返回一个 HTML 404。
                    return methodNotAllowed();
            }
        } catch (Exception e) {
            SpiderDebug.log(TAG, e);
            return Nano.error(Status.INTERNAL_ERROR, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 方法实现

    /**
     * {@code OPTIONS}：声明支持的方法。
     *
     * <p>这是各客户端探测「这个服务端能不能写」的依据。必须在写入关闭时也如实回答，
     * 否则客户端会一直尝试上传然后失败。
     */
    private Response options() {
        Response response = newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, "");
        StringBuilder allow = new StringBuilder("OPTIONS, GET, HEAD, PROPFIND");
        if (WebDavSetting.isWritable()) allow.append(", PUT, MKCOL, DELETE, MOVE");
        response.addHeader("Allow", allow.toString());
        // DAV 头是客户端判定「这是 WebDAV」的关键；给 1 表示只支持基础能力
        // （不说 2/3，因为我们没有实现 LOCK 与完整属性集）。
        response.addHeader("DAV", "1");
        response.addHeader("MS-Author-Via", "DAV");
        return response;
    }

    /**
     * {@code PROPFIND}：目录列举。
     *
     * <p>响应必须包含被请求资源<b>自身</b>的条目（WebDAV 规范要求），否则客户端会
     * 认为路径不存在。目录还要递归一层——很多客户端（Windows 资源管理器）在挂载时
     * 就发 {@code Depth: 1}，只回自身会显示成空目录。
     *
     * <p>{@code Depth} 头必须尊重：{@code 0} 只回自身。客户端用 {@code Depth: 0} 做
     * 「这个路径存不存在、是文件还是目录」的探测，多回子项会让它把探测结果理解错。
     * {@code infinity} 不实现（规范允许拒绝），按 1 处理——对媒体共享来说，一次性
     * 展开整棵目录树既无必要也极慢。
     */
    private Response propfind(IHTTPSession session, String path) {
        File target = WebDavRoot.resolve(path);
        if (target == null || !target.exists()) return notFound();
        List<String> items = new ArrayList<>();
        items.add(entry(target, path));
        String depth = first(session.getHeaders(), "depth");
        // 缺省按规范是 infinity，但我们不支持递归，退到 1（返回自身 + 直接子项）。
        boolean children = !"0".equals(depth == null ? "" : depth.trim());
        if (children && target.isDirectory()) {
            File[] list = target.listFiles();
            if (list != null) {
                List<File> sorted = new ArrayList<>();
                for (File child : list) {
                    if (!WebDavPath.isVisibleName(child.getName())) continue;
                    sorted.add(child);
                }
                sorted.sort((a, b) -> WebDavPath.compare(a.isDirectory(), a.getName(), b.isDirectory(), b.getName()));
                for (File child : sorted) items.add(entry(child, childPath(path, child.getName())));
            }
        }
        Response response = newFixedLengthResponse(Status.MULTI_STATUS, "application/xml; charset=utf-8", WebDavXml.multistatus(items));
        response.addHeader("DAV", "1");
        return response;
    }

    /** 生成一条 {@code <D:response>}，href 用已转义的 URI 路径。 */
    private String entry(File file, String path) {
        String href = path.isEmpty() ? PREFIX + "/" : PREFIX + "/" + WebDavPath.encode(path) + (file.isDirectory() ? "/" : "");
        String name = WebDavRoot.isRoot(file) ? WebDavRoot.NAME : file.getName();
        return WebDavXml.response(href, name, file.isDirectory(),
                file.isDirectory() ? 0 : file.length(), file.lastModified());
    }

    /**
     * {@code GET} / {@code HEAD}：读文件。
     *
     * <p>Range 必须如实支持：播放器拖动进度条时会发 {@code Range}，不能正确回 206
     * 与 {@code Content-Range} 的话，客户端会认为该资源不可 seek。
     */
    private Response get(IHTTPSession session, String path, boolean headOnly) throws IOException {
        File file = WebDavRoot.resolve(path);
        if (file == null || !file.exists() || file.isDirectory()) return notFound();
        if (!file.canRead()) return Nano.error(Status.FORBIDDEN, "无读取权限");
        long length = file.length();
        String etag = etag(file, length);
        String ifNoneMatch = first(session.getHeaders(), "if-none-match");
        if (ifNoneMatch != null && (ifNoneMatch.equals("*") || ifNoneMatch.equals(etag))) {
            return newFixedLengthResponse(Status.NOT_MODIFIED, WebDavXml.mimeOf(file.getName()), "");
        }
        // 空文件必须单独处理：NetDiskRange 对 total=0 会算出「长度 1」
        // （服务网盘读接口时 total 为 0 表示未知，那里是合理的降级），
        // 但这里 total 是已知的，声明成 1 而实际发 0 字节会让客户端一直等。
        if (length == 0) {
            if (headOnly) {
                Response response = newFixedLengthResponse(Status.OK, WebDavXml.mimeOf(file.getName()), "");
                response.addHeader("Content-Length", "0");
                response.addHeader("Accept-Ranges", "bytes");
                response.addHeader("ETag", etag);
                return response;
            }
            Response response = newFixedLengthResponse(Status.OK, WebDavXml.mimeOf(file.getName()),
                    new java.io.ByteArrayInputStream(new byte[0]), 0);
            response.addHeader("Content-Length", "0");
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("ETag", etag);
            return response;
        }
        String rangeHeader = first(session.getHeaders(), "range");
        String ifRange = first(session.getHeaders(), "if-range");
        // If-Range 与当前 ETag 不匹配时，按协议要忽略 Range 回全量，
        // 而不是回一段可能是旧内容的字节。
        if (ifRange != null && !ifRange.isEmpty() && !ifRange.equals(etag)) rangeHeader = null;
        // 复用 NetDiskRange：已有 17 项单测钉住它的边界行为，没必要再写一份。
        NetDiskRange.Result range = NetDiskRange.parse(rangeHeader, length);
        if (range.isUnsatisfiable()) {
            Response invalid = newFixedLengthResponse(Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "");
            invalid.addHeader("Content-Range", NetDiskRange.unsatisfiedContentRange(length));
            return invalid;
        }
        boolean partial = range.isPartial(length);
        Status status = partial ? Status.PARTIAL_CONTENT : Status.OK;
        if (headOnly) {
            // HEAD 只回头部，不带正文。用 String 重载（正文为空），并显式声明
            // Content-Length：NanoHTTPD 会优先采用我们给的 Content-Length 头，
            // 这样客户端仍能据全文件长度判断可否 seek。
            Response response = newFixedLengthResponse(status, WebDavXml.mimeOf(file.getName()), "");
            addFileHeaders(response, range, length, etag, partial);
            return response;
        }
        InputStream stream = new FileInputStream(file);
        // 必须真的把流定位到起点：直接把整个流交给响应、只声明 Content-Length
        // 会让客户端收到从 0 开始的内容，seek 之后画面对不上。
        skipFully(stream, range.getStart());
        Response response = newFixedLengthResponse(status, WebDavXml.mimeOf(file.getName()), stream, range.length());
        addFileHeaders(response, range, length, etag, partial);
        return response;
    }

    private void addFileHeaders(Response response, NetDiskRange.Result range, long length, String etag, boolean partial) {
        if (partial) response.addHeader("Content-Range", NetDiskRange.contentRange(range.getStart(), range.getEnd(), length));
        response.addHeader("Content-Length", String.valueOf(range.length()));
        response.addHeader("Accept-Ranges", "bytes");
        response.addHeader("ETag", etag);
    }

    /**
     * {@code PUT}：上传/覆盖文件。
     *
     * <p><b>为什么直接读 {@code session.getInputStream()}，而不用 NanoHTTPD 的
     * {@code parseBody}。</b>两个原因：
     * <ol>
     *   <li>项目里的 {@code Nano.serve()} 只为 POST 调 {@code parseBody}，PUT 时传进来的
     *       {@code files} 永远是空的——照它写就会把每个上传都存成 0 字节文件，
     *       而客户端收到 201 以为成功（用户看到「已上传」而文件是空的）。</li>
     *   <li>{@code parseBody} 会把整个请求体落到临时文件再 {@code map} 成
     *       {@code MappedByteBuffer}，上传一个几 GB 的视频会直接把地址空间吃满；
     *       直接流式拷贝则只占一个 16KB 缓冲区。</li>
     * </ol>
     * <p>{@code execute()} 在调 {@code serve()} 之前已经 {@code skip} 过请求头，所以这里
     * 拿到的流正好位于正文起点。
     */
    private Response put(IHTTPSession session, String path) throws Exception {
        if (!WebDavSetting.isWritable()) return closeKeepAlive(forbiddenReadOnly());
        if (path.isEmpty()) return closeKeepAlive(Nano.error(Status.CONFLICT, "不能写入根目录"));
        String encoding = first(session.getHeaders(), "transfer-encoding");
        if (encoding != null && encoding.toLowerCase(java.util.Locale.ROOT).contains("chunked")) {
            // NanoHTTPD 2.3.1 不解析分块请求体，直接读流会把分块长度行当成文件内容写进去。
            // 回 411 让客户端改用 Content-Length，而不是默默存一个坏文件。
            return closeKeepAlive(Nano.error(Status.LENGTH_REQUIRED, "请使用 Content-Length 上传"));
        }
        long declared = declaredLength(session);
        if (declared > MAX_UPLOAD_BYTES) return closeKeepAlive(Nano.error(Status.PAYLOAD_TOO_LARGE, "文件过大"));
        File target = WebDavRoot.resolve(path);
        if (target == null) return Nano.error(Status.FORBIDDEN, "路径不合法");
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            // 上游目录不存在时按协议应回 409（Conflict），客户端据此知道要先建目录。
            return closeKeepAlive(Nano.error(Status.CONFLICT, "上级目录不存在"));
        }
        // 目录冲突：对方想用文件名覆盖一个已存在的目录，这是明确的错误而不是可覆盖状态。
        if (target.isDirectory()) return closeKeepAlive(Nano.error(Status.CONFLICT, "同名目录已存在"));
        boolean existed = target.isFile();
        // 先把字节写到临时文件，成功了再改名到目标。
        //
        // 不直接往目标写：传输中断（对方掉线、存储写满）会留下半个文件，
        // 把用户原本完整的那一份顶掉。同目录内改名是原子的，要么全新、要么不变。
        //
        // 也不用 Path.copy：它内部把 IOException 吞了，失败时会返回「成功」。
        File staging = new File(target.getAbsolutePath() + ".webdav-upload");
        long written;
        try {
            // 空的 PUT（declared 为 0）不读流：keep-alive 连接上已无正文，
            // 再去 read() 会一直阻塞到超时，把连接白白挂住。
            written = declared == 0 ? 0 : copyExact(session.getInputStream(), staging, declared);
        } catch (Exception e) {
            staging.delete();
            throw e;
        }
        if (!staging.renameTo(target)) {
            // 改名失败（目标被占用等）时退回复制，至少让上传成功。
            try {
                copy(staging, target);
            } finally {
                staging.delete();
            }
        }
        if (SpiderDebug.isEnabled()) SpiderDebug.log(TAG, "put %s size=%d", path, target.length());
        return newFixedLengthResponse(existed ? Status.NO_CONTENT : Status.CREATED, MIME_PLAINTEXT, "");
    }

    /** {@code MKCOL}：建目录。 */
    private Response mkcol(String path) {
        if (!WebDavSetting.isWritable()) return forbiddenReadOnly();
        if (path.isEmpty()) return Nano.error(Status.METHOD_NOT_ALLOWED, "根目录已存在");
        File target = WebDavRoot.resolve(path);
        if (target == null) return Nano.error(Status.FORBIDDEN, "路径不合法");
        if (target.exists()) return Nano.error(Status.METHOD_NOT_ALLOWED, "已存在");
        File parent = target.getParentFile();
        // 协议规定：父目录不存在时回 409，而不是替对方递归建目录。
        if (parent != null && !parent.isDirectory()) return Nano.error(Status.CONFLICT, "上级目录不存在");
        if (!target.mkdir()) return Nano.error("无法创建目录");
        return newFixedLengthResponse(Status.CREATED, MIME_PLAINTEXT, "");
    }

    /** {@code DELETE}：删除文件或目录。 */
    private Response delete(String path) {
        if (!WebDavSetting.isWritable()) return forbiddenReadOnly();
        // 共享根不允许被删：否则用户一旦误操作，整个共享目录连同文件一起没了。
        if (path.isEmpty()) return Nano.error(Status.FORBIDDEN, "不能删除共享根目录");
        File target = WebDavRoot.resolve(path);
        if (target == null) return Nano.error(Status.FORBIDDEN, "路径不合法");
        if (!target.exists()) return notFound();
        if (WebDavRoot.isRoot(target)) return Nano.error(Status.FORBIDDEN, "不能删除共享根目录");
        Path.clear(target);
        if (target.exists()) return Nano.error("删除失败");
        return newFixedLengthResponse(Status.NO_CONTENT, MIME_PLAINTEXT, "");
    }

    /**
     * {@code MOVE}：重命名/移动。
     *
     * <p>目标地址在 {@code Destination} 头里，是完整 URL，需要重新解析成相对路径——
     * 这里同样要过一遍 {@link WebDavPath}，否则攻击者可以用它把文件搬到根之外。
     */
    private Response move(IHTTPSession session, String path) {
        if (!WebDavSetting.isWritable()) return forbiddenReadOnly();
        String destination = first(session.getHeaders(), "destination");
        if (destination == null || destination.isEmpty()) return Nano.error(Status.BAD_REQUEST, "缺少 Destination");
        String targetPath = WebDavPath.normalize(stripPrefix(destinationOf(destination)));
        if (targetPath == null || targetPath.isEmpty()) return Nano.error(Status.FORBIDDEN, "目标路径不合法");
        File source = WebDavRoot.resolve(path);
        File target = WebDavRoot.resolve(targetPath);
        if (source == null || target == null) return Nano.error(Status.FORBIDDEN, "路径不合法");
        if (!source.exists()) return notFound();
        if (WebDavRoot.isRoot(source)) return Nano.error(Status.FORBIDDEN, "不能移动共享根目录");
        // Overwrite 按 RFC 4918 缺省为 T（允许覆盖），只有显式 F 才拒绝。
        boolean overwrite = !"F".equalsIgnoreCase(first(session.getHeaders(), "overwrite"));
        if (target.exists()) {
            if (!overwrite) return Nano.error(Status.PRECONDITION_FAILED, "目标已存在");
            Path.clear(target);
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return Nano.error(Status.CONFLICT, "上级目录不存在");
        if (!source.renameTo(target)) return Nano.error("移动失败");
        return newFixedLengthResponse(Status.CREATED, MIME_PLAINTEXT, "");
    }

    // ------------------------------------------------------------------ 辅助

    /** 去掉 {@code /dav} 前缀，得到要交给路径解析的部分。 */
    private static String stripPrefix(String url) {
        String value = url == null ? "" : url;
        if (value.startsWith(PREFIX)) value = value.substring(PREFIX.length());
        return value.isEmpty() ? "/" : value;
    }

    /**
     * 从完整 URL 里取出路径部分。
     *
     * <p>客户端可能发 {@code http://host:port/dav/a/b} 也可能只发 {@code /dav/a/b}，
     * 两种都要能处理。用 {@code indexOf} 定位主机之后的第一个 {@code /} 而不是
     * 解析 URL 对象：URL 解析会把百分号转义解掉，那样 {@code %2F} 就变成了分隔符，
     * 反而绕过了逐段校验。
     */
    private static String destinationOf(String destination) {
        String value = destination.trim();
        int scheme = value.indexOf("://");
        if (scheme < 0) return value;
        int slash = value.indexOf('/', scheme + 3);
        if (slash < 0) return "/";
        return value.substring(slash);
    }

    private static String childPath(String parent, String name) {
        return parent.isEmpty() ? name : parent + "/" + name;
    }

    /**
     * 标记该响应发完即断开连接。
     *
     * <p>用在「还没读完请求体就报错」的分支（未开启写入、文件过大等）。
     * HTTP/1.1 默认复用连接，若我们把未读完的正文留在 socket 里，客户端下一次请求
     * 会被服务端当成上一次请求的正文残尾，造成整条连接后续全部错位。断开是干净的做法。
     */
    private static Response closeKeepAlive(Response response) {
        response.closeConnection(true);
        return response;
    }

    private static long declaredLength(IHTTPSession session) {
        String value = first(session.getHeaders(), "content-length");
        if (value == null) return 0;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String first(Map<String, String> headers, String name) {
        if (headers == null || name == null) return null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private String etag(File file, long length) {
        CRC32 crc = new CRC32();
        crc.update((file.getAbsolutePath() + file.lastModified() + length).getBytes(StandardCharsets.UTF_8));
        return "\"" + Long.toHexString(crc.getValue()) + "\"";
    }

    private static void skipFully(InputStream stream, long count) throws IOException {
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

    /**
     * 复制文件，失败时抛出。
     *
     * <p>项目里的 {@code Path.copy} 把 IOException 吞掉了，上传场景下不能用：
     * 磁盘满或权限不足时会静默「成功」，用户看到上传完成而文件是空的。
     */
    private static void copy(File in, File out) throws IOException {
        try (InputStream input = new FileInputStream(in); FileOutputStream output = new FileOutputStream(out)) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        }
    }

    /**
     * 把上传正文拷贝到目标文件，返回实际写入字节数。
     *
     * <p><b>只读指定的字节数，不读到 EOF。</b>HTTP/1.1 默认复用连接，读完正文就停正好
     * 把流交给下一个请求；若一直读到 {@code -1}，在 keep-alive 连接上会阻塞到超时——
     * 每个上传都会先卡上几十秒。
     *
     * <p>字节数不足时抛错：说明传输被截断，不能拿它去顶掉用户原本完好的文件。
     */
    private static long copyExact(InputStream in, File out, long count) throws IOException {
        long written = 0;
        try (FileOutputStream output = new FileOutputStream(out)) {
            byte[] buffer = new byte[16384];
            while (written < count) {
                int want = (int) Math.min(buffer.length, count - written);
                int read = in.read(buffer, 0, want);
                if (read < 0) throw new IOException("上传被截断");
                output.write(buffer, 0, read);
                written += read;
            }
        }
        return written;
    }

    private static Response notFound() {
        return Nano.error(Status.NOT_FOUND, "不存在");
    }

    private static Response methodNotAllowed() {
        return Nano.error(Status.METHOD_NOT_ALLOWED, "暂不支持该方法");
    }

    private static Response forbiddenReadOnly() {
        return Nano.error(Status.FORBIDDEN, "服务端未开启写入");
    }
}
