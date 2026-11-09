package com.fongmi.android.tv.setting;

import androidx.annotation.Nullable;

import com.github.catvod.utils.Path;
import com.github.catvod.utils.Prefers;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 播放队列的持久化层。
 *
 * <p>与收藏分类一致，刻意<b>不建数据库表</b>：队列是一份小 JSON，放在应用私有目录即可，
 * 避免 Room 迁移风险。同时把循环模式单独记在首选项里 —— 它是用户的播放偏好，
 * 清空队列时也应当保留。
 */
public final class PlayQueueStore {

    private static final String FILE_NAME = "play_queue.json";
    private static final String KEY_MODE = "play_queue_mode";

    private static PlayQueue cache;

    private PlayQueueStore() {
    }

    public static synchronized PlayQueue get() {
        if (cache == null) {
            // 循环模式以首选项为准：它是独立于队列内容的偏好。
            String mode = Prefers.getString(KEY_MODE);
            cache = PlayQueue.fromJson(readContent());
            if (mode != null && !mode.isEmpty()) cache.setMode(mode);
        }
        return cache;
    }

    public static synchronized void save() {
        if (cache == null) return;
        Prefers.put(KEY_MODE, cache.mode());
        try {
            Path.write(Path.files(FILE_NAME), cache.toJson().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 落盘失败不影响本次播放：内存模型仍然有效，下次改动能再试一次。
        }
    }

    public static synchronized void reload() {
        cache = null;
        get();
    }

    public static synchronized void reset() {
        cache = PlayQueue.empty();
        cache.setMode(Prefers.getString(KEY_MODE));
        save();
    }

    /** 直接替换整个队列（例如「加入整季」）。 */
    public static synchronized void replace(PlayQueue queue) {
        if (queue == null) return;
        queue.setMode(get().mode());
        cache = queue;
        save();
    }

    /**
     * 追加一集到队尾。返回 false 表示重复或地址不可用。
     *
     * <p>不在这里改「当前项」：加入的条目未必是正在播的那一集（例如看第 5 集时
     * 把整季入队），由调用方用 {@link #alignCurrent} 按实际播放地址对齐。
     */
    public static synchronized boolean add(@Nullable String name, @Nullable String url) {
        PlayQueue queue = get();
        if (!queue.add(new PlayQueue.Entry(name, url))) return false;
        save();
        return true;
    }

    /** 插队：排在当前项之后立即播放。 */
    public static synchronized boolean insertNext(@Nullable String name, @Nullable String url) {
        PlayQueue queue = get();
        if (!queue.insertNext(new PlayQueue.Entry(name, url))) return false;
        save();
        return true;
    }

    /** 追加一批（例如整季）。返回真正加入的数量，已存在的会被跳过。 */
    public static synchronized int addAll(List<PlayQueue.Entry> entries) {
        if (entries == null || entries.isEmpty()) return 0;
        PlayQueue queue = get();
        int added = 0;
        for (PlayQueue.Entry entry : entries) {
            if (queue.add(entry)) added++;
        }
        if (added > 0) save();
        return added;
    }

    /** 记录当前播放项，供下次启动与「下一集」定位。 */
    public static synchronized void markCurrent(@Nullable String url) {
        PlayQueue queue = get();
        queue.setCurrent(url);
        save();
    }


    @Nullable
    private static String readContent() {
        try {
            java.io.File file = Path.files(FILE_NAME);
            return file.exists() ? Path.read(file) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
