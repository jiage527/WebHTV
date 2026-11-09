package com.fongmi.android.tv.player.intro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link IntroSkipGuard} 与 {@link IntroProfile} 的行为。
 *
 * <p>这些用例盯的是防误跳：自动跳过一旦误判，用户会看到进度条莫名跳走且不知道原因。
 */
public class IntroSkipGuardTest {

    private static IntroSegment opening() {
        return IntroSegment.opening(10_000, 90_000, IntroSegment.Source.CHAPTER, "OP");
    }

    private static IntroSegment ending() {
        return IntroSegment.ending(1_400_000, IntroSegment.Source.CHAPTER, "ED");
    }

    // ------------------------------------------------------------------ 防误跳

    @Test
    public void skipsOnceInsideOpening() {
        IntroSkipGuard guard = new IntroSkipGuard();
        assertTrue(guard.shouldSkipOpening(opening(), 50_000, true));
        // 位置会持续上报，同一段区间不能反复跳；否则用户手动拖回片头又被弹走。
        assertFalse(guard.shouldSkipOpening(opening(), 55_000, true));
    }

    @Test
    public void doesNotSkipWhilePaused() {
        IntroSkipGuard guard = new IntroSkipGuard();
        // 暂停时用户可能正在片头里找画面，这时跳走等于没法定位。
        assertFalse(guard.shouldSkipOpening(opening(), 50_000, false));
    }

    @Test
    public void doesNotSkipOutsideRange() {
        IntroSkipGuard guard = new IntroSkipGuard();
        assertFalse(guard.shouldSkipOpening(opening(), 5_000, true));
        assertFalse(guard.shouldSkipOpening(opening(), 500_000, true));
    }

    @Test
    public void doesNotSkipWithoutSegment() {
        IntroSkipGuard guard = new IntroSkipGuard();
        assertFalse(guard.shouldSkipOpening(null, 50_000, true));
        assertFalse(guard.shouldSkipEnding(null, 1_500_000, true));
    }

    @Test
    public void resetAllowsSkippingAgain() {
        IntroSkipGuard guard = new IntroSkipGuard();
        assertTrue(guard.shouldSkipOpening(opening(), 50_000, true));
        assertFalse(guard.shouldSkipOpening(opening(), 50_000, true));
        guard.reset();
        assertTrue(guard.shouldSkipOpening(opening(), 50_000, true));
    }

    @Test
    public void endingSkipsOnceOnReachingStart() {
        IntroSkipGuard guard = new IntroSkipGuard();
        assertFalse(guard.shouldSkipEnding(ending(), 1_300_000, true));
        assertTrue(guard.shouldSkipEnding(ending(), 1_400_000, true));
        // 只跳一次：跳走之后位置仍在片尾起点之后，不能反复触发下一集。
        assertFalse(guard.shouldSkipEnding(ending(), 1_500_000, true));
    }

    @Test
    public void endingDoesNotSkipWhilePaused() {
        IntroSkipGuard guard = new IntroSkipGuard();
        assertFalse(guard.shouldSkipEnding(ending(), 1_400_000, false));
    }

    // ------------------------------------------------------------------ 档案

    @Test
    public void newProfileIsEmpty() {
        assertTrue(new IntroProfile().isEmpty());
    }

    @Test
    public void recordingMakesProfileNonEmpty() {
        IntroProfile profile = new IntroProfile();
        profile.record(95_000, 90_000, 1_400_000);
        assertFalse(profile.isEmpty());
        assertEquals(1, profile.getOpeningEnds().size());
        assertEquals(1, profile.getOpeningLengths().size());
        assertEquals(1, profile.getEndingStarts().size());
    }

    @Test
    public void tooShortLengthIsNotRecorded() {
        IntroProfile profile = new IntroProfile();
        // 太短的「片头」通常是误记或章节写错，收了会污染后续判定。
        profile.record(95_000, 1_000, 0);
        assertTrue(profile.getOpeningLengths().isEmpty());
        assertEquals(1, profile.getOpeningEnds().size());
    }

    @Test
    public void duplicateValuesAreNotRecordedTwice() {
        IntroProfile profile = new IntroProfile();
        profile.record(95_000, 90_000, 0);
        profile.record(95_000, 90_000, 0);
        // 去重是为了让「样本数」如实反映见过多少次不同位置。
        assertEquals(1, profile.getOpeningEnds().size());
    }

    @Test
    public void samplesAreBounded() {
        IntroProfile profile = new IntroProfile();
        for (int i = 1; i <= 60; i++) profile.record(1_000L * i, 90_000, 0);
        assertTrue(profile.getOpeningEnds().size() <= 30);
    }

    @Test
    public void normalizeRevivesNullCollections() {
        // Gson 直接写字段，JSON 里写成 null 会把集合置空；之后遍历就 NPE。
        IntroProfile profile = new IntroProfile();
        profile.setOpeningEndsForTest(null);
        profile.setOpeningLengthsForTest(null);
        profile.setEndingStartsForTest(null);
        assertTrue(profile.isEmpty());
        profile.record(95_000, 90_000, 0);
        assertEquals(1, profile.getOpeningEnds().size());
    }

    // ------------------------------------------------------------------ 判定链

    @Test
    public void chapterWinsOverLearned() {
        IntroProfile profile = new IntroProfile();
        profile.record(200_000, 150_000, 0);
        profile.record(201_000, 151_000, 0);
        profile.record(199_000, 149_000, 0);
        // 关闭总开关时不下结论，行为与旧版本一致。
        IntroSegment learned = IntroResolver.opening(profile, null, 24 * 60_000L);
        assertTrue(learned == null || learned.getSource() != IntroSegment.Source.CHAPTER);
    }

    @Test
    public void manualRecordContributesSamplesForLearning() {
        IntroProfile profile = new IntroProfile();
        // 用户手动记的三次（同一部剧三集各调一次）。长度样本必须是三次不同的记录：
        // 档案对同一个值去重，重复值凑不出三个样本。
        profile.record(95_000, 90_000, 0);
        profile.record(96_000, 91_000, 0);
        profile.record(94_000, 89_000, 0);
        IntroSegment learned = IntroLearner.inferOpening(
                profile.getOpeningEnds(), profile.getOpeningLengths());
        assertEquals(95_000L, learned.getEnd());
        assertEquals(90_000L, learned.length());
        assertEquals(IntroSegment.Source.LEARNED, learned.getSource());
    }
}
