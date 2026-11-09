package com.fongmi.android.tv.player.intro;

import com.github.catvod.utils.Path;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 各剧的片头片尾档案，存成 JSON 文件。
 *
 * <p><b>不建数据库、不做迁移</b>（项目硬约束）。这里存的是可随时重建的偏好数据，
 * 丢了最坏结果是重新学一遍，不值得为它加一张表和一个迁移版本号——迁移一旦写错，
 * 影响的是整个历史库。同样的取舍见 T2-02、T2-13。
 *
 * <p><b>为什么整体序列化后一次写出。</b>档案是累积出来的，局部写入一旦写坏，
 * 用户会静默丢掉已学到的数据且无法察觉。整体写出最坏只是这一次新增没生效，
 * 已有数据不受损。
 *
 * <p><b>为什么要有 {@link #save()} 的空写跳过。</b>播放进度上报很频繁，每次都写文件
 * 会在低端设备上造成可感的 IO 抖动；内容没变就不写。
 */
public final class IntroProfileStore {

    private static final String FILE_NAME = "intro_profiles.json";
    private static final Gson GSON = new Gson();

    private static Map<String, IntroProfile> profiles;
    /** 上次落盘的内容，用于跳过无变化的写盘。 */
    private static String loaded = "";

    private IntroProfileStore() {
    }

    private static synchronized void ensureLoaded() {
        if (profiles != null) return;
        Map<String, IntroProfile> result = new HashMap<>();
        try {
            File file = Path.files(FILE_NAME);
            String text = file.isFile() ? Path.read(file) : "";
            loaded = text == null ? "" : text;
            if (!loaded.isEmpty()) {
                JsonObject root = JsonParser.parseString(loaded).getAsJsonObject();
                JsonObject items = root.has("profiles") ? root.getAsJsonObject("profiles") : root;
                for (String key : items.keySet()) {
                    try {
                        IntroProfile profile = GSON.fromJson(items.get(key), IntroProfile.class);
                        // 单条坏了只丢那一条，不能让整份档案都读不出来。
                        if (profile != null) result.put(key, profile);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        profiles = result;
    }

    /** 取某部剧的档案；没有就建一个空的（空档案不落盘）。 */
    public static synchronized IntroProfile get(String seriesKey) {
        ensureLoaded();
        if (seriesKey == null || seriesKey.isEmpty()) return new IntroProfile();
        return profiles.computeIfAbsent(seriesKey, key -> new IntroProfile());
    }

    /** 记录一次观察并落盘。 */
    public static synchronized void record(String seriesKey, long openingEnd, long openingLength, long endingStart) {
        if (seriesKey == null || seriesKey.isEmpty()) return;
        ensureLoaded();
        profiles.get(seriesKey).record(openingEnd, openingLength, endingStart);
        save();
    }

    /** 只有内容真的变了才写盘。 */
    public static synchronized void save() {
        ensureLoaded();
        try {
            JsonObject root = new JsonObject();
            JsonObject items = new JsonObject();
            for (Map.Entry<String, IntroProfile> entry : profiles.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isEmpty()) continue;
                items.add(entry.getKey(), GSON.toJsonTree(entry.getValue()));
            }
            root.add("profiles", items);
            String text = GSON.toJson(root);
            if (text.equals(loaded)) return;
            Path.write(Path.files(FILE_NAME), text.getBytes(StandardCharsets.UTF_8));
            loaded = text;
        } catch (Throwable ignored) {
        }
    }

    /** 只用于测试：清掉内存态。 */
    static synchronized void reset() {
        profiles = null;
        loaded = "";
    }

    /** 只用于测试：先建好某部剧的档案再记样本。 */
    static synchronized void prepare(String seriesKey) {
        ensureLoaded();
        profiles.computeIfAbsent(seriesKey, key -> new IntroProfile());
    }
}
