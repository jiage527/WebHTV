package com.fongmi.android.tv.setting;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 通用播放队列的纯逻辑模型：条目、循环模式、排序与导航。
 *
 * <p>刻意不依赖 Android 界面类型与播放器，因此队列的排序、插队、循环回绕这些最容易出错的
 * 规则都能在 JVM 单测里直接验证；播放与界面只负责调用它。
 *
 * <p>队列按 URL 去重：同一集被加两次没有意义，而且去重后「当前项」的下标不会被重复项
 * 弄乱。损坏的 JSON 一律退化为空队列，绝不让队列数据拖垮播放。
 */
public final class PlayQueue {

    /** 用独立的 Gson 实例：队列是纯数据，不依赖应用上下文，因此可在单测里直接读写。 */
    private static final Gson GSON = new GsonBuilder().create();

    public static final String MODE_OFF = "off";
    public static final String MODE_ALL = "all";
    public static final String MODE_ONE = "one";

    /** 队列长度上限。持久化在首选项里，过长的队列既没必要也会拖慢读写。 */
    public static final int MAX_ENTRIES = 200;
    private static final int MAX_NAME = 80;
    private static final int MAX_URL = 2048;

    @SerializedName("entries")    private List<Entry> entries = new ArrayList<>();
    @SerializedName("mode")
    private String mode = MODE_OFF;
    /** 当前播放项在队列中的下标；-1 表示尚未定位（例如刚导入队列）。 */
    @SerializedName("current")
    private int current = -1;

    public static PlayQueue empty() {
        return new PlayQueue();
    }

    /** 由当前剧集列表建立队列，用于「把这一整季加入队列」。 */
    public static PlayQueue of(List<Entry> source) {
        PlayQueue queue = new PlayQueue();
        for (Entry entry : source) queue.add(entry);
        return queue;
    }

    /**
     * 从 JSON 恢复。任何解析问题都退化为空队列。
     *
     * <p>连同循环模式与当前下标一起存，否则重启后会丢掉用户的循环选择，
     * 而且「当前」会退回到未知、下一集又从队首开始。
     */
    public static PlayQueue fromJson(@Nullable String json) {
        PlayQueue queue = new PlayQueue();
        if (isBlank(json)) return queue;
        try {
            PlayQueue parsed = GSON.fromJson(json, PlayQueue.class);
            if (parsed == null) return queue;
            for (Entry entry : parsed.rawEntries()) queue.add(entry);
            queue.setMode(parsed.mode());
            // 下标按去重后的实际长度重新校验，避免存档与当前内容不一致时越界。
            queue.setCurrentIndex(parsed.currentIndex());
        } catch (Exception ignored) {
            return new PlayQueue();
        }
        return queue;
    }

    public String toJson() {
        PlayQueue snapshot = new PlayQueue();
        snapshot.entries = new ArrayList<>(entries);
        snapshot.mode = mode();
        snapshot.current = currentIndex();
        return GSON.toJson(snapshot);
    }

    /** 反序列化用的原始列表；可能为 null 或含非法条目。 */
    private List<Entry> rawEntries() {
        return entries == null ? new ArrayList<>() : entries;
    }

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public String mode() {
        return normalizeMode(mode);
    }

    public void setMode(@Nullable String mode) {
        this.mode = normalizeMode(mode);
    }

    /** 循环模式取值必须是三者之一，其余一律按「不循环」处理。 */
    public static String normalizeMode(@Nullable String value) {
        if (MODE_ALL.equals(value)) return MODE_ALL;
        if (MODE_ONE.equals(value)) return MODE_ONE;
        return MODE_OFF;
    }

    /** 循环模式在按钮上显示时用得到的短名。 */
    public static String describeMode(@Nullable String value) {
        return switch (normalizeMode(value)) {
            case MODE_ALL -> "ALL";
            case MODE_ONE -> "ONE";
            default -> "OFF";
        };
    }

    /** 下一个循环模式，供按钮点击时轮换。 */
    public static String cycleMode(@Nullable String value) {
        return switch (normalizeMode(value)) {
            case MODE_OFF -> MODE_ALL;
            case MODE_ALL -> MODE_ONE;
            default -> MODE_OFF;
        };
    }

    public int currentIndex() {
        return current >= 0 && current < entries.size() ? current : -1;
    }

    public void setCurrentIndex(int index) {
        this.current = index >= 0 && index < entries.size() ? index : -1;
    }

    /** 按 URL 定位当前项；找不到时保持未知（-1）而不是错误地指向第一项。 */
    public void setCurrent(@Nullable String url) {
        this.current = indexOf(url);
    }

    @Nullable
    public Entry currentEntry() {
        return entryAt(currentIndex());
    }

    @Nullable
    public Entry entryAt(int index) {
        return index >= 0 && index < entries.size() ? entries.get(index) : null;
    }

    public int indexOf(@Nullable String url) {
        if (isBlank(url)) return -1;
        for (int i = 0; i < entries.size(); i++) {
            if (url.equals(entries.get(i).url)) return i;
        }
        return -1;
    }

    /** 该地址是否已在队列中。 */
    public boolean contains(@Nullable String url) {
        return indexOf(url) >= 0;
    }

    /** 追加到队尾；URL 重复或非法则忽略。 */
    public boolean add(@Nullable Entry entry) {
        Entry normalized = normalize(entry);
        if (normalized == null) return false;
        if (indexOf(normalized.url) >= 0) return false;
        if (entries.size() >= MAX_ENTRIES) return false;
        entries.add(normalized);
        return true;
    }

