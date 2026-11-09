package com.fongmi.android.tv.player.preview;

import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 进度条首帧预览的缓存键与在途去重。
 *
 * <p>刻意不依赖任何 Android 类型，这样时间分档、LRU 淘汰与并发去重都能直接在
 * JVM 单测里验证；真正的画面（Bitmap）由调用方按 {@link #key} 另行持有。
 *
 * <p>时间按档位取整而不是精确到毫秒：拖动会连续产生大量相邻位置，每个位置都
 * 抽一次帧的解码开销无法接受。同一档内复用同一张图。
 *
 * <p>泛型参数 {@code T} 只是为了让「键 → 值」的映射可测；实际类型无所谓。
 */
public class SeekPreviewCache<T> {

    /** 默认时间档：5 秒。 */
    public static final long DEFAULT_BUCKET_MS = 5000L;
    /** 默认容量：60 档，约覆盖 5 分钟素材。 */
    public static final int DEFAULT_MAX_ENTRIES = 60;

    private final long bucketMs;
    private final int maxEntries;
    private final Map<String, T> frames;
    private final Set<String> pending = new LinkedHashSet<>();

    public SeekPreviewCache() {
        this(DEFAULT_BUCKET_MS, DEFAULT_MAX_ENTRIES);
    }

    public SeekPreviewCache(long bucketMs, int maxEntries) {
        this.bucketMs = Math.max(1L, bucketMs);
        this.maxEntries = Math.max(1, maxEntries);
        // accessOrder = true 即 LRU：命中移到队尾，淘汰从队首开始。
        this.frames = new LinkedHashMap<>(16, 0.75f, true);
    }

    /** 把任意播放位置归到时间档的起始毫秒。负值一律归到 0。 */
    public long bucket(long positionMs) {
        return positionMs <= 0L ? 0L : positionMs / bucketMs * bucketMs;
    }

    /** 缓存键 = 播放地址 + 时间档。地址不同不会互相覆盖。 */
    public String key(@Nullable String url, long positionMs) {
        return (url == null ? "" : url) + "@" + bucket(positionMs);
    }

    @Nullable
    public synchronized T get(String key) {
        return frames.get(key);
    }

    /** 写入并执行 LRU 淘汰；value 为 null 时视为无效结果，仅解除在途标记。 */
    public synchronized void put(String key, @Nullable T value) {
        pending.remove(key);
        if (value == null) return;
        frames.put(key, value);
        trim();
    }

    /**
     * 认领一个档位开始抽取。
     *
     * @return true 表示由本次调用负责抽帧；false 表示已有缓存或已有别的请求在做
     */
    public synchronized boolean begin(String key) {
        if (frames.containsKey(key) || pending.contains(key)) return false;
        pending.add(key);
        return true;
    }

    /** 抽取结束必须调用（无论成败），否则该档位会永久阻塞、无法重试。 */
    public synchronized void end(String key) {
        pending.remove(key);
    }

    public synchronized int size() {
        return frames.size();
    }

    public synchronized void clear() {
        frames.clear();
        pending.clear();
    }

    private void trim() {
        if (frames.size() <= maxEntries) return;
        String[] keys = frames.keySet().toArray(new String[0]);
        for (int i = 0; i < keys.length - maxEntries; i++) frames.remove(keys[i]);
    }
}
