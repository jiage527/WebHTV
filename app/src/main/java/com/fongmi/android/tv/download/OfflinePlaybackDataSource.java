package com.fongmi.android.tv.download;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.server.Server;

/**
 * 播放离线内容时的开关，把「下载模块」与「播放模块」解耦。
 *
 * <p><b>为什么是「换地址」而不是「塞缓存键」。</b>Media3 官方的离线播放做法是让下载与播放
 * 共用同一个 {@code Cache} 且 cacheKey 一致。那条路只对 Exo 有效：MPV 内核用的是
 * {@code MpvPlayer}，它自己读 URI、根本不走 {@code MediaSourceFactory}。真机验证时正是
 * 这样——下载完整成功，关掉源站后报「连接超时」。所以离线播放只有一个内核无关的做法：
 * 给播放器一个指向本机端点的普通 HTTP 地址（见 {@code server.process.OfflineDownloadProxy}）。
 * 这与项目里既有做法一致：{@code file://} 走 {@code /file/}、{@code smb://} 走 {@code /netdisk}。
 *
 * <p>所有方法都对异常兜底：下载模块没起来不能连带把播放弄挂。
 */
public final class OfflinePlaybackDataSource {

    private OfflinePlaybackDataSource() {
    }

    /**
     * 这一条已经下好了的话，返回指向本机端点的地址；否则返回 null。
     *
     * <p>只有「整段都已在缓存里」才返回地址：缓存被删过一半时交回原始地址，
     * 让播放照旧联网，而不是给出一个读了一半的坏流。
     */
    @Nullable
    public static String proxyUrl(@Nullable String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            OfflineDownloadManager manager = OfflineDownloadManager.get();
            if (!OfflineDownloadCache.complete(manager.cache(), url)) return null;
            String base = address();
            String proxy = OfflineDownloadProxyPath.url(base, url);
            return proxy.isEmpty() ? null : proxy;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 本机服务地址。
     *
     * <p>播放时服务通常已经在跑（网盘播放就是走它），但不保证——用户可能一进 App 就直接
     * 播离线内容。这里补一次启动，避免拿到一个没人监听的端口号，那样离线播放会变成
     * 「连接失败」，看起来像是下载坏了。
     */
    private static String address() {
        Server server = Server.get();
        if (!server.isRunning()) server.start();
        return server.getAddress(true);
    }
}
