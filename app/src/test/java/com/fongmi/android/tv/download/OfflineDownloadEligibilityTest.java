package com.fongmi.android.tv.download;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 盯的是用户实测反馈的那条：40 秒的 MP4 在播放页没有下载按钮。
 *
 * <p>根因是旧判定拿「时长 &gt; 1 分钟」当「点播」，短片被判成直播。这里把新规则钉住，
 * 尤其是「不长但也不是直播」这一档必须放行——它正是这次要修的那一格。
 *
 * <p>纯 JVM，不依赖 Android。
 */
public class OfflineDownloadEligibilityTest {

    /** 40 秒的 MP4：时长已知、不直播、不动态 —— 必须能下。这是本次修复的核心用例。 */
    @Test
    public void shortVodIsAllowed() {
        assertTrue(OfflineDownloadEligibility.allows(false, false, true));
    }

    /** 正常的点播：时长已知、非直播。 */
    @Test
    public void ordinaryVodIsAllowed() {
        assertTrue(OfflineDownloadEligibility.allows(false, false, true));
    }

    /** 直播：不给下，无论时长知不知道。 */
    @Test
    public void liveIsRefused() {
        assertFalse(OfflineDownloadEligibility.allows(true, false, false));
        assertFalse(OfflineDownloadEligibility.allows(true, true, true));
    }

    /**
     * dynamic 且时长未知 —— MPV 与 IJK 就是这么标直播的（时长永远探不到）。
     * 这一档必须拦，否则用户会对着一路直播点下载。
     */
    @Test
    public void dynamicWithoutKnownDurationIsRefused() {
        assertFalse(OfflineDownloadEligibility.allows(false, true, false));
    }

    /**
     * dynamic 但时长已经探到 —— 是「分段媒体还在长」而不是直播。
     * 时长已知说明它有个尽头，可以下。
     */
    @Test
    public void dynamicWithKnownDurationIsAllowed() {
        assertTrue(OfflineDownloadEligibility.allows(false, true, true));
    }

    /**
     * 时长还没探到、也不 dynamic：这是「刚起播、metadata 还没到」的普通 MP4。
     * 放行，否则刚点开的那一两秒按钮会闪一下才出现。
     */
    @Test
    public void unknownDurationWithoutDynamicIsAllowed() {
        assertTrue(OfflineDownloadEligibility.allows(false, false, false));
    }

    /**
     * 旧判定与这里的分歧点，写下来防止有人「顺手统一回去」：
     * 40 秒的片子旧判定给的是「直播」（时长 &lt; 1 分钟），新判定给「可下载」。
     */
    @Test
    public void thirtyNineSecondClipDiffersFromTheLegacyOneMinuteRule() {
        long durationMs = 39_000L;
        boolean legacyVod = durationMs > java.util.concurrent.TimeUnit.MINUTES.toMillis(1);
        boolean legacyLive = durationMs < java.util.concurrent.TimeUnit.MINUTES.toMillis(1);
        assertFalse(legacyVod);
        assertTrue(legacyLive);
        // 新规则只看「是不是直播」，与秒数无关。
        assertTrue(OfflineDownloadEligibility.allows(false, false, true));
    }
}
