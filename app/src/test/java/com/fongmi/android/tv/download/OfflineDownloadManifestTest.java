package com.fongmi.android.tv.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 重点盯两件事：分片行必须改写成走本机端点（不改的话断网时「列表读到了、画面出不来」）；
 * 相对文件名必须按播放列表地址解析成绝对地址（否则播放器会把端点地址当自己的相对路径）。
 */
public class OfflineDownloadManifestTest {

    private static final String BASE = "http://127.0.0.1:9978";
    private static final String MANIFEST = "http://cdn.example.com/hls/index.m3u8";

    @Test
    public void rewriteSendsAbsoluteSegmentLinesThroughTheProxy() {
        String text = "#EXTM3U\n#EXTINF:10,\nhttp://cdn.example.com/hls/seg1.ts\n";
        String rewritten = OfflineDownloadManifest.rewrite(text, MANIFEST, BASE);
        List<String> uris = OfflineDownloadManifest.uris(rewritten);
        assertEquals(1, uris.size());
        assertTrue(uris.get(0).startsWith(BASE + "/offline/"));
        assertEquals("http://cdn.example.com/hls/seg1.ts", OfflineDownloadProxyPath.originalOf(stripHost(uris.get(0))));
    }

    @Test
    public void rewriteResolvesRelativeSegmentNames() {
        String text = "#EXTM3U\n#EXTINF:10,\nseg2.ts\n";
        String rewritten = OfflineDownloadManifest.rewrite(text, MANIFEST, BASE);
        String uri = OfflineDownloadManifest.uris(rewritten).get(0);
        assertEquals("http://cdn.example.com/hls/seg2.ts", OfflineDownloadProxyPath.originalOf(stripHost(uri)));
    }

    @Test
    public void rewriteKeepsTagsUntouched() {
        String text = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:10,\nseg.ts\n";
        String rewritten = OfflineDownloadManifest.rewrite(text, MANIFEST, BASE);
        assertTrue(rewritten.contains("#EXT-X-VERSION:3"));
        assertTrue(rewritten.contains("#EXTM3U"));
    }

    @Test
    public void rewriteSendsTheInitSegmentThroughTheProxy() {
        // fMP4 的初始化段必须一起改写，否则播到一半才发现缺它。
        String text = "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:10,\nseg.m4s\n";
        String rewritten = OfflineDownloadManifest.rewrite(text, MANIFEST, BASE);
        assertTrue(rewritten.contains(BASE + "/offline/"));
        assertTrue(rewritten.contains("#EXT-X-MAP:URI=\""));
    }

    @Test
    public void rewriteLeavesKeyUrisAlone() {
        // 密钥通常不在下载范围内，改写只会把一个「联网可能成功」的请求变成必然失败的本机 404。
        String text = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\nseg.ts\n";
        String rewritten = OfflineDownloadManifest.rewrite(text, MANIFEST, BASE);
        assertTrue(rewritten.contains("URI=\"key.bin\""));
    }

    @Test
    public void rewritePreservesLineCount() {
        String text = "#EXTM3U\n#EXTINF:10,\na.ts\n#EXTINF:10,\nb.ts\n";
        String rewritten = OfflineDownloadManifest.rewrite(text, MANIFEST, BASE);
        assertEquals(text.split("\n", -1).length, rewritten.split("\n", -1).length);
    }

    @Test
    public void rewriteHandlesWindowsLineEndings() {
        String text = "#EXTM3U\r\n#EXTINF:10,\r\na.ts\r\n";
        String rewritten = OfflineDownloadManifest.rewrite(text, MANIFEST, BASE);
        assertEquals(1, OfflineDownloadManifest.uris(rewritten).size());
        assertTrue(OfflineDownloadManifest.uris(rewritten).get(0).contains("a.ts"));
    }

    @Test
    public void rewriteHandlesMissingInput() {
        assertEquals("", OfflineDownloadManifest.rewrite(null, MANIFEST, BASE));
        assertEquals("", OfflineDownloadManifest.rewrite("", MANIFEST, BASE));
        assertTrue(OfflineDownloadManifest.uris(null).isEmpty());
    }

    @Test
    public void absolutizeReturnsEmptyWhenItCannotResolve() {
        assertEquals("", OfflineDownloadManifest.absolutize("seg.ts", null));
        assertEquals("", OfflineDownloadManifest.absolutize("", MANIFEST));
        assertEquals("http://cdn/a.ts", OfflineDownloadManifest.absolutize("http://cdn/a.ts", null));
    }

    /** 去掉主机部分，留下路径，模拟 NanoHTTPD 交给 Process 的那种 url。 */
    private static String stripHost(String url) {
        int scheme = url.indexOf("://");
        int slash = url.indexOf('/', scheme + 3);
        return slash < 0 ? "/" : url.substring(slash);
    }
}
