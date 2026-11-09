package com.fongmi.android.tv.player.intro;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 中位数统计工具。
 *
 * <p>纯逻辑、不依赖 Android。
 *
 * <p><b>为什么全用中位数而不是平均值。</b>样本来自用户的真实操作与片源的章节标记，
 * 两者都会混进离群值（随手记错、压制组写错的章节）。平均值会被单个离群值拉走，
 * 中位数不会。样本量往往只有三五条，这一点尤其关键。
 */
final class IntroStats {

    /** 判定「同一个位置」的容差：各集片头位置通常有几秒浮动。 */
    static final long TOLERANCE_MS = 15_000;

    /** 至少要这么多样本才敢用，两条可能是巧合。 */
    static final int MIN_SAMPLES = 3;

    private IntroStats() {
    }

    static List<Long> valid(List<Long> samples) {
        if (samples == null) return Collections.emptyList();
        List<Long> result = new ArrayList<>();
        for (Long sample : samples) if (sample != null && sample > 0) result.add(sample);
        return result;
    }

    /**
     * 取中位数；样本不足或彼此不一致时返回 {@code null}。
     *
     * <p>要求过半样本落在中位数附近：位置本来就不固定时（或误记太多），任何「代表值」
     * 都是编出来的，此时宁可不用。
     */
    static Long consensus(List<Long> samples, int minSamples) {
        List<Long> valid = valid(samples);
        if (valid.size() < minSamples) return null;
        long median = median(valid);
        int near = 0;
        for (Long sample : valid) if (Math.abs(sample - median) <= TOLERANCE_MS) near++;
        if (near * 2 <= valid.size()) return null;
        return median;
    }

    static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int size = sorted.size();
        if (size % 2 == 1) return sorted.get(size / 2);
        return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2;
    }
}
