package com.fongmi.android.tv.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 重点盯两件事：带上签名的地址必须原样编进路径、并且能原样还原（还原错误会让端点去
 * 取一个不存在的地址，表现为「下载好的东西放不了」）；相对地址必须按播放列表地址解析
 * 成绝对地址后再改写（否则播放器会把端点地址当自己的相对路径）。
 */
public class OfflineDownloadProxyPathTest {

    @Test
    public void urlPrefixesTheBaseAddress() {
        String result = OfflineDownloadProxyPath.url("http://127.0.0.1:9978", "http://cdn/a/b.m3u8");
        assertTrue(result.startsWith("http://127.0.0.1:9978/offline/"));
    }

    @Test
    public void urlKeepsTheOriginalUrlRecoverable() {
        String original = "http://cdn.example.com/hls/index.m3u8?sign=abc%2Bd&t=1";
        String proxy = OfflineDownloadProxyPath.url("http://127.0.0.1:9978", original);
        assertEquals(original, OfflineDownloadProxyPath.originalOf(stripHost(proxy)));
    }

    @Test
    public void urlEncodesThePathSoNoDoubleSlashSurvives() {
        String proxy = OfflineDownloadProxyPath.url("http://127.0.0.1:9978", "http://cdn/a/b.ts");
        // 原始的 "//" 必须被编掉：留着它会被部分客户端先行合并，落到端点里就对不上。
        String path = stripHost(proxy);
        assertTrue(path.startsWith("/offline/"));
        // 端点前缀只出现一次——出现两次说明原始地址没被整段编码。
        assertEquals(path.indexOf("/offline/"), path.lastIndexOf("/offline/"));
        assertFalse(path.contains("//"));
        assertEquals("http://cdn/a/b.ts", OfflineDownloadProxyPath.originalOf(path));
    }

    @Test
    public void urlHandlesBaseAddressWithTrailingSlash() {
        String proxy = OfflineDownloadProxyPath.url("http://127.0.0.1:9978/", "http://cdn/a.ts");
        assertTrue(proxy.startsWith("http://127.0.0.1:9978/offline/"));
    }

    @Test
    public void urlReturnsEmptyWhenEitherSideIsMissing() {
        assertEquals("", OfflineDownloadProxyPath.url(null, "http://cdn/a.ts"));
        assertEquals("", OfflineDownloadProxyPath.url("", "http://cdn/a.ts"));
        assertEquals("", OfflineDownloadProxyPath.url("http://127.0.0.1:9978", null));
        assertEquals("", OfflineDownloadProxyPath.url("http://127.0.0.1:9978", ""));
    }

    @Test
    public void originalOfIgnoresForeignPaths() {
        assertEquals("", OfflineDownloadProxyPath.originalOf("/netdisk?account=x"));
        assertEquals("", OfflineDownloadProxyPath.originalOf("/offline/"));
        assertEquals("", OfflineDownloadProxyPath.originalOf(null));
    }

    @Test
    public void encodeAndDecodeSurviveUnicodeAndSymbols() {
        String value = "http://cdn/影片 第1集.ts?k=a b&x=1";
        assertEquals(value, OfflineDownloadProxyPath.decode(OfflineDownloadProxyPath.encode(value)));
    }

    /** 去掉主机部分，留下路径，模拟 NanoHTTPD 交给 Process 的那种 url。 */
    private static String stripHost(String url) {
        int scheme = url.indexOf("://");
        int slash = url.indexOf('/', scheme + 3);
        return slash < 0 ? "/" : url.substring(slash);
    }
}
