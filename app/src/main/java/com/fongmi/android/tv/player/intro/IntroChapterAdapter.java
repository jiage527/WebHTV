package com.fongmi.android.tv.player.intro;

import androidx.media3.common.C;
import androidx.media3.common.MediaChapter;
import androidx.media3.common.MediaEdition;

import java.util.ArrayList;
import java.util.List;

/**
 * 把两套播放引擎各自的章节类型转成 {@link IntroChapterPoint}。
 *
 * <p>两套引擎的单位与字段并不一致，这里一并说明清楚，避免以后有人按一种引擎的
 * 语义去读另一种：
 *
 * <ul>
 *   <li>Exo 的 {@link MediaChapter}：{@code timeUs} 是章节起点，{@code label} 是标题。</li>
 *   <li>mpv 的 {@link MediaEdition}：该实现把章节列表装进了 edition，所以
 *       {@code durationUs} 承载的是<b>章节起点</b>，{@code label} 是标题。
 *       {@code index} 是章节序号，不是时间。</li>
 * </ul>
 */
public final class IntroChapterAdapter {

    private IntroChapterAdapter() {
    }

    public static List<IntroChapterPoint> fromChapters(List<MediaChapter> chapters) {
        List<IntroChapterPoint> points = new ArrayList<>();
        if (chapters == null) return points;
        for (MediaChapter chapter : chapters) {
            if (chapter == null) continue;
            // TIME_UNSET 表示这条章节没有可用时间，丢掉它而不是当成 0，
            // 否则会在文件开头凭空冒出一个「片头」。
            if (chapter.timeUs == C.TIME_UNSET) continue;
            points.add(new IntroChapterPoint(chapter.timeUs / 1000L, chapter.label));
        }
        return points;
    }

    public static List<IntroChapterPoint> fromEditions(List<MediaEdition> editions) {
        List<IntroChapterPoint> points = new ArrayList<>();
        if (editions == null) return points;
        for (MediaEdition edition : editions) {
            if (edition == null) continue;
            if (edition.durationUs == C.TIME_UNSET) continue;
            points.add(new IntroChapterPoint(edition.durationUs / 1000L, edition.label));
        }
        return points;
    }
}
