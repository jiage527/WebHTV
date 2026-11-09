package com.fongmi.android.tv.player.intro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * {@link IntroLearner} 与 {@link IntroStats} 的推断规则。
 *
 * <p>这些用例盯的是「样本不可信时宁可什么都不做」：推断出的区间会被套用到整部剧的
 * 每一集，一个错误的中位数意味着一部剧全部跳错位置。
 */
public class IntroLearnerTest {

    private static final long OP_END = 95_000L;
    private static final long OP_LEN = 90_000L;

    // ------------------------------------------------------------------ 片头推断

    @Test
    public void infersOpeningFromConsistentSamples() {
        IntroSegment segment = IntroLearner.inferOpening(
                List.of(95_000L, 96_000L, 94_000L),
                List.of(90_000L, 91_000L, 89_000L));
        assertEquals(95_000L, segment.getEnd());
        assertEquals(5_000L, segment.getStart());
        assertEquals(IntroSegment.Source.LEARNED, segment.getSource());
    }

    @Test
    public void medianResistsOutlier() {
        // 三个正常样本 + 一个明显写错的样本，中位数不该被拉走。
        IntroSegment segment = IntroLearner.inferOpening(
                List.of(95_000L, 96_000L, 94_000L, 10 * 60_000L),
                List.of(90_000L, 91_000L, 89_000L));
        assertEquals(95_500L, segment.getEnd());
    }

    @Test
    public void rejectsTooFewSamples() {
        assertNull(IntroLearner.inferOpening(List.of(95_000L, 95_000L), List.of(OP_LEN, OP_LEN)));
        assertNull(IntroLearner.inferOpening(null, null));
    }

    @Test
    public void rejectsInconsistentPositions() {
        // 三个样本彼此相差远超容差，说明这部剧片头位置本来就不固定。
        assertNull(IntroLearner.inferOpening(
                List.of(30_000L, 10 * 60_000L, 5 * 60_000L),
                List.of(OP_LEN, OP_LEN, OP_LEN)));
    }

    @Test
    public void rejectsWhenLengthUnknownButPositionKnown() {
        // 只知道片头在哪结束、不知道它多长：无法判断该从何时开始算进片头里，
        // 硬猜等于把用户扔进一次可能很长的误跳过。
        assertNull(IntroLearner.inferOpening(
                List.of(95_000L, 96_000L, 94_000L), List.of()));
    }

    @Test
    public void rejectsWhenLengthInconsistent() {
        assertNull(IntroLearner.inferOpening(
                List.of(OP_END, OP_END, OP_END),
                List.of(10_000L, 120_000L, 240_000L)));
    }

    @Test
    public void rejectsTinyLengths() {
        assertNull(IntroLearner.inferOpening(
                List.of(OP_END, OP_END, OP_END),
                List.of(1_000L, 1_000L, 1_000L)));
    }

    @Test
    public void inferredRangeStillHonoursProductLimit() {
        // 学出来的区间也要过与手动记录同一套长度校验，否则会产出用户改不动的值。
        assertNull(IntroLearner.inferOpening(
                List.of(OP_END, OP_END, OP_END),
                List.of(9 * 60_000L, 9 * 60_000L, 9 * 60_000L)));
    }

    @Test
    public void startNeverGoesNegative() {
        IntroSegment segment = IntroLearner.inferOpening(
                List.of(20_000L, 21_000L, 19_000L),
                List.of(20_000L, 20_000L, 20_000L));
        assertTrue(segment.getStart() >= 0);
    }

    // ------------------------------------------------------------------ 片尾推断

    @Test
    public void infersEndingFromConsistentSamples() {
        long near = 24 * 60_000L - 90_000L;
        IntroSegment segment = IntroLearner.inferEnding(List.of(near, near + 1_000L, near - 1_000L));
        assertEquals(near, segment.getStart());
        assertEquals(IntroSegment.Source.LEARNED, segment.getSource());
    }

    @Test
    public void rejectsInconsistentEndings() {
        assertNull(IntroLearner.inferEnding(List.of(60_000L, 10 * 60_000L, 20 * 60_000L)));
    }

    @Test
    public void rejectsEndingWithoutSamples() {
        assertNull(IntroLearner.inferEnding(List.of()));
        assertNull(IntroLearner.inferEnding(null));
    }

    // ------------------------------------------------------------------ 统计

    @Test
    public void medianOfOddCount() {
        assertEquals(3L, IntroStats.median(List.of(1L, 3L, 5L)));
    }

    @Test
    public void medianOfEvenCount() {
        // 偶数个样本取中间两个的均值；样本少时必须让中间那对都参与，取错一侧会偏。
        assertEquals(4L, IntroStats.median(List.of(1L, 3L, 5L, 7L)));
    }

    @Test
    public void consensusRequiresOverHalfNearMedian() {
        // 5 个样本里 2 个靠近中位数，不到一半，应拒绝。
        assertNull(IntroStats.consensus(List.of(0L, 0L, 100_000L, 200_000L, 300_000L), 3));
    }

    @Test
    public void validFiltersZeroAndNull() {
        // 0 表示「没有这条证据」，不能当成一个真实样本（否则会推出 0 处的片头）。
        assertEquals(2, IntroStats.valid(java.util.Arrays.asList(0L, null, 10L, 20L)).size());
        assertEquals(0, IntroStats.valid(null).size());
    }
}
