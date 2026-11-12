package com.fongmi.android.tv.download;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 HLS/DASH 播放列表里的子资源地址改写成经过本机离线端点的地址。
 *
 * <p><b>为什么必须改写。</b>播放列表里的分片有两种写法：绝对地址（{@code http://cdn/seg1.ts}）
 * 和相对文件名（{@code seg1.ts}）。若原样交给播放器，两种写法都会绕开本机端点去联网，
 * 断网时表现为「列表读到了、画面却出不来」。所以读列表时就把地址换成指向本端点的地址，
 * 播放器再来取分片时自然落在我们手里。
 *
 * <p>相对地址按播放列表自身地址解析成绝对地址后再改写——否则拼出来的端点地址会被
 * 播放器当成它自己的相对路径，指到不存在的地方。
 *
 * <p><b>哪些行改写、哪些不改。</b>{@code #EXT-X-MAP} 的初始化段会被下载器一并取回
 * （fMP4 格式必需），必须改写，否则播到一半才发现缺初始化段；{@code #EXT-X-KEY} 的密钥
 * 地址不改写——密钥通常不在下载范围内，改写只会把一个「联网可能成功」的请求变成
 * 必然失败的本机 404。
 *
 * <p>纯逻辑，可直接 JVM 单测。
 */
public final class OfflineDownloadManifest {

    private OfflineDownloadManifest() {
    }

    /**
     * 改写播放列表正文。
     *
     * @param text        播放列表正文
     * @param manifestUrl 播放列表自身的原始地址，用来解析相对地址
     * @param base        本机服务地址（{@code http://127.0.0.1:端口}）
     */
    public static String rewrite(String text, String manifestUrl, String base) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length() + 256);
        for (int i = 0; i < lines.length; i++) {
            out.append(rewriteLine(lines[i], manifestUrl, base));
            // 还原原有的换行结构：split 用 -1 会把结尾的换行变成一个空串。
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    private static String rewriteLine(String line, String manifestUrl, String base) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return line;
        if (trimmed.startsWith("#")) {
            if (trimmed.startsWith("#EXT-X-MAP")) return rewriteAttribute(line, manifestUrl, base);
            return line;
        }
        String absolute = absolutize(trimmed, manifestUrl);
        if (absolute.isEmpty()) return line;
        String proxy = OfflineDownloadProxyPath.url(base, absolute);
        return proxy.isEmpty() ? line : proxy;
    }

    /**
     * 改写形如 {@code #EXT-X-MAP:URI="init.mp4"} 里的 URI 属性。
     */
    private static String rewriteAttribute(String line, String manifestUrl, String base) {
        int marker = indexOfUriAttribute(line);
        if (marker < 0) return line;
        int quote = line.indexOf('"', marker);
        if (quote < 0) return line;
        int endQuote = line.indexOf('"', quote + 1);
        if (endQuote < 0) return line;
        String value = line.substring(quote + 1, endQuote);
        String absolute = absolutize(value, manifestUrl);
        if (absolute.isEmpty()) return line;
        String proxy = OfflineDownloadProxyPath.url(base, absolute);
        if (proxy.isEmpty()) return line;
        return line.substring(0, quote + 1) + proxy + line.substring(endQuote);
    }

    private static int indexOfUriAttribute(String line) {
        int at = line.indexOf("URI=");
        return at < 0 ? -1 : at + "URI=".length();
    }

    /**
     * 把播放列表里的地址解析成绝对地址；解析不出来时返回空串（调用方会退回不改写）。
     */
    static String absolutize(String value, String manifestUrl) {
        if (value == null || value.isEmpty()) return "";
        try {
            URI resolved = URI.create(value);
            if (resolved.isAbsolute()) return value;
            if (manifestUrl == null || manifestUrl.isEmpty()) return "";
            return URI.create(manifestUrl).resolve(value).toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 列出正文里指向媒体的地址行（注释行不计）。单测与排查用。
     */
    public static List<String> uris(String text) {
        List<String> list = new ArrayList<>();
        if (text == null) return list;
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            list.add(trimmed);
        }
        return list;
    }
}
