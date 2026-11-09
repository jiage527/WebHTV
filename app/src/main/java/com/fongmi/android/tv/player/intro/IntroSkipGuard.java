package com.fongmi.android.tv.player.intro;

/**
 * 决定「此刻该不该自动跳」。
 *
 * <p>纯逻辑、不依赖 Android。
 *
 * <p><b>为什么需要这一层。</b>自动跳过一旦误判，用户体验比不做还差：进度条会莫名
 * 跳到别处，而且用户不知道是为什么。所以这里集中三件防护：
 *
 * <ul>
 *   <li><b>只跳一次。</b>播放位置是持续上报的，同一段区间会被反复看到。不记住
 *       「这段已跳过」，就会在用户手动拖回片头时又被弹走。</li>
 *   <li><b>只在播放中跳。</b>暂停时拖动（预览、找画面）不触发跳转，否则拖动
 *       刚停在片头范围内就被强行弹开，根本没法定位。</li>
 *   <li><b>只往前跳、且要真的能前进。</b>片头已在身后（用户从中段续看）或目标
 *       位置比现在更前时不跳，否则会把人往回拽。</li>
 * </ul>
 */
public final class IntroSkipGuard {

    private long skippedOpeningKey = Long.MIN_VALUE;
    private long skippedEndingKey = Long.MIN_VALUE;

    /** 用户手动重置后，允许在同一区间再跳一次。 */
    public void reset() {
        skippedOpeningKey = Long.MIN_VALUE;
        skippedEndingKey = Long.MIN_VALUE;
    }

    /**
     * 是否应跳过片头。
     *
     * @param opening  当前认定的片头区间，可为 null
     * @param position 当前播放位置（毫秒）
     * @param playing  是否正在播放
     */
    public boolean shouldSkipOpening(IntroSegment opening, long position, boolean playing) {
        if (opening == null || !opening.isValid()) return false;
        // 暂停时不跳：用户可能正在片头里找画面。
        if (!playing) return false;
        if (!opening.shouldSkipAt(position)) return false;
        long key = key(opening);
        if (key == skippedOpeningKey) return false;
        skippedOpeningKey = key;
        return true;
    }

    /** 是否应跳到下一集（片尾判定）。 */
    public boolean shouldSkipEnding(IntroSegment ending, long position, boolean playing) {
        if (ending == null || ending.getStart() <= 0) return false;
        if (!playing) return false;
        // 片尾是「到了就走」，用位置到达起点来判断，而不是区间包含。
        if (position < ending.getStart()) return false;
        long key = key(ending);
        if (key == skippedEndingKey) return false;
        skippedEndingKey = key;
        return true;
    }

    private static long key(IntroSegment segment) {
        return segment.getStart() * 31L + segment.getEnd();
    }
}
