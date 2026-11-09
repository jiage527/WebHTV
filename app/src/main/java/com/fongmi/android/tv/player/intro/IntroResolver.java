package com.fongmi.android.tv.player.intro;

import java.util.List;

/**
 * 把「章节识别」与「同剧学习」合成一条判定链。
 *
 * <p>纯逻辑、不依赖 Android。两套 UI、两套播放引擎都只跟这一个类打交道，
 * 判定顺序只写一遍。
 *
 * <p>判定顺序与理由：
 * <ol>
 *   <li><b>容器章节</b>：片源自带的事实，比推断可靠。</li>
 *   <li><b>同剧学习</b>：没有章节标记时才用其它集推出来的值。</li>
 * </ol>
 *
 * <p><b>手动记录为什么不在链里。</b>现有手动记录写入的是 {@code History} 的
 * 「续播位置」语义（下次从这里开始播），而不是「跳过某段」的区间语义，两者不是同一件
 * 事。本类刻意不读它，以免把续播点当成片头区间、反而让播放位置变得莫名其妙。
 * 手动记录的价值体现在另一处：它会被当作样本喂进档案（见 {@code IntroSession}），
 * 用来提升同剧推断的准确度。
 *
 * <p>关闭总开关时一律返回 {@code null}，即完全不自动跳转，行为与旧版本一致。
 */
public final class IntroResolver {

    private IntroResolver() {
    }

    /** 本集应当采用的片头区间；没有任何依据时返回 {@code null}。 */
    public static IntroSegment opening(IntroProfile profile, List<IntroChapterPoint> chapters, long durationMs) {
        if (!IntroSetting.isEnabled()) return null;
        if (IntroSetting.useChapter()) {
            IntroSegment detected = IntroChapterDetector.detectOpening(chapters, durationMs);
            if (detected != null) return detected;
        }
        if (!IntroSetting.useLearned() || profile == null) return null;
        return IntroLearner.inferOpening(profile.getOpeningEnds(), profile.getOpeningLengths());
    }

    /** 本集应当采用的片尾区间；没有任何依据时返回 {@code null}。 */
    public static IntroSegment ending(IntroProfile profile, List<IntroChapterPoint> chapters, long durationMs) {
        if (!IntroSetting.isEnabled()) return null;
        if (IntroSetting.useChapter()) {
            IntroSegment detected = IntroChapterDetector.detectEnding(chapters, durationMs);
            if (detected != null) return detected;
        }
        if (!IntroSetting.useLearned() || profile == null) return null;
        return IntroLearner.inferEnding(profile.getEndingStarts());
    }
}
