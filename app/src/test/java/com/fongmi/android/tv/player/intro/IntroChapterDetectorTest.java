package com.fongmi.android.tv.player.intro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link IntroChapterDetector} 的章节识别规则。
 *
 * <p>这些用例盯的是「不要把不是片头的章节认成片头」：章节标题是网友或压制组随手写的，
 * 误判的代价是每次播放都从中间跳走，用户还找不到原因。
 */
public class IntroChapterDetectorTest {

    private static final long DURATION = 24 * 60_000L;

    private static List<IntroChapterPoint> of(Object... pairs) {
        List<IntroChapterPoint> points = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            points.add(new IntroChapterPoint((Long) pairs[i], (String) pairs[i + 1]));
        }
        return points;
    }

    // ------------------------------------------------------------------ 片头

    @Test
    public void detectsOpeningFromTitleAndNextChapter() {
        IntroSegment segment = IntroChapterDetector.detectOpening(
                of(0L, "Opening", 95_000L, "Part A"), DURATION);
        assertNotNull(segment);
        assertEquals(0, segment.getStart());
        assertEquals(95_000, segment.getEnd());
        assertEquals(IntroSegment.Source.CHAPTER, segment.getSource());
    }

    @Test
    public void detectsOpeningNotStartingAtZero() {
        IntroSegment segment = IntroChapterDetector.detectOpening(
                of(0L, "Cold Open", 8_000L, "OP", 98_000L, "Part A"), DURATION);
        assertNotNull(segment);
        assertEquals(8_000, segment.getStart());
        assertEquals(98_000, segment.getEnd());
    }

    @Test
    public void requiresNextChapterToKnownEnd() {
        // 片头是最后一个章节时放弃：猜终点等于编数据。
        assertNull(IntroChapterDetector.detectOpening(of(0L, "Cold Open", 90_000L, "Opening"), DURATION));
        // 只有一条章节时也没有下一个可用。
        assertNull(IntroChapterDetector.detectOpening(of(0L, "Opening"), DURATION));
    }

    @Test
    public void requiresAtLeastTwoChapters() {
        assertNull(IntroChapterDetector.detectOpening(of(0L, "Opening"), DURATION));
        assertNull(IntroChapterDetector.detectOpening(null, DURATION));
        assertNull(IntroChapterDetector.detectOpening(new ArrayList<>(), DURATION));
    }

    @Test
    public void rejectsOpeningTooShortToBotherSkipping() {
        assertNull(IntroChapterDetector.detectOpening(
                of(0L, "OP", 2_000L, "Part A"), DURATION));
    }

    @Test
    public void rejectsOpeningLongerThanProductLimit() {
        // 超过手动记录允许的上限，说明标记写错了：自动结果不该超出用户能改的范围。
        assertNull(IntroChapterDetector.detectOpening(
                of(0L, "OP", 8 * 60_000L, "Part A"), DURATION));
    }

    @Test
    public void rejectsOpeningStartingBeyondLimit() {
        assertNull(IntroChapterDetector.detectOpening(
                of(10 * 60_000L, "OP", 12 * 60_000L, "Part A"), DURATION));
    }

    @Test
    public void limitFollowsDuration() {
        assertEquals(3 * 60_000L, IntroChapterDetector.limitOf(10 * 60_000L));
        assertEquals(6 * 60_000L, IntroChapterDetector.limitOf(20 * 60_000L));
        assertEquals(10 * 60_000L, IntroChapterDetector.limitOf(45 * 60_000L));
    }

    @Test
    public void zeroDurationYieldsNothing() {
        assertNull(IntroChapterDetector.detectOpening(of(0L, "OP", 90_000L, "A"), 0));
        assertNull(IntroChapterDetector.detectEnding(of(0L, "ED"), 0));
    }

    // ------------------------------------------------------------------ 标题匹配

    @Test
    public void matchesOpeningTitles() {
        assertTrue(IntroChapterDetector.isOpening("OP"));
        assertTrue(IntroChapterDetector.isOpening("op"));
        assertTrue(IntroChapterDetector.isOpening("OP1"));
        assertTrue(IntroChapterDetector.isOpening("Opening"));
        assertTrue(IntroChapterDetector.isOpening("Intro"));
        assertTrue(IntroChapterDetector.isOpening("片头"));
        assertTrue(IntroChapterDetector.isOpening("片头曲"));
        assertTrue(IntroChapterDetector.isOpening("主题曲"));
    }

    @Test
    public void matchesEndingTitles() {
        assertTrue(IntroChapterDetector.isEnding("ED"));
        assertTrue(IntroChapterDetector.isEnding("ED2"));
        assertTrue(IntroChapterDetector.isEnding("Ending"));
        assertTrue(IntroChapterDetector.isEnding("Outro"));
        assertTrue(IntroChapterDetector.isEnding("片尾"));
        assertTrue(IntroChapterDetector.isEnding("片尾曲"));
    }

    @Test
    public void doesNotMatchWordsThatMerelyStartWithOpOrEd() {
        // ED 只有两个字母，按子串匹配会把 Edit、Reduced 这类章节名全认成片尾。
        assertFalse(IntroChapterDetector.isEnding("Edited by x"));
        assertFalse(IntroChapterDetector.isEnding("Editor notes"));
        assertFalse(IntroChapterDetector.isOpening("Opponent"));
        assertFalse(IntroChapterDetector.isOpening("Options"));
    }

    @Test
    public void doesNotMatchOrdinaryChapterNames() {
        assertFalse(IntroChapterDetector.isOpening("Part A"));
        assertFalse(IntroChapterDetector.isOpening(""));
        assertFalse(IntroChapterDetector.isOpening(null));
        assertFalse(IntroChapterDetector.isEnding("Credits"));
        assertFalse(IntroChapterDetector.isEnding(null));
    }

    // ------------------------------------------------------------------ 片尾

    @Test
    public void detectsEndingNearFileEnd() {
        IntroSegment segment = IntroChapterDetector.detectEnding(
                of(0L, "Part A", DURATION - 90_000L, "ED"), DURATION);
        assertNotNull(segment);
        assertEquals(DURATION - 90_000L, segment.getStart());
        assertEquals(IntroSegment.Source.CHAPTER, segment.getSource());
    }

    @Test
    public void rejectsEndingTooFarFromFileEnd() {
        // 离结尾太远（超过上限）通常是标题写错，不是真的片尾。
        assertNull(IntroChapterDetector.detectEnding(
                of(0L, "ED"), DURATION));
    }

    @Test
    public void rejectsEndingAtTheVeryBeginning() {
        assertNull(IntroChapterDetector.detectEnding(
                of(0L, "ED", 30_000L, "ED2"), DURATION));
    }

    @Test
    public void rejectsEndingWithTooLittleLeft() {
        assertNull(IntroChapterDetector.detectEnding(
                of(DURATION - 2_000L, "ED"), DURATION));
    }

    @Test
    public void rejectsEndingBeyondFileLength() {
        assertNull(IntroChapterDetector.detectEnding(
                of(DURATION + 60_000L, "ED"), DURATION));
    }

    @Test
    public void endingIsRangedFromItsStart() {
        IntroSegment segment = IntroSegment.ending(100_000, IntroSegment.Source.CHAPTER, "ED");
        assertEquals(100_000, segment.getStart());
        assertEquals(100_000, segment.getEnd());
        // 片尾是「到了就走」，不靠区间包含判断，所以只有起点是有效信息。
        assertFalse(segment.isValid());
    }

    // ------------------------------------------------------------------ 区间语义

    @Test
    public void shouldSkipUsesContainmentNotEquality() {
        IntroSegment segment = IntroSegment.opening(10_000, 90_000, IntroSegment.Source.CHAPTER, "OP");
        // 进度上报很少正好落在起点上，等值判断会让自动跳过大多时候不触发。
        assertTrue(segment.shouldSkipAt(50_000));
        assertTrue(segment.shouldSkipAt(10_000));
        assertFalse(segment.shouldSkipAt(9_999));
    }

    @Test
    public void invalidRangeNeverSkips() {
        IntroSegment segment = IntroSegment.opening(90_000, 10_000, IntroSegment.Source.CHAPTER, "bad");
        assertFalse(segment.isValid());
        assertFalse(segment.shouldSkipAt(50_000));
    }
}
