package com.fongmi.android.tv.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 重点盯两件事：
 * <ul>
 *   <li>m3u8/mpd 必须被判成分片类型——判成普通文件去下，只会下到一个几百字节的播放列表，
 *       用户看到的是「下载完成但播不了」，这种错比「下载失败」难查得多。</li>
 *   <li>带 query 的地址必须取对后缀——否则带签名的地址会取到 {@code token=.mp4} 里的 {@code mp4}，
 *       把 HLS 判成普通文件。</li>
 * </ul>
 *
 * <p>纯 JVM，不需要 Android 环境。
 */
public class OfflineDownloadKindTest {

    @Test
    public void recognizesHlsByExtension() {
        assertEquals(OfflineDownloadKind.HLS, OfflineDownloadKind.ofUrl("https://cdn.example.com/live/index.m3u8"));
        assertEquals(OfflineDownloadKind.HLS, OfflineDownloadKind.ofUrl("https://cdn.example.com/live/index.m3u"));
    }

    @Test
    public void recognizesDashByExtension() {
        assertEquals(OfflineDownloadKind.DASH, OfflineDownloadKind.ofUrl("https://cdn.example.com/vod/manifest.mpd"));
    }

    @Test
    public void recognizesProgressiveByExtension() {
        assertEquals(OfflineDownloadKind.PROGRESSIVE, OfflineDownloadKind.ofUrl("https://cdn.example.com/a/b.mp4"));
        assertEquals(OfflineDownloadKind.PROGRESSIVE, OfflineDownloadKind.ofUrl("https://cdn.example.com/a/b.mkv"));
        assertEquals(OfflineDownloadKind.PROGRESSIVE, OfflineDownloadKind.ofUrl("https://cdn.example.com/a/b.mp3"));
    }

    /** 后缀大小写与结尾的空格都不该影响判定。 */
    @Test
    public void extensionIsCaseInsensitive() {
        assertEquals(OfflineDownloadKind.HLS, OfflineDownloadKind.ofUrl("https://cdn.example.com/a/b.M3U8"));
        assertEquals(OfflineDownloadKind.PROGRESSIVE, OfflineDownloadKind.ofUrl("https://cdn.example.com/a/b.MP4"));
    }

    /** 带签名参数的地址：后缀要取问号之前那一段，否则会把 m3u8 当成后面的 token。 */
    @Test
    public void stripsQueryAndFragmentBeforeTakingExtension() {
        assertEquals(OfflineDownloadKind.HLS, OfflineDownloadKind.ofUrl("https://cdn.example.com/a/b.m3u8?sign=x.mp4"));
        assertEquals(OfflineDownloadKind.HLS, OfflineDownloadKind.ofUrl("https://cdn.example.com/a/b.m3u8?token=.mp4&exp=1"));
        assertEquals(OfflineDownloadKind.HLS, OfflineDownloadKind.ofUrl("https://cdn.example.com/a/b.m3u8#frag.mp4"));
    }

    /** 点出现的位置必须在最后一个斜杠之后，否则 {@code /v1.2/stream} 会被当成后缀 2。 */
    @Test
    public void dotBeforeLastSlashIsNotAnExtension() {
        assertEquals("", OfflineDownloadKind.extensionOf("https://cdn.example.com/v1.2/stream"));
        assertEquals(OfflineDownloadKind.UNKNOWN, OfflineDownloadKind.ofUrl("https://cdn.example.com/v1.2/stream"));
    }

    @Test
    public void extensionRejectsTrailingDotAndEmpty() {
        assertEquals("", OfflineDownloadKind.extensionOf("https://cdn.example.com/a/b."));
        assertEquals("", OfflineDownloadKind.extensionOf(""));
        assertEquals("", OfflineDownloadKind.extensionOf(null));
        assertEquals(OfflineDownloadKind.UNKNOWN, OfflineDownloadKind.ofUrl("https://cdn.example.com/a/b"));
    }

    /** MIME 优先于后缀：服务端给 m3u8 的 MIME 但地址没后缀时必须判成 HLS。 */
    @Test
    public void mimeWinsOverExtension() {
        assertEquals(OfflineDownloadKind.HLS, OfflineDownloadKind.of("https://cdn.example.com/play?id=1", "application/x-mpegURL"));
        assertEquals(OfflineDownloadKind.DASH, OfflineDownloadKind.of("https://cdn.example.com/play?id=1", "application/dash+xml"));
    }

    @Test
    public void mimeMatchingIsCaseInsensitive() {
        assertEquals(OfflineDownloadKind.HLS, OfflineDownloadKind.of("https://a/b", "APPLICATION/X-MPEGURL"));
    }

    /** 认不出的 MIME 要返回 null（让调用方继续看后缀），而不是直接判 UNKNOWN。 */
    @Test
    public void unknownMimeFallsBackToExtension() {
        assertNull(OfflineDownloadKind.ofMimeType("video/mp4"));
        assertNull(OfflineDownloadKind.ofMimeType(""));
        assertNull(OfflineDownloadKind.ofMimeType(null));
        assertEquals(OfflineDownloadKind.PROGRESSIVE, OfflineDownloadKind.of("https://cdn.example.com/a/b.mp4", "text/plain"));
    }

    /** 两个都认不出时是 UNKNOWN，交给 Media3 用响应头嗅探，不能拦。 */
    @Test
    public void unknownWhenNeitherMimeNorExtensionHelps() {
        assertEquals(OfflineDownloadKind.UNKNOWN, OfflineDownloadKind.of("https://cdn.example.com/live/abc?sign=1", ""));
        assertEquals(OfflineDownloadKind.UNKNOWN, OfflineDownloadKind.of(null, null));
    }

    /** 分片类型要写 MIME（Media3 靠它挑下载器），普通文件要留 null 让它自己判。 */
    @Test
    public void onlySegmentedKindsCarryMimeType() {
        assertTrue(OfflineDownloadKind.HLS.isSegmented());
        assertTrue(OfflineDownloadKind.DASH.isSegmented());
        assertFalse(OfflineDownloadKind.PROGRESSIVE.isSegmented());
        assertFalse(OfflineDownloadKind.UNKNOWN.isSegmented());
        assertEquals("application/x-mpegURL", OfflineDownloadKind.HLS.mimeType());
        assertEquals("application/dash+xml", OfflineDownloadKind.DASH.mimeType());
        assertNull(OfflineDownloadKind.PROGRESSIVE.mimeType());
        assertNull(OfflineDownloadKind.UNKNOWN.mimeType());
    }
}
