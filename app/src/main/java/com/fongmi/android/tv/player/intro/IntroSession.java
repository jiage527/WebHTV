package com.fongmi.android.tv.player.intro;

import androidx.media3.common.MediaChapter;
import androidx.media3.common.MediaEdition;

import java.util.ArrayList;
import java.util.List;

/**
 * 一部剧在一次播放期间的片头片尾判定会话。
 *
 * <p>两套 UI（手机 / 电视）与两套播放引擎共用一个会话类，判定链只在
 * {@link IntroResolver} 里写一遍，{@link IntroSkipGuard} 里的防误跳也只写一遍；
 * 两个 Activity 只负责在合适的时机问它、执行跳转。
 *
 * <p><b>与既有手动记录的关系。</b>现有「点按记录片头 / 长按清除」的按钮与语义
 * 一行都不改。本类只是把用户手动记的值<b>额外</b>当样本喂进档案，让同剧其它集能受益。
 * 手动值原本存在 {@code History} 里、代表续播位置，本类不去读它，以免把续播点
 * 误当成片头区间。
 *
 * <p><b>为什么档案按剧存。</b>{@code History} 的 key 已经不含集号（集在 episodeUrl 里），
 * 与它粒度一致，用户看第 5 集时就能用上第 1 集学到的信息。
 */
public final class IntroSession {

    private final String seriesKey;
    private final IntroProfile profile;
    private final IntroSkipGuard guard = new IntroSkipGuard();
    private final List<IntroChapterPoint> chapters = new ArrayList<>();

    private IntroSegment opening;
    private IntroSegment ending;
    private long durationMs;

    private IntroSession(String seriesKey, IntroProfile profile) {
        this.seriesKey = seriesKey;
        this.profile = profile;
    }

    public static IntroSession create(String seriesKey) {
        return new IntroSession(seriesKey, IntroProfileStore.get(seriesKey));
    }

    public IntroProfile getProfile() {
        return profile;
    }

    public IntroSegment getOpening() {
        return opening;
    }

    public IntroSegment getEnding() {
        return ending;
    }

    /**
     * 章节信息到达时调用（两套引擎的章节都可能在起播后才到，也可能永远不到）。
     *
     * <p>只在这里重新判定、并把章节结果存成证据；跳转时机由 {@code shouldSkip*} 决定，
     * 因为它依赖「是否正在播放」这一只有在进度回调里才拿得到的实时状态。
     */
    public void onChapters(List<MediaChapter> chapterList, List<MediaEdition> editions) {
        chapters.clear();
        chapters.addAll(chapterList != null && !chapterList.isEmpty()
                ? IntroChapterAdapter.fromChapters(chapterList)
                : IntroChapterAdapter.fromEditions(editions));
        refresh();
        rememberChapterEvidence();
    }

    /**
     * 时长变化时调用。
     *
     * <p>判定需要总时长来判断「片尾离结尾有多远」，但章节可能在时长到手之前就到了；
     * 所以时长更新后要重判一次，不能只在章节到达时判。
     */
    public void onDuration(long durationMs) {
        if (durationMs <= 0 || durationMs == this.durationMs) return;
        this.durationMs = durationMs;
        refresh();
    }

    private void refresh() {
        opening = IntroResolver.opening(profile, chapters, durationMs);
        ending = IntroResolver.ending(profile, chapters, durationMs);
    }

    /**
     * 片源章节识别出的结果作为证据存下来，供同剧其它集使用。
     *
     * <p>只存章节（{@code CHAPTER}）来的值：学习结果本身是推断出来的，把它再当证据
     * 会形成自证循环，一次误判会被不断放大到整部剧。
     */
    private void rememberChapterEvidence() {
        long end = 0;
        long length = 0;
        long endingStart = 0;
        if (opening != null && opening.getSource() == IntroSegment.Source.CHAPTER && opening.isValid()) {
            end = opening.getEnd();
            length = opening.length();
        }
        if (ending != null && ending.getSource() == IntroSegment.Source.CHAPTER && ending.getStart() > 0) {
            endingStart = ending.getStart();
        }
        if (end == 0 && endingStart == 0) return;
        IntroProfileStore.record(seriesKey, end, length, endingStart);
    }

    /**
     * 用户手动记下片头结束点（沿用现有按钮的行为）。
     *
     * <p>现有按钮记的是「片头播完的时刻」，正好是跳转目标；但单个点推不出片头起点，
     * 所以这里只贡献 {@code openingEnds} 样本，长度样本留空。
     */
    public void recordManualOpeningEnd(long endMs) {
        if (endMs > 0) IntroProfileStore.record(seriesKey, endMs, 0, 0);
    }

    /** 用户手动记下片尾起点。 */
    public void recordManualEndingStart(long startMs) {
        if (startMs > 0) IntroProfileStore.record(seriesKey, 0, 0, startMs);
    }

    public boolean shouldSkipOpening(long positionMs, boolean playing) {
        return guard.shouldSkipOpening(opening, positionMs, playing);
    }

    public boolean shouldSkipEnding(long positionMs, boolean playing) {
        return guard.shouldSkipEnding(ending, positionMs, playing);
    }

    /** 用户手动重置后允许同一区间再跳一次。 */
    public void resetGuard() {
        guard.reset();
    }
}
