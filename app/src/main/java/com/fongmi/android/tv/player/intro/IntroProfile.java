package com.fongmi.android.tv.player.intro;

import java.util.ArrayList;
import java.util.List;

/**
 * 一部剧的片头片尾档案。
 *
 * <p>纯逻辑、不依赖 Android，序列化成 JSON 落盘（见 {@code IntroProfileStore}）。
 *
 * <p><b>为什么按「剧」而不是按「集」存。</b>同一部剧每集的片头位置几乎一样，逐集存
 * 会让用户看第 5 集时用不上第 1 集学到的信息，等于还是每集手动记一次。现有
 * {@code History} 的 key 本身就不含集号（集在 episodeUrl 里），与它粒度一致。
 *
 * <p><b>样本的含义。</b>与现有手动记录保持一致——手动按钮记下的是<b>跳转目标</b>
 * （片头的结束点、片尾的起始点），而不是整段区间。所以这里学的是「跳到哪里」与
 * 「回到多久之前算在片头里」，两者分开存：
 *
 * <ul>
 *   <li>{@code openingEnds}：片头何时结束（跳转目标）。手动按钮与章节标记都能贡献。</li>
 *   <li>{@code openingLengths}：片头有多长（决定「从哪里开始算在片头内」）。
 *       只有章节标记能贡献——手动按钮只给一个点，推不出起点。</li>
 *   <li>{@code endingStarts}：片尾从何时开始（到了就跳下一集）。</li>
 * </ul>
 *
 * <p><b>为什么留样本而不是只留一个值。</b>只留一个值就判断不出它可不可信。留样本
 * 之后才能用中位数加一致性门槛（见 {@link IntroStats}）决定该不该套用到别的集上。
 */
public final class IntroProfile {

    /** 样本上限：防止长期使用后档案无限膨胀，只保留最近的。 */
    private static final int MAX_SAMPLES = 30;

    private List<Long> openingEnds = new ArrayList<>();
    private List<Long> openingLengths = new ArrayList<>();
    private List<Long> endingStarts = new ArrayList<>();

    /** 供 Gson 反序列化使用。 */
    public IntroProfile() {
    }

    /**
     * 反序列化后把可能为 null 的集合补回空列表。
     *
     * <p>Gson 直接写字段，JSON 里写成 {@code null}（档案被手改过、或由旧版本写入）
     * 会把集合置空，之后任何遍历都会 NPE。所以每个读入口都先调这里。
     * 字段因此刻意不声明为 final。
     */
    public void normalize() {
        if (openingEnds == null) openingEnds = new ArrayList<>();
        if (openingLengths == null) openingLengths = new ArrayList<>();
        if (endingStarts == null) endingStarts = new ArrayList<>();
    }

    public List<Long> getOpeningEnds() {
        normalize();
        return openingEnds;
    }

    public List<Long> getOpeningLengths() {
        normalize();
        return openingLengths;
    }

    public List<Long> getEndingStarts() {
        normalize();
        return endingStarts;
    }

    /**
     * 记入一次观察。
     *
     * @param openingEnd 片头结束点（跳转目标），0 表示没有
     * @param openingLength 片头长度，0 表示没有
     * @param endingStart 片尾起始点，0 表示没有
     */
    public void record(long openingEnd, long openingLength, long endingStart) {
        normalize();
        if (openingEnd > 0) addSample(openingEnds, openingEnd);
        // 太短的「片头」通常是误记或章节写错，收了会污染后续判定。
        if (openingLength >= IntroChapterDetector.MIN_LENGTH_MS) addSample(openingLengths, openingLength);
        if (endingStart > 0) addSample(endingStarts, endingStart);
    }

    /** 档案里是否一条证据都没有。全空的档案不必落盘。 */
    public boolean isEmpty() {
        normalize();
        return openingEnds.isEmpty() && openingLengths.isEmpty() && endingStarts.isEmpty();
    }

    /**
     * 只用于单测：把集合置空，模拟「JSON 里写了 null」这种反序列化结果。
     *
     * <p>生产代码没有任何路径会调用它；Gson 直接写字段才能产生这种状态。
     */
    void setOpeningEndsForTest(List<Long> value) {
        this.openingEnds = value;
    }

    void setOpeningLengthsForTest(List<Long> value) {
        this.openingLengths = value;
    }

    void setEndingStartsForTest(List<Long> value) {
        this.endingStarts = value;
    }

    /**
     * 同一个值反复记（用户来回微调、同一部剧多集结构一致）不该把样本撑满。
     * 去重在这里做，而不是在统计时做，是为了让「样本数」如实反映见过多少次不同位置。
     */
    private static void addSample(List<Long> samples, long value) {
        if (samples.contains(value)) return;
        samples.add(value);
        while (samples.size() > MAX_SAMPLES) samples.remove(0);
    }
}
