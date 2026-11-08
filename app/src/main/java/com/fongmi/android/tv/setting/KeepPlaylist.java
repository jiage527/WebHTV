package com.fongmi.android.tv.setting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 收藏分类（「我的片单」）与多标签的纯逻辑模型。
 *
 * <p>本类不依赖任何 Android API，可直接在 JVM 单测中运行；持久化与 UI 分别由
 * {@link KeepPlaylistStore} 与 {@code KeepOrganizeDialog} 负责。
 *
 * <p>存储以收藏条目的 {@code Keep.key} 为主键，因此收藏条目本身仍由 Keep 表管理，
 * 这里只保存「分类 / 标签」这层附加信息。删除片单时不会丢弃收藏，而是把条目退回「未分类」。
 */
public class KeepPlaylist {

    public static final int VERSION = 1;
    public static final int MAX_NAME_LENGTH = 40;
    public static final int MAX_TAG_LENGTH = 24;
    public static final int MAX_TAGS_PER_ITEM = 20;
    public static final int MAX_PLAYLISTS = 200;

    /** 未分类片单的标识：空串表示「未分类」。 */
    public static final String UNFILED = "";

    private int version = VERSION;
    private List<Playlist> playlists = new ArrayList<>();
    private List<Entry> entries = new ArrayList<>();

    public static class Playlist {

        private String id;
        private String name;
        private long createdAt;

        public Playlist() {
        }

        public Playlist(String id, String name, long createdAt) {
            this.id = id;
            this.name = name;
            this.createdAt = createdAt;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public long getCreatedAt() {
            return createdAt;
        }
    }

    public static class Entry {

        private String key;
        private String playlistId = UNFILED;
        private List<String> tags = new ArrayList<>();

        public Entry() {
        }

        public Entry(String key) {
            this.key = key;
        }

        public String getKey() {
            return key;
        }

        public String getPlaylistId() {
            return playlistId == null ? UNFILED : playlistId;
        }

        public List<String> getTags() {
            return tags == null ? Collections.emptyList() : tags;
        }
    }

    public static KeepPlaylist empty() {
        return new KeepPlaylist();
    }

    /** 宽容解析：任何损坏、缺字段或越界的条目都会被丢弃，绝不抛异常。 */
    public static KeepPlaylist fromJson(String json) {
        if (json == null || json.trim().isEmpty()) return empty();
        KeepPlaylist parsed;
        try {
            parsed = new Gson().fromJson(json, KeepPlaylist.class);
        } catch (Exception e) {
            return empty();
        }
        return parsed == null ? empty() : parsed.normalize();
    }

    public String toJson() {
        return new GsonBuilder().setPrettyPrinting().create().toJson(normalize());
    }

    /** 逐字段校验并修复，任何入口（新建 / 解析 / 导入）都会经过这里。 */
    KeepPlaylist normalize() {
        KeepPlaylist result = new KeepPlaylist();
        List<Playlist> source = playlists == null ? Collections.emptyList() : playlists;
        Set<String> seen = new LinkedHashSet<>();
        for (Playlist item : source) {
            if (item == null || result.playlists.size() >= MAX_PLAYLISTS) continue;
            String id = trim(item.getId());
            String name = normalizeName(item.getName());
            if (id.isEmpty() || name.isEmpty() || !seen.add(id)) continue;
            result.playlists.add(new Playlist(id, name, item.getCreatedAt()));
        }
        List<Entry> sourceEntries = entries == null ? Collections.emptyList() : entries;
        Set<String> seenKeys = new LinkedHashSet<>();
        for (Entry item : sourceEntries) {
            if (item == null) continue;
            String key = trim(item.getKey());
            if (key.isEmpty() || !seenKeys.add(key)) continue;
            Entry entry = new Entry(key);
            String playlistId = trim(item.getPlaylistId());
            entry.playlistId = seen.contains(playlistId) ? playlistId : UNFILED;
            entry.tags = sanitizeTags(item.getTags());
            if (entry.playlistId.isEmpty() && entry.tags.isEmpty()) continue;
            result.entries.add(entry);
        }
        return result;
    }

    // ------------------------------------------------------------------ 片单

    public List<Playlist> getPlaylists() {
        return Collections.unmodifiableList(playlists);
    }

    public Playlist findPlaylist(String id) {
        String target = trim(id);
        for (Playlist item : playlists) if (item.getId().equals(target)) return item;
        return null;
    }

    public boolean hasPlaylist(String name) {
        String target = normalizeName(name);
        if (target.isEmpty()) return false;
        for (Playlist item : playlists) if (item.getName().equalsIgnoreCase(target)) return true;
        return false;
    }

    /** 新建片单；名称为空、重复或已达上限时返回 null。 */
    public String createPlaylist(String name, long now) {
        String value = normalizeName(name);
        if (value.isEmpty() || hasPlaylist(value) || playlists.size() >= MAX_PLAYLISTS) return null;
        String id = "p" + now + "-" + (playlists.size() + 1);
        playlists.add(new Playlist(id, value, now));
        return id;
    }

    public boolean renamePlaylist(String id, String name) {
        Playlist target = findPlaylist(id);
        String value = normalizeName(name);
        if (target == null || value.isEmpty()) return false;
        if (target.getName().equalsIgnoreCase(value)) return true;
        if (hasPlaylist(value)) return false;
        playlists.set(playlists.indexOf(target), new Playlist(target.getId(), value, target.getCreatedAt()));
        return true;
    }