    /**
     * 插队：把条目放到当前项之后，成为下一个要播的内容。
     *
     * <p>当前项未知时退化为追加到队首 —— 此时「下一个」就是第一个。
     */
    public boolean insertNext(@Nullable Entry entry) {
        Entry normalized = normalize(entry);
        if (normalized == null) return false;
        int existing = indexOf(normalized.url);
        if (existing >= 0) {
            // 已在队列里：把它移动过来，而不是留下第二个同样的条目。
            int target = currentIndex() < 0 ? 0 : currentIndex() + 1;
            // 先移除会让目标下标前移，因此按移除后的位置重新计算。
            entries.remove(existing);
            int adjusted = target > existing ? target - 1 : target;
            entries.add(Math.min(adjusted, entries.size()), normalized);
            return true;
        }
        if (entries.size() >= MAX_ENTRIES) return false;
        entries.add(currentIndex() < 0 ? 0 : currentIndex() + 1, normalized);
        return true;
    }

    /** 拖拽排序。返回是否真的发生了移动。 */
    public boolean move(int from, int to) {
        if (from < 0 || from >= entries.size()) return false;
        if (to < 0 || to >= entries.size()) return false;
        if (from == to) return false;
        Entry entry = entries.remove(from);
        entries.add(to, entry);
        // 当前项跟着它自己的条目走，否则排序后「当前」会指向别的集。
        if (current == from) current = to;
        else if (from < current && to >= current) current--;
        else if (from > current && to <= current) current++;
        return true;
    }

    public boolean remove(int index) {
        if (index < 0 || index >= entries.size()) return false;
        entries.remove(index);
        if (index < current) current--;
        else if (index == current) current = currentIndexAfterRemoval(index);
        return true;
    }

    /** 删掉当前项后，让「当前」落在原本紧随其后的那一项上；没有则收尾为未知。 */
    private int currentIndexAfterRemoval(int removedIndex) {
        if (entries.isEmpty()) return -1;
        return removedIndex < entries.size() ? removedIndex : -1;
    }

    public boolean removeByUrl(@Nullable String url) {
        return remove(indexOf(url));
    }

    public void clear() {
        entries.clear();
        current = -1;
    }

    /**
     * 下一项的下标。
     *
     * <p>三种循环模式的语义：
     * <ul>
     *   <li>{@link #MODE_ONE}：始终停在当前项，即单集重复。</li>
     *   <li>{@link #MODE_ALL}：走到队尾再回到队首。</li>
     *   <li>{@link #MODE_OFF}：走到队尾就结束，返回 -1 表示没有下一项。</li>
     * </ul>
     * 当前项未知时，从队首开始播，避免「导入队列后按下一集没反应」。
     */
    public int nextIndex() {
        if (entries.isEmpty()) return -1;
        if (MODE_ONE.equals(mode())) return Math.max(0, currentIndex());
        int index = currentIndex();
        if (index < 0) return 0;
        if (index + 1 < entries.size()) return index + 1;
        return MODE_ALL.equals(mode()) ? 0 : -1;
    }

    /** 上一项的下标。语义与 {@link #nextIndex()} 对称。 */
    public int prevIndex() {
        if (entries.isEmpty()) return -1;
        if (MODE_ONE.equals(mode())) return Math.max(0, currentIndex());
        int index = currentIndex();
        if (index < 0) return 0;
        if (index - 1 >= 0) return index - 1;
        return MODE_ALL.equals(mode()) ? entries.size() - 1 : -1;
    }

    @Nullable
    public Entry nextEntry() {
        return entryAt(nextIndex());
    }

    @Nullable
    public Entry prevEntry() {
        return entryAt(prevIndex());
    }

    /** 判断是否为最后一项（循环关闭时用来决定是否要提示播放结束）。 */
    public boolean isLast() {
        int index = currentIndex();
        return index >= 0 && index == entries.size() - 1;
    }

    /** 去空白、限长并丢弃缺少 URL 的条目。 */
    @Nullable
    private static Entry normalize(@Nullable Entry entry) {
        if (entry == null) return null;
        String url = trim(entry.url);
        if (url.isEmpty() || url.length() > MAX_URL) return null;
        Entry result = new Entry();
        result.url = url;
        result.name = trim(entry.name);
        if (result.name.length() > MAX_NAME) result.name = result.name.substring(0, MAX_NAME);
        result.desc = trim(entry.desc);
        return result;
    }

    private static String trim(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    /** 不引入 Android 的 TextUtils，保证本类可在 JVM 单测里运行。 */
    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 队列里的一条：显示名 + 播放地址。 */
    public static class Entry {

        @SerializedName("name")
        public String name = "";
        @SerializedName("desc")
        public String desc = "";
        @SerializedName("url")
        public String url = "";

        public Entry() {
        }

        public Entry(@Nullable String name, @Nullable String url) {
            this.name = name == null ? "" : name;
            this.url = url == null ? "" : url;
        }

        public Entry(@Nullable String name, @Nullable String desc, @Nullable String url) {
            this(name, url);
            this.desc = desc == null ? "" : desc;
        }

        /** 列表里展示的标题：名称优先，退化为 URL。 */
        public String displayName() {
            return isBlank(name) ? url : name;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Entry entry)) return false;
            return url.equals(entry.url);
        }

        @Override
        public int hashCode() {
            return url.hashCode();
        }
    }
}
