package com.fongmi.android.tv.player.intro;

import java.util.Locale;

/**
 * 一段片头或片尾区间，以及它是由什么证据得到的。
 *
 * <p>纯逻辑、不依赖 Android，便于单测。单位统一为毫秒，与播放器一致。
 */
public final class IntroSegment {

    /**
     * 这段区间的证据来源。
     *
     * <p>只用于追溯与日志：判定链里「谁优先」由 {@code IntroResolver} 明确写死，
     * 不靠这里的枚举顺序推导。
     */
    public enum Source {
        /** 用户在播放器里手动记录后喂进来的样本。 */
        MANUAL,
        /** 从容器章节标记里读出来的。 */
        CHAPTER,
        /** 同剧其它剧集学到的固定时长。 */
        LEARNED
    }

    private final long start;
    private final long end;
    private final Source source;
    private final String label;

    private IntroSegment(long start, long end, Source source, String label) {
        this.start = start;
        this.end = end;
        this.source = source;
        this.label = label == null ? "" : label;
    }

    public static IntroSegment of(long start, long end, Source source, String label) {
        return new IntroSegment(Math.max(0, start), Math.max(0, end), source, label);
    }

    /** 片头：从 {@code start} 播到 {@code end} 就跳走。 */
    public static IntroSegment opening(long start, long end, Source source, String label) {
        return of(start, end, source, label);
    }

    /** 片尾：从 {@code start} 起算作已到结尾。 */
    public static IntroSegment ending(long start, Source source, String label) {
        return of(start, start, source, label);
    }

    public long getStart() {
        return start;
    }

    public long getEnd() {
        return end;
    }

    public Source getSource() {
        return source;
    }

    public String getLabel() {
        return label;
    }

    /** 区间是否有意义：终点必须严格晚于起点，否则跳转无效果。 */
    public boolean isValid() {
        return end > start;
    }

    public long length() {
        return end - start;
    }

    /** {@code position} 是否落在这段区间内（左闭右开）。 */
    public boolean contains(long position) {
        return position >= start && position < end;
    }

    /**
     * 该不该在这个位置跳转。
     *
     * <p>刻意用「已经进到区间里」而不是「等于起点」来判断：播放位置是按固定间隔
     * 上报的，很少正好落在起点上，等值判断会让自动跳过大部分时候不触发。
     */
    public boolean shouldSkipAt(long position) {
        return isValid() && contains(position);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s[%d-%d](%s)", source, start, end, label);
    }
}
