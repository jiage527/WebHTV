package com.fongmi.android.tv.download;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 重点盯两件事：
 * <ul>
 *   <li>本机回环地址必须挡住——那是 App 自己架的代理端点（{@code http://127.0.0.1:9978}），
 *       下下来只是一段转发的响应，缓存里的东西换台设备再也读不出来。</li>
 *   <li>判不出类型必须放行——很多直链既没后缀也没 MIME，拦住它们会误伤一大批真实可下的地址。</li>
 * </ul>
 *
 * <p>纯 JVM，不依赖 Android。
 */
public class OfflineDownloadUrlTest {

    @Test
    public void acceptsPlainHttpAndHttps() {
        assertTrue(OfflineDownloadUrl.isDownloadable("https://cdn.example.com/a/b.mp4"));
        assertTrue(OfflineDownloadUrl.isDownloadable("http://cdn.example.com/a/b.mp4"));
    }

    /** 没有后缀、没有 MIME，只有一串 query 的地址仍然要放行（Media3 靠响应头嗅探）。 */
    @Test
    public void acceptsUrlsWithoutAnyExtension() {
        assertTrue(OfflineDownloadUrl.isDownloadable("https://cdn.example.com/live/abc?sign=deadbeef&exp=1"));
        assertTrue(OfflineDownloadUrl.isDownloadable("https://cdn.example.com/stream"));
        assertTrue(OfflineDownloadUrl.isDownloadable("https://cdn.example.com/"));
    }

    /** 网盘/种子地址下载器读不了，播放页也不该给下载按钮。 */
    @Test
    public void rejectsNonHttpSchemes() {
        assertFalse(OfflineDownloadUrl.isDownloadable("smb://192.168.1.2/share/a.mkv"));
        assertFalse(OfflineDownloadUrl.isDownloadable("ftp://192.168.1.2/a.mkv"));
        assertFalse(OfflineDownloadUrl.isDownloadable("magnet:?xt=urn:btih:abc"));
        assertFalse(OfflineDownloadUrl.isDownloadable("file:///sdcard/a.mp4"));
        assertFalse(OfflineDownloadUrl.isDownloadable("rtmp://live.example.com/app"));
    }

    @Test
    public void rejectsLoopbackHosts() {
        assertFalse(OfflineDownloadUrl.isDownloadable("http://127.0.0.1:9978/proxy?url=x"));
        assertFalse(OfflineDownloadUrl.isDownloadable("http://127.0.0.1/a.mp4"));
        assertFalse(OfflineDownloadUrl.isDownloadable("http://127.255.255.254/a.mp4"));
        assertFalse(OfflineDownloadUrl.isDownloadable("https://localhost:8443/a.mp4"));
        assertFalse(OfflineDownloadUrl.isDownloadable("http://localhost/a.mp4"));
        assertFalse(OfflineDownloadUrl.isDownloadable("http://0.0.0.0:9978/a.mp4"));
        assertFalse(OfflineDownloadUrl.isDownloadable("http://[::1]:9978/a.mp4"));
    }

    /** 名字里带 localhost 但不是本机的域名（{@code notlocalhost.com}）不能误判。 */
    @Test
    public void doesNotConfuseSimilarHostsWithLoopback() {
        assertTrue(OfflineDownloadUrl.isDownloadable("https://notlocalhost.com/a.mp4"));
        assertTrue(OfflineDownloadUrl.isDownloadable("https://localhost.example.com/a.mp4"));
        assertTrue(OfflineDownloadUrl.isDownloadable("https://128.0.0.1.example.com/a.mp4"));
    }

    /** 局域网/公网地址都是可下的（网盘直链常常就在局域网 NAS 上）。 */
    @Test
    public void acceptsLanAndPublicHosts() {
        assertTrue(OfflineDownloadUrl.isDownloadable("http://192.168.1.20:8080/a.mkv"));
        assertTrue(OfflineDownloadUrl.isDownloadable("http://10.0.0.5/a.mkv"));
        assertTrue(OfflineDownloadUrl.isDownloadable("https://[2001:db8::1]/a.mp4"));
    }

    /** 大小写混写的协议头也算 http(s)。 */
    @Test
    public void schemeMatchIsCaseInsensitive() {
        assertTrue(OfflineDownloadUrl.isDownloadable("HTTPS://cdn.example.com/a.mp4"));
        assertTrue(OfflineDownloadUrl.isDownloadable("Http://cdn.example.com/a.mp4"));
    }

    /** 带凭据的地址：{@code user:pass@} 会被当主机名解析，必须去掉再判断本机。 */
    @Test
    public void stripsCredentialsBeforeCheckingHost() {
        assertFalse(OfflineDownloadUrl.isDownloadable("http://user:pass@127.0.0.1:9978/a.mp4"));
        assertTrue(OfflineDownloadUrl.isDownloadable("http://user:pass@cdn.example.com/a.mp4"));
    }

    @Test
    public void isLocalReportsLoopbackOnly() {
        assertTrue(OfflineDownloadUrl.isLocal("http://127.0.0.1:9978/proxy"));
        assertTrue(OfflineDownloadUrl.isLocal("http://localhost/a"));
        assertFalse(OfflineDownloadUrl.isLocal("http://192.168.1.2/a"));
        assertFalse(OfflineDownloadUrl.isLocal("https://cdn.example.com/a"));
        assertFalse(OfflineDownloadUrl.isLocal(null));
        assertFalse(OfflineDownloadUrl.isLocal(""));
    }

    @Test
    public void handlesNullAndBlank() {
        assertFalse(OfflineDownloadUrl.isDownloadable(null));
        assertFalse(OfflineDownloadUrl.isDownloadable(""));
        assertFalse(OfflineDownloadUrl.isDownloadable("   "));
        assertFalse(OfflineDownloadUrl.isDownloadable("https://"));
        assertTrue(OfflineDownloadUrl.isDownloadable("  https://cdn.example.com/a.mp4  "));
    }

    /** 带 userinfo 的地址去掉凭据后仍需认出本机端口那一段。 */
    @Test
    public void loopbackWithPortVariantsIsRejected() {
        assertFalse(OfflineDownloadUrl.isDownloadable("http://127.0.0.1:80/a.mp4"));
        assertFalse(OfflineDownloadUrl.isDownloadable("http://127.0.0.1:65535/a.mp4"));
        assertFalse(OfflineDownloadUrl.isDownloadable("http://127.0.0.1/a.mp4?x=1#f"));
    }
}