    /** 删除片单本身，其条目退回「未分类」——收藏条目绝不因删片单而丢失。 */
    public boolean deletePlaylist(String id) {
        Playlist target = findPlaylist(id);
        if (target == null) return false;
        playlists.remove(target);
        for (Entry entry : entries) if (target.getId().equals(entry.getPlaylistId())) entry.playlistId = UNFILED;
        prune();
        return true;
    }

    // ------------------------------------------------------------------ 归类

    /** 批量移动到片单；目标片单不存在（且非「未分类」）时整体失败，不做半途修改。 */
    public boolean move(List<String> keys, String playlistId) {
        String target = trim(playlistId);
        if (!target.isEmpty() && findPlaylist(target) == null) return false;
        for (String key : safeKeys(keys)) entryOf(key).playlistId = target;
        prune();
        return true;
    }

    public String playlistOf(String key) {
        Entry entry = findEntry(key);
        return entry == null ? UNFILED : entry.getPlaylistId();
    }

    /** 所有已记录分类 / 标签的收藏键。 */
    public List<String> allKeys() {
        List<String> keys = new ArrayList<>();
        for (Entry entry : entries) keys.add(entry.getKey());
        return keys;
    }

    public List<String> keysIn(String playlistId) {
        String target = trim(playlistId);
        List<String> keys = new ArrayList<>();
        for (Entry entry : entries) if (target.equals(entry.getPlaylistId())) keys.add(entry.getKey());
        return keys;
    }

    public int countOf(String playlistId) {
        return keysIn(playlistId).size();
    }

    // ------------------------------------------------------------------ 标签

    public List<String> tagsOf(String key) {
        Entry entry = findEntry(key);
        return entry == null ? Collections.emptyList() : Collections.unmodifiableList(entry.getTags());
    }

    public boolean setTags(String key, List<String> tags) {
        String value = trim(key);
        if (value.isEmpty()) return false;
        entryOf(value).tags = sanitizeTags(tags);
        prune();
        return true;
    }

    public boolean addTag(String key, String tag) {
        List<String> tags = new ArrayList<>(tagsOf(key));
        tags.add(tag);
        return setTags(key, tags);
    }

    /** 去重后的全部标签，按名称忽略大小写排序；跨条目同名不同大小写只保留首次出现的写法。 */
    public List<String> allTags() {
        List<String> result = new ArrayList<>();
        for (Entry entry : entries) {
            for (String tag : entry.getTags()) {
                boolean exists = false;
                for (String item : result) if (item.equalsIgnoreCase(tag)) exists = true;
                if (!exists) result.add(tag);
            }
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    public List<String> keysWithTag(String tag) {
        String target = normalizeTag(tag);
        List<String> keys = new ArrayList<>();
        if (target.isEmpty()) return keys;
        for (Entry entry : entries) {
            for (String item : entry.getTags()) if (item.equalsIgnoreCase(target)) {
                keys.add(entry.getKey());
                break;
            }
        }
        return keys;
    }

    // ------------------------------------------------------------------ 维护

    /** 收藏被删除后同步清掉其分类与标签，避免文件无限增长。 */
    public void removeKeys(List<String> keys) {
        for (String key : safeKeys(keys)) entries.removeIf(entry -> key.equals(entry.getKey()));
    }

    public boolean isEmpty() {
        return playlists.isEmpty() && entries.isEmpty();
    }

    // ------------------------------------------------------------------ 工具

    private Entry findEntry(String key) {
        String value = trim(key);
        if (value.isEmpty()) return null;
        for (Entry entry : entries) if (value.equals(entry.getKey())) return entry;
        return null;
    }

    private Entry entryOf(String key) {
        Entry entry = findEntry(key);
        if (entry != null) return entry;
        Entry created = new Entry(key);
        entries.add(created);
        return created;
    }

    /** 去掉既无分类又无标签的空条目。 */
    private void prune() {
        entries.removeIf(entry -> entry.playlistId.isEmpty() && entry.getTags().isEmpty());
    }

    private static List<String> safeKeys(List<String> keys) {
        List<String> result = new ArrayList<>();
        if (keys == null) return result;
        for (String key : keys) {
            String value = trim(key);
            if (!value.isEmpty() && !result.contains(value)) result.add(value);
        }
        return result;
    }

    public static List<String> sanitizeTags(List<String> tags) {
        List<String> result = new ArrayList<>();
        if (tags == null) return result;
        for (String tag : tags) {
            if (result.size() >= MAX_TAGS_PER_ITEM) break;
            String value = normalizeTag(tag);
            if (value.isEmpty()) continue;
            boolean exists = false;
            for (String item : result) if (item.equalsIgnoreCase(value)) exists = true;
            if (!exists) result.add(value);
        }
        return result;
    }

    /** 标签统一去掉首尾空白、内部空白压成单空格，并截断到上限长度。 */
    public static String normalizeTag(String tag) {
        String value = trim(tag).replaceAll("\\s+", " ");
        if (value.length() > MAX_TAG_LENGTH) value = value.substring(0, MAX_TAG_LENGTH).trim();
        return value;
    }

    /** 片单名去掉首尾空白、内部空白压成单空格，并截断到上限长度。 */
    public static String normalizeName(String name) {
        String value = trim(name).replaceAll("\\s+", " ");
        if (value.length() > MAX_NAME_LENGTH) value = value.substring(0, MAX_NAME_LENGTH).trim();
        return value;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
