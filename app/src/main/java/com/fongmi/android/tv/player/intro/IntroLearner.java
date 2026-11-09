package com.fongmi.android.tv.player.intro;

import java.util.List;

/**
 * 同一部剧里片头位置基本固定，据此在章节缺失时推断。
 *
 * <p>纯逻辑、不依赖 Android。
 *
 * <p><b>典型场景。</b>一部剧只有第 1 集带章节标记，后面几集都没有。第 1 集识别出
 * 片头区间后成为样本，后续各集据中位数推断，不必每集手动记一次——这正是报表 A-09
 * 写的「同一部剧学到的片头区间自动套用到后续集」。
 *
 * <p><b>长宽都要有依据才给结果。</b>只知道片头在哪里结束、不知道它多长，就无法知道
 * 该从什么时候开始判定「进片头了」；硬用一个默认值去猜，等于把用户扔进一次可能很长的
 * 误跳过里。所以推断不出长度时宁可不做，让播放行为保持原样。
 */
public final class IntroLearner {

    private IntroLearner() {
    }

    /**
     * 推断片头区间。
     *
     * @param openingEnds    其它集的片头结束点样本（毫秒）
     * @param openingLengths 其它集的片头长度样本（毫秒）
     * @return 推断出的片头区间；样本不足或不一致时返回 {@code null}
     */
    public static IntroSegment inferOpening(List<Long> openingEnds, List<Long> openingLengths) {
        Long end = IntroStats.consensus(openingEnds, IntroStats.MIN_SAMPLES);
        if (end == null || end <= 0) return null;
        Long length = IntroStats.consensus(openingLengths, IntroStats.MIN_SAMPLES);
        if (length == null || length < IntroChapterDetector.MIN_LENGTH_MS) return null;
        long start = Math.max(0, end - length);
        // 推出来的区间仍然要过与手动记录同一套长度校验：学习结果不该产生手动
        // 记不出来、用户也没法改的区间。
        if (length > IntroChapterDetector.limitOf(end)) return null;
        return IntroSegment.opening(start, end, IntroSegment.Source.LEARNED, "learned");
    }

    /**
     * 推断片尾起点。
     *
     * @param endingStarts 其它集的片尾起点样本（毫秒）
     */
    public static IntroSegment inferEnding(List<Long> endingStarts) {
        Long start = IntroStats.consensus(endingStarts, IntroStats.MIN_SAMPLES);
        if (start == null || start <= 0) return null;
        return IntroSegment.ending(start, IntroSegment.Source.LEARNED, "learned");
    }
}
