package com.fongmi.android.tv.webdav;

import java.util.List;

/**
 * WebDAV 的 XML 与时间格式生成。纯逻辑、不依赖 Android，便于单测。
 *
 * <p><b>为什么 XML 必须转义。</b>文件名里出现 {@code &}、{@code <}、{@code >}（真实存在，
 * 例如 {@code Tom & Jerry.mp4}）时，未转义会直接破坏响应体，客户端要么解析失败、
 * 要么把名字读错——后者更危险：显示的名字与实际文件不符，重命名/删除会作用到错的项上。
 *
 * <p><b>为什么时间和尺寸要按 RFC 1123 / DAV 格式输出。</b>WebDAV 客户端（Windows
 * 资源管理器、macOS Finder、各家 NAS 客户端）大多严格按
 * {@code getlastmodified} 解析时间。格式不标准时，客户端会把所有文件显示成同一时间，
 * 或干脆列不出来。
 */
public final class WebDavXml {

    /** DAV 命名空间：所有响应元素都必须在它里面，否则客户端不认。 */
    public static final String DAV_NS = "DAV:";

    private WebDavXml() {
    }

    /** XML 文本转义，含属性里必须处理的引号。 */
    public static String escape(String text) {
        if (text == null) return "";
        StringBuilder builder = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '&': builder.append("&amp;"); break;
                case '<': builder.append("&lt;"); break;
                case '>': builder.append("&gt;"); break;
                case '"': builder.append("&quot;"); break;
                case '\'': builder.append("&apos;"); break;
                default:
                    // 控制字符在 XML 1.0 里非法（除了 tab/CR/LF）。文件名理论上不含，
                    // 但一旦出现会让整个响应解析失败，所以直接丢掉。
                    if (ch < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') break;
                    builder.append(ch);
            }
        }
        return builder.toString();
    }

    /**
     * 生成一个 {@code <D:response>} 条目。
     *
     * @param href    已转义的 URI 路径（含结尾斜杠表示目录）
     * @param name    显示名
     * @param dir     是否目录
     * @param size    字节数（目录传 0）
     * @param modifiedMs 最后修改时间（毫秒）；{@code <= 0} 时不输出该属性
     */
    public static String response(String href, String name, boolean dir, long size, long modifiedMs) {
        StringBuilder builder = new StringBuilder(512);
        builder.append("<D:response>");
        builder.append("<D:href>").append(escape(href)).append("</D:href>");
        builder.append("<D:propstat><D:prop>");
        // 资源类型是客户端区分目录与文件的主要依据（Windows 靠它决定是否可进入）。
        builder.append("<D:resourcetype>").append(dir ? "<D:collection/>" : "").append("</D:resourcetype>");
        if (!dir) {
            builder.append("<D:getcontentlength>").append(size).append("</D:getcontentlength>");
            // 少数客户端（尤其 macOS Finder）缺这个属性会显示成 0 字节。
            builder.append("<D:getcontenttype>").append(escape(mimeOf(name))).append("</D:getcontenttype>");
        }
        if (modifiedMs > 0) builder.append("<D:getlastmodified>").append(escape(httpDate(modifiedMs))).append("</D:getlastmodified>");
        // 不支持任何加锁：明确回 not-supported，客户端会据此认为无需锁即可读写。
        // 不回这个属性的话，部分客户端会认为服务端「能力未知」而拒绝写入。
        builder.append("<D:supportedlock/>");
        builder.append("</D:prop>");
        builder.append("<D:status>HTTP/1.1 200 OK</D:status>");
        builder.append("</D:propstat>");
        // 显示名是客户端列表里「看到的名字」，必须给，否则会退回用 href 末段。
        builder.append("<D:propstat><D:prop><D:displayname>").append(escape(name)).append("</D:displayname></D:prop>");
        builder.append("<D:status>HTTP/1.1 200 OK</D:status></D:propstat>");
        builder.append("</D:response>");
        return builder.toString();
    }

    /** 包成完整的 {@code 207 Multi-Status} 响应体。 */
    public static String multistatus(List<String> responses) {
        StringBuilder builder = new StringBuilder(1024);
        builder.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        builder.append("<D:multistatus xmlns:D=\"").append(DAV_NS).append("\">");
        if (responses != null) for (String response : responses) builder.append(response);
        builder.append("</D:multistatus>");
        return builder.toString();
    }

    /**
     * HTTP 日期（RFC 1123，固定 GMT）。
     *
     * <p>必须用 {@code Locale.US}：某些地区的默认 Locale 会输出本地化的星期/月名，
     * 客户端解析不了，表现为「所有文件没有修改时间」。
     */
    public static String httpDate(long millis) {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(
                "EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US);
        format.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
        return format.format(new java.util.Date(millis));
    }

    /**
     * 按扩展名猜 MIME。
     *
     * <p>不依赖 Android 的 {@code MimeTypeMap}（单测里不可用），只覆盖视频/音频/字幕
     * 这几类真正会经此访问的类型；其余一律 {@code application/octet-stream}，
     * 让客户端按二进制处理而不是猜成一个可能出错的文本类型。
     */
    public static String mimeOf(String name) {
        if (name == null) return "application/octet-stream";
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) return "application/octet-stream";
        switch (lower.substring(dot + 1)) {
            case "mp4": case "m4v": return "video/mp4";
            case "mkv": return "video/x-matroska";
            case "avi": return "video/x-msvideo";
            case "mov": return "video/quicktime";
            case "wmv": return "video/x-ms-wmv";
            case "flv": return "video/x-flv";
            case "ts": case "m2ts": return "video/mp2t";
            case "mpg": case "mpeg": return "video/mpeg";
            case "webm": return "video/webm";
            case "rmvb": case "rm": return "application/vnd.rn-realmedia";
            case "mp3": return "audio/mpeg";
            case "flac": return "audio/flac";
            case "wav": return "audio/wav";
            case "m4a": return "audio/mp4";
            case "aac": return "audio/aac";
            case "srt": return "application/x-subrip";
            case "ass": case "ssa": return "text/x-ssa";
            case "vtt": return "text/vtt";
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "txt": return "text/plain; charset=utf-8";
            default: return "application/octet-stream";
        }
    }
}
