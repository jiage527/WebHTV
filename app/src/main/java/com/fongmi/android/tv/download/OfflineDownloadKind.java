package com.fongmi.android.tv.download;

/**
 * 离线下载的流类型。
 *
 * <p><b>为什么要先判类型。</b>Media3 的下载器分成三套实现：普通单文件走
 * {@code ProgressiveDownloader}，HLS 走 {@code HlsDownloader}，DASH 走
 * {@code DashDownloader}。走哪一套由 {@code Util.inferContentType(mimeType, uri)} 决定，
 * 而它优先看 MIME、其次看后缀。所以入队前必须先把类型判准：判成普通文件去下 m3u8，
 * 结果是「只下到一个几百字节的播放列表」，用户看到的是「下载完成但播不了」。
 *
 * <p><b>为什么不用 {@code MimeTypes} 常量。</b>{@code androidx.media3.common.MimeTypes}
 * 在纯 JVM 单测里不可靠（它是 Android 库里的静态字段），所以这里直接写字面量并注明出处。
 * 这两个值是 HLS/DASH 的规范 MIME，等于 {@code MimeTypes.APPLICATION_M3U8} 与
 * {@code MimeTypes.APPLICATION_MPD}。
 *
 * <p>本类不碰 Android API，可以直接跑单测。
 */
public enum OfflineDownloadKind {

    /** 单个文件（mp4/mkv/ts/flv…），可以边下边播。 */
    PROGRESSIVE,
    /** HLS（m3u8 + 分片），需要分片合并。 */
    HLS,
    /** DASH（mpd + 分片），需要分片合并。 */
    DASH,
    /** 从地址上判不出来，交给 Media3 用真实内容嗅探。 */
    UNKNOWN;

    /** 等于 {@code MimeTypes.APPLICATION_M3U8}。 */
    public static final String MIME_HLS = "application/x-mpegURL";
    /** 等于 {@code MimeTypes.APPLICATION_MPD}。 */
    public static final String MIME_DASH = "application/dash+xml";

    /**
     * 判类型：MIME 优先，其次看后缀。
     *
     * <p>只认后缀不认 MIME 会在「带签名参数的 m3u8」上判错——那种地址的后缀常常是
     * 空或者 {@code .php}，但服务端会回 HLS 的 MIME；反过来只认 MIME 又会在
     * 「服务端乱填 content-type」时判错。两边都看，MIME 优先。
     */
    public static OfflineDownloadKind of(String url, String mimeType) {
        OfflineDownloadKind byMime = ofMimeType(mimeType);
        if (byMime != null) return byMime;
        return ofUrl(url);
    }

    /** 只看 MIME；判不出来返回 null（而不是 UNKNOWN），好让调用方继续看后缀。 */
    public static OfflineDownloadKind ofMimeType(String mimeType) {
        if (mimeType == null || mimeType.isEmpty()) return null;
        String lower = mimeType.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("mpegurl")) return HLS;
        if (lower.contains("dash")) return DASH;
        return null;
    }

    /** 只看地址后缀；判不出来返回 UNKNOWN。 */
    public static OfflineDownloadKind ofUrl(String url) {
        String extension = extensionOf(url);
        if (extension.isEmpty()) return UNKNOWN;
        if ("m3u8".equals(extension) || "m3u".equals(extension)) return HLS;
        if ("mpd".equals(extension)) return DASH;
        if (isProgressiveExtension(extension)) return PROGRESSIVE;
        return UNKNOWN;
    }

    /**
     * 取地址的扩展名（小写、不含点）。
     *
     * <p>要先把 query 与 fragment 切掉：带签名参数的地址里 {@code ?token=.mp4} 这种
     * 内容会让「最后一个点之后」取到完全错误的东西。
     */
    public static String extensionOf(String url) {
        if (url == null) return "";
        String value = url.trim();
        int cut = value.indexOf('?');
        if (cut >= 0) value = value.substring(0, cut);
        cut = value.indexOf('#');
        if (cut >= 0) value = value.substring(0, cut);
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        int dot = value.lastIndexOf('.');
        if (dot <= slash || dot == value.length() - 1) return "";
        return value.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isProgressiveExtension(String extension) {
        switch (extension) {
            case "mp4": case "mkv": case "webm": case "avi": case "mov": case "wmv": case "flv":
            case "ts": case "m2ts": case "mpg": case "mpeg": case "rmvb": case "rm": case "3gp":
            case "m4v": case "mts": case "vob": case "ogv": case "asf":
            case "mp3": case "m4a": case "aac": case "flac": case "wav": case "ogg": case "ape":
            case "wma": case "mka": case "opus": case "amr":
                return true;
            default:
                return false;
        }
    }

    /** 是不是「一个地址对应一堆分片」的类型——这类必须先解析播放列表才能入队。 */
    public boolean isSegmented() {
        return this == HLS || this == DASH;
    }

    /** 入队时写进 {@code DownloadRequest} 的 MIME；返回 null 表示让 Media3 自己判。 */
    public String mimeType() {
        if (this == HLS) return MIME_HLS;
        if (this == DASH) return MIME_DASH;
        return null;
    }
}
