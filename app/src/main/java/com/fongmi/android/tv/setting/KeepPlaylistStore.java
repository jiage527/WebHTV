package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Keep;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Prefers;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 收藏分类 / 标签的持久化层。
 *
 * <p>数据量小（只存 key → 片单 / 标签），因此刻意<b>不建数据库表</b>：一份 JSON 文件放在
 * 应用私有目录，避免 Room 迁移与版本升级带来的风险。文件损坏时静默退化为空模型，绝不影响收藏本身。
 */
public class KeepPlaylistStore {

    private static final String FILE_NAME = "keep_playlist.json";
    /** 兼容早期把内容放在首选项里的情况。 */
    private static final String PREF_LEGACY = "keep_playlist_json";

    private static KeepPlaylist cache;

    private KeepPlaylistStore() {
    }

    public static synchronized KeepPlaylist get() {
        if (cache == null) cache = KeepPlaylist.fromJson(readContent());
        return cache;
    }

    public static synchronized void save() {
        if (cache == null) return;
        try {
            Path.write(file(), cache.toJson().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 落盘失败不影响使用：内存模型仍有效，下次改动能再试一次。
        }
    }

    public static synchronized void reload() {
        cache = KeepPlaylist.fromJson(readContent());
    }

    public static synchronized void reset() {
        cache = KeepPlaylist.empty();
        save();
    }

    /** 与 Keep 表对齐：删掉已不存在的收藏条目，避免文件随时间无限增长。 */
    public static synchronized void pruneDeletedKeeps() {
        KeepPlaylist playlist = get();
        if (playlist.isEmpty()) return;
        List<String> alive = new ArrayList<>();
        for (Keep item : Keep.getVod()) alive.add(item.getKey());
        List<String> dead = new ArrayList<>();
        for (String key : playlist.allKeys()) if (!alive.contains(key)) dead.add(key);
        if (dead.isEmpty()) return;
        playlist.removeKeys(dead);
        save();
    }

    /** 导出为可读 JSON 文本。 */
    public static synchronized String export() {
        return get().toJson();
    }

    /**
     * 导入 JSON。返回 null 表示成功，否则返回错误文案。
     *
     * @param merge true 时与现有数据合并（同名片单复用），false 时整体替换。
     */
    public static synchronized String importFrom(String json, boolean merge) {
        if (TextUtils.isEmpty(json) || json.trim().isEmpty()) return App.get().getString(R.string.keep_import_empty);
        KeepPlaylist incoming = KeepPlaylist.fromJson(json);
        if (incoming.isEmpty()) return App.get().getString(R.string.keep_import_failed);
        if (!merge) {
            cache = incoming;
            save();
            return null;
        }
        KeepPlaylist current = get();
        for (KeepPlaylist.Playlist item : incoming.getPlaylists()) {
            String id = findIdByName(current, item.getName());
            if (id == null) id = current.createPlaylist(item.getName(), System.currentTimeMillis());
            if (id == null) continue;
            List<String> keys = incoming.keysIn(item.getId());
            current.move(keys, id);
            for (String key : keys) current.setTags(key, incoming.tagsOf(key));
        }
        for (String key : incoming.keysIn(KeepPlaylist.UNFILED)) current.setTags(key, incoming.tagsOf(key));
        save();
        return null;
    }

    private static String findIdByName(KeepPlaylist playlist, String name) {
        String target = KeepPlaylist.normalizeName(name);
        for (KeepPlaylist.Playlist item : playlist.getPlaylists()) if (item.getName().equalsIgnoreCase(target)) return item.getId();
        return null;
    }

    private static File file() {
        return Path.files(FILE_NAME);
    }

    private static String readContent() {
        try {
            File file = file();
            if (!file.exists()) return Prefers.getString(PREF_LEGACY, "");
            return Path.read(file);
        } catch (Exception e) {
            return "";
        }
    }
}
