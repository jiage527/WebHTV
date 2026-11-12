package com.fongmi.android.tv.download;

import java.util.Locale;

/**
 * 「这个地址能不能离线下载」的判定。
 *
 * <p>单独抽出来是为了让播放页与下载入口用同一套判断：播放页据此决定要不要显示下载按钮，
 * 入口据此再挡一次（用户可能刚播到一半地址就被换成了代理地址）。两边判断不一致会出现
 * 「看得见按钮、点了却说不能下」，那是很典型的半成品体验。
 *
 * <p><b>要挡住哪两类。</b>
 * <ul>
 *   <li>非 http(s)：{@code smb://}、{@code ftp://}、{@code magnet:} 这些要么没有分段语义，
 *       要么下载器根本读不了（播放器读 smb 都还要绕本机代理）。</li>
 *   <li>本机回环：App 自己就架着一个代理服务（{@code Server.getAddress(true)} 是
 *       {@code http://127.0.0.1:端口}），网盘播放就是这样走本机代理的。把这种地址丢给下载器，
 *       下到的是一段「代理转发响应」，缓存里存下的东西换台设备再也读不出来。</li>
 * </ul>
 *
 * <p><b>判不出类型仍然放行。</b>很多直链没有后缀、也没有 MIME（例如 {@code /live/abc?sign=…}），
 * 拦住它们会误伤一大批真实可下的地址；Media3 会按响应头自己嗅探，判不出来才是它的事。
 *
 * <p>本类不碰 Android API，可直接 JVM 单测。
 */
public final class OfflineDownloadUrl {

    private OfflineDownloadUrl() {
    }

    /**
     * 能不能交给离线下载器。
     *
     * @param url 播放器手上的真实地址（不是历史里那条可能还需二次解析的地址）
     */
    public static boolean isDownloadable(String url) {
        if (url == null) return false;
        String value = url.trim();
        if (value.isEmpty()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
        String authority = authorityOf(lower);
        // 光有协议头没有主机（"https://"、":8080"）不是能下的地址，丢给下载器只会换来一条
        // 看不懂的报错，不如在这里就拦住。
        if (hostOf(authority).isEmpty()) return false;
        return !isLocalHost(authority);
    }

    /** 是不是指向本机（App 自己的代理端点）。 */
    public static boolean isLocal(String url) {
        if (url == null) return false;
        return isLocalHost(authorityOf(url.trim().toLowerCase(Locale.ROOT)));
    }

    /** 取 authority（{@code host[:port]}）；取不到时返回空串。 */
    private static String authorityOf(String value) {
        int scheme = value.indexOf("://");
        String rest = scheme < 0 ? value : value.substring(scheme + 3);
        int cut = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                cut = i;
                break;
            }
        }
        String authority = rest.substring(0, cut);
        // 去掉 user:pass@，否则带凭据的地址（用户直接粘的那种）会被别人当主机名。
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        return authority;
    }

    private static boolean isLocalHost(String authority) {
        if (authority.isEmpty()) return false;
        String host = hostOf(authority);
        if (host.equals("localhost") || host.endsWith(".localhost")) return true;
        if (host.equals("::1") || host.equals("[::1]")) return true;
        if (host.equals("0.0.0.0")) return true;
        // 127.0.0.0/8 全是回环，整段都算本机。
        return host.startsWith("127.");
    }

    /** 从 authority 里取主机（去掉端口、去掉 IPv6 的方括号）。 */
    private static String hostOf(String authority) {
        String value = authority;
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end < 0 ? value : value.substring(1, end);
        }
        int colon = value.indexOf(':');
        return colon < 0 ? value : value.substring(0, colon);
    }
}
