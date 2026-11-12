package com.fongmi.android.tv.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;

import org.junit.Test;

/**
 * 钉住「哪些内容类型不许带 customCacheKey」这条 Media3 规则。
 *
 * <p>回归背景：播放页点「下载」闪退，栈里是
 * {@code IllegalArgumentException: customCacheKey must be null for type: 2}。
 * 这里把 type 的编号本身也断言住——Media3 哪天改了编号，这条测试先炸，
 * 而不是等用户点一下下载又闪退一次。
 */
public class OfflineDownloadCacheKeyTest {

    @Test
    public void contentTypeNumbersMatchTheValuesMedia3Uses() {
        assertEquals(0, C.CONTENT_TYPE_DASH);
        assertEquals(1, C.CONTENT_TYPE_SS);
        assertEquals(2, C.CONTENT_TYPE_HLS);
        assertEquals(3, C.CONTENT_TYPE_RTSP);
        assertEquals(4, C.CONTENT_TYPE_OTHER);
    }

    /** type 2 = HLS，就是崩在报错里那个编号。 */
    @Test
    public void hlsRefusesTheCustomCacheKey() {
        assertFalse(OfflineDownloadCacheKey.allows(C.CONTENT_TYPE_HLS));
    }

    /** type 1 = SmoothStreaming，同一条 checkArgument 也把它挡掉。 */
    @Test
    public void smoothStreamingRefusesTheCustomCacheKey() {
        assertFalse(OfflineDownloadCacheKey.allows(C.CONTENT_TYPE_SS));
    }

    /**
     * type 0 = DASH。这条最容易被漏掉——凭直觉会以为只有 HLS/SS 受限，
     * 但字节码里 type==0 也跳到了 checkArgument。
     */
    @Test
    public void dashRefusesTheCustomCacheKey() {
        assertFalse(OfflineDownloadCacheKey.allows(C.CONTENT_TYPE_DASH));
    }

    /** 普通单文件（mp4/mkv…）走这一支，也是唯一在真机上验过的一支。 */
    @Test
    public void progressiveAcceptsTheCustomCacheKey() {
        assertTrue(OfflineDownloadCacheKey.allows(C.CONTENT_TYPE_OTHER));
    }

    @Test
    public void rtspAcceptsTheCustomCacheKey() {
        assertTrue(OfflineDownloadCacheKey.allows(C.CONTENT_TYPE_RTSP));
    }
}
