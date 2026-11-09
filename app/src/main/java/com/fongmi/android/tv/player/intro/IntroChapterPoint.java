package com.fongmi.android.tv.player.intro;

/**
 * 容器章节标记里的一条：起始时间 + 标题。
 *
 * <p>把两套引擎的章节类型收敛成这一个结构，是因为 Exo 给的是 {@code MediaChapter}、
 * mpv 给的是 {@code MediaEdition}（该实现把章节列表装进了 edition），字段含义还不一样。
 * 检测逻辑不该为这个差异写出两份。转换放在 {@link IntroChapterAdapter}，这样本类
 * 保持纯逻辑、可以直接单测。
 */
public final class IntroChapterPoint {

    private final long timeMs;
    private final String label;

    public IntroChapterPoint(long timeMs, String label) {
        this.timeMs = Math.max(0, timeMs);
        this.label = label == null ? "" : label;
    }

    public long getTimeMs() {
        return timeMs;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return timeMs + "ms:" + label;
    }
}
