package com.fongmi.android.tv.download;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 离线播放端点 {@code /offline/...} 的路径拼装与还原。
 *
 * <p><b>为什么走本地 HTTP 端点，而不是各自去改内核。</b>Exo 内核可以从
 * {@code MediaSourceFactory} 那层拿到本地缓存；MPV 内核用的是 {@code MpvPlayer}，
 * 它自己读 URI、根本不走 MediaSource，所以「把本地内容交给播放器」这件事只有一个
 * 内核无关的做法：给播放器一个普通 {@code http://127.0.0.1:port} 地址。这也是项目里
 * 既有的做法——{@code file://} 走 {@code /file/}、{@code smb://} 走 {@code /netdisk}。
 *
 * <p><b>为什么把原始地址整段编进路径。</b>HLS 的播放列表里常是相对文件名，
 * 播放器会拿它相对播放列表地址去拼。把原始地址原样编进路径后，播放器拼出来的地址
 * 仍然落在本端点上，我们就能按「原始地址」回查下载缓存——分片的缓存键正是它自己的
 * 原始地址（{@code SegmentDownloader} 不给分片设自定义键）。整段编码同时避免了路径里
 * 出现 {@code //}，那种地址会被部分客户端与代理先行合并，落到端点里就对不上了。
 *
 * <p>纯逻辑，可直接 JVM 单测。
 */
public final class OfflineDownloadProxyPath {

    /** 端点前缀。 */
    public static final String PREFIX = "/offline/";

    private OfflineDownloadProxyPath() {
    }

    /**
     * 拼出交给播放器的地址。取不到原始地址时返回空串。
     */
    public static String url(String baseAddress, String originalUrl) {
        if (baseAddress == null || baseAddress.isEmpty()) return "";
        if (originalUrl == null || originalUrl.isEmpty()) return "";
        StringBuilder builder = new StringBuilder(baseAddress);
        if (!baseAddress.endsWith("/")) builder.append('/');
        builder.append(PREFIX.substring(1)).append(encode(originalUrl));
        return builder.toString();
    }

    /**
     * 从端点地址里还原原始地址；不是本端点的地址时返回空串。
     */
    public static String originalOf(String path) {
        if (path == null) return "";
        int marker = path.indexOf(PREFIX);
        if (marker < 0) return "";
        String encoded = path.substring(marker + PREFIX.length());
        if (encoded.isEmpty()) return "";
        // 相对地址拼接时可能带上自己的 query，这里只取到第一个 ? 之前。
        return decode(encoded);
    }

    /** 整段编码：只留下不需要转义的字符，其余一律百分号编码。 */
    public static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    public static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }
}
