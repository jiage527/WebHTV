package com.fongmi.android.tv.download;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线下载记录表：内存里按 id 索引，落盘一份 JSON，用于进程重启后还能拿到标题与请求头。
 *
 * <p>重点盯两件事：
 * <ul>
 *   <li>落盘的 JSON 读坏了不能把整张表丢掉（写坏一次就永久失去所有请求头，用户此后下载全 403）。</li>
 *   <li>回查请求头必须同时覆盖「按 id」与「按同源地址」，否则 HLS 分片拿不到 Referer。</li>
 * </ul>
 *
 * <p>只用 {@link File} + gson，可直接 JVM 单测（传一个临时文件进来即可）。
 */
public final class OfflineDownloadRegistry {

    private final File file;
    private final Map<String, OfflineDownloadRecord> records = new LinkedHashMap<>();

    public OfflineDownloadRegistry(@Nullable File file) {
        this.file = file;
        load();
    }

    public synchronized void load() {
        records.clear();
        if (file == null || !file.exists() || file.length() <= 0) return;
        String json;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            json = new String(bytes, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return;
        }
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonArray()) return;
            JsonArray array = element.getAsJsonArray();
            for (JsonElement item : array) {
                if (!item.isJsonObject()) continue;
                OfflineDownloadRecord record = OfflineDownloadRecord.fromJson(item.getAsJsonObject());
                if (record != null) records.put(record.id(), record);
            }
        } catch (Throwable ignored) {
            // 单条读坏就只丢那一条，已经塞进去的保留；整份 JSON 读坏则维持空表，不影响继续下载。
        }
    }

    public synchronized void save() {
        if (file == null) return;
        try {
            JsonArray array = new JsonArray();
            for (OfflineDownloadRecord record : records.values()) array.add(record.toJson());
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            java.nio.file.Files.write(file.toPath(), array.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    public synchronized void put(OfflineDownloadRecord record) {
        if (record == null || !record.isValid()) return;
        records.put(record.id(), record);
    }

    public synchronized void remove(String id) {
        if (id == null || id.isEmpty()) return;
        records.remove(id);
    }

    public synchronized void retain(Collection<String> ids) {
        if (ids == null) return;
        records.keySet().retainAll(ids);
    }

    @Nullable
    public synchronized OfflineDownloadRecord get(@Nullable String id) {
        if (id == null || id.isEmpty()) return null;
        return records.get(id);
    }

    @NonNull
    public synchronized String titleOf(@Nullable String id, @Nullable String fallback) {
        OfflineDownloadRecord record = get(id);
        if (record != null && !record.title().isEmpty()) return record.title();
        return fallback == null ? "" : fallback;
    }

    @NonNull
    public synchronized Map<String, String> headersFor(@Nullable String url, @Nullable String key) {
        return OfflineDownloadRecord.headersFor(url, key, snapshot());
    }

    public synchronized int size() {
        return records.size();
    }

    public synchronized boolean isEmpty() {
        return records.isEmpty();
    }

    @NonNull
    public synchronized List<OfflineDownloadRecord> snapshot() {
        return new ArrayList<>(records.values());
    }

    /**
     * 下载面板要用的稳定顺序：新建的排在前面。
     */
    @NonNull
    public synchronized List<OfflineDownloadRecord> sorted() {
        List<OfflineDownloadRecord> list = snapshot();
        list.sort((left, right) -> {
            if (left.createdAt() != right.createdAt()) return Long.compare(right.createdAt(), left.createdAt());
            return left.id().compareTo(right.id());
        });
        return Collections.unmodifiableList(list);
    }
}
