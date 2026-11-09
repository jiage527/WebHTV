package com.fongmi.android.tv.player.intro;

import java.util.List;
import java.util.Locale;

/**
 * 从容器的章节标记里认出片头与片尾。
 *
 * <p>纯逻辑、不依赖 Android，也不依赖任何播放引擎。
 *
 * <p><b>为什么先做章节、而不是音频指纹。</b>章节标记是片源里已经写好的事实，
 * 读它不需要解码音频、不需要额外模型，也不会在低端设备上抢资源；指纹方案要跑
 * chromaprint 并对全片取样，成本与风险都高一个数量级（见报表 A-09 的「低成本起步」）。
 * 章节认不出来时由 {@link IntroLearner} 用同剧其它集学到的偏移兜底。
 *
 * <p><b>为什么必须限定长度。</b>章节标记是用户或压制组随手写的，标题里出现
 * 「OP」不代表这一段就是片头。这里沿用与手动记录完全相同的边界
 * （{@link #limitOf(long)}），这样「自动认出来的」和「手动记的」在接受范围上一致，
 * 不会出现自动跳转跳到用户根本没法手动记录的位置。
 */
public final class IntroChapterDetector {

    /** 标题里出现这些词就当作片头起始章节；匹配的是整词，不是子串。 */
    private static final String[] OPENING_TOKENS = {"op", "opening", "intro", "オープニング"};
    private static final String[] ENDING_TOKENS = {"ed", "ending", "outro", "エンディング"};

    /** 中文没有词边界，只能按包含判断。 */
    private static final String[] OPENING_CJK = {"片头", "片頭", "主題曲", "主题曲", "オープニング"};
    private static final String[] ENDING_CJK = {"片尾", "片尾曲", "エンディング"};

    /** 短于这个长度的区间不值得跳，跳了也是闪一下。 */
    public static final long MIN_LENGTH_MS = 5_000;

    private IntroChapterDetector() {
    }

    /**
     * 与手动记录一致的片头/片尾允许范围。
     *
     * <p>与 ProGuard 无关，这是产品规则：太长的「片头」通常是识别错了。
     * 这里刻意复用同一套阈值（见 {@code Constant.getOpEdLimit}）而不是另立一套，
     * 否则自动识别能产生手动记录无法接受的值，用户改都改不动。
     */
    public static long limitOf(long durationMs) {
        if (durationMs < 15 * 60_000L) return 3 * 60_000L;
        if (durationMs < 30 * 60_000L) return 6 * 60_000L;
        return 10 * 60_000L;
    }

    /** 找出片头区间；没有可信的章节时返回 {@code null}。 */
    public static IntroSegment detectOpening(List<IntroChapterPoint> points, long durationMs) {
        if (points == null || points.size() < 2 || durationMs <= 0) return null;
        long limit = limitOf(durationMs);
        for (int i = 0; i < points.size(); i++) {
            IntroChapterPoint point = points.get(i);
            if (!isOpening(point.getLabel())) continue;
            // 有起点还得有下一个章节才能知道片头何时结束；片头是最后一个章节时放弃，
            // 因为猜终点等于编数据。
            if (i + 1 >= points.size()) return null;
            long start = point.getTimeMs();
            long end = points.get(i + 1).getTimeMs();
            if (start > limit) return null;
            long length = end - start;
            if (length < MIN_LENGTH_MS) return null;
            if (length > limit) return null;
            return IntroSegment.opening(start, end, IntroSegment.Source.CHAPTER, point.getLabel());
        }
        return null;
    }

    /** 找出片尾起点；没有可信的章节时返回 {@code null}。 */
    public static IntroSegment detectEnding(List<IntroChapterPoint> points, long durationMs) {
        if (points == null || points.isEmpty() || durationMs <= 0) return null;
        long limit = limitOf(durationMs);
        for (IntroChapterPoint point : points) {
            if (!isEnding(point.getLabel())) continue;
            long start = point.getTimeMs();
            if (start <= 0 || start >= durationMs) continue;
            long remaining = durationMs - start;
            if (remaining < MIN_LENGTH_MS) return null;
            if (remaining > limit) return null;
            return IntroSegment.ending(start, IntroSegment.Source.CHAPTER, point.getLabel());
        }
        return null;
    }

    /** 标题是否表示片头起始章节。 */
    public static boolean isOpening(String label) {
        return matches(label, OPENING_TOKENS, OPENING_CJK);
    }

    /** 标题是否表示片尾起始章节。 */
    public static boolean isEnding(String label) {
        return matches(label, ENDING_TOKENS, ENDING_CJK);
    }

    private static boolean matches(String label, String[] tokens, String[] cjk) {
        if (label == null || label.isEmpty()) return false;
        String text = label.trim().toLowerCase(Locale.ROOT);
        for (String keyword : cjk) if (text.contains(keyword.toLowerCase(Locale.ROOT))) return true;
        String token = leadingToken(text);
        if (token.isEmpty()) return false;
        for (String candidate : tokens) if (candidate.equals(token)) return true;
        return false;
    }

    /**
     * 取标题开头的第一个字母串。
     *
     * <p>必须取「整段」而不是做子串包含：{@code ED} 只有两个字母，一旦按子串匹配，
     * {@code "Edited by x"}、{@code "Reduced"} 这类章节名都会被误认成片尾。
     * 取头部整词后，{@code "OP1"} 会得到 {@code "op"}（命中），而
     * {@code "Edited"} 得到 {@code "edited"}（不命中）。
     */
    private static String leadingToken(String text) {
        int index = 0;
        while (index < text.length() && !isAsciiLetter(text.charAt(index))) index++;
        int start = index;
        while (index < text.length() && isAsciiLetter(text.charAt(index))) index++;
        return text.substring(start, index);
    }

    private static boolean isAsciiLetter(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
    }
}
