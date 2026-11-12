package com.fongmi.android.tv.download;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条离线下载的「下载之外」信息：标题、原始地址、类型、创建时间、以及下载时用的请求头。
 *
 * <p>为什么要单独存：Media3 的 {@code DownloadRequest} 只能带 id + uri + mimeType + customCacheKey，
 * 既不保存标题，下载器也拿不到请求头（{@code DownloadRequest.toMediaItem()} 只搬 id/uri/cacheKey/mimeType/streamKeys，
 * 已用 javap 核对过）。而很多直链必须带 Referer/User-Agent 才能拉，缺了就是 403。
 * 所以请求头由本记录持久化，下载数据源按 id 或同源地址回查（见 {@link #headersFor}）。
 *
 * <p>本类不碰任何 Android 类，可直接 JVM 单测。
 */
public final class OfflineDownloadRecord {

    private final String id;
    private final String title;
    private final String url;
    private final OfflineDownloadKind kind;
    private final long createdAt;
    private final Map<String, String> headers;

    public OfflineDownloadRecord(String id, String title, String url, OfflineDownloadKind kind, long createdAt, @Nullable Map<String, String> headers) {
        this.id = id == null ? "" : id;
        this.title = title == null ? "" : title;
        this.url = url == null ? "" : url;
        this.kind = kind == null ? OfflineDownloadKind.UNKNOWN : kind;
        this.createdAt = createdAt;
        this.headers = sanitize(headers);
    }

    /**
     * 由地址生成下载记录，播放页点「下载」时用。
     *
     * <p><b>id 为什么由地址算出来而不是随机取。</b>id 同时是 {@code DownloadRequest.id} 与
     * {@code customCacheKey}（见 {@code OfflineDownloadManager#buildRequest}），播放侧就是靠
     * 「cacheKey 是否命中一条已完成的下载」来判断「这条能离线看」。如果每次点下载都换一个新 id，
     * 同一集下第二次就会得到两份互不相认的缓存，播放器永远只认得其中一份。用地址的摘要做 id，
     * 同一地址永远是同一个 key，删掉再下也一样。
     *
     * <p>用 SHA-1 而不是 {@code hashCode()}：后者只有 32 位，不同的剧集地址撞到同一个 key
     * 会让播放器读到别人的分片——画面串台比「下不了」难查得多。
     */
    @NonNull
    public static OfflineDownloadRecord create(@Nullable String title, @Nullable String url, @Nullable Map<String, String> headers, long createdAt) {
        return new OfflineDownloadRecord(idFor(url), title, url, OfflineDownloadKind.ofUrl(url), createdAt, headers);
    }

    /** 地址 → 稳定的下载 id。地址为空时返回空串（{@link #isValid()} 会把它挡在队列外）。 */
    @NonNull
    public static String idFor(@Nullable String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-1").digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder("od-");
            for (int i = 0; i < 8; i++) builder.append(String.format(java.util.Locale.ROOT, "%02x", digest[i]));
            return builder.toString();
        } catch (Throwable ignored) {
            // 摘不出来也不能让下载入口直接崩：退回长度 + 哈希，仍然稳定，只是防撞强度低一些。
            return "od-" + Integer.toHexString(url.length()) + "-" + Integer.toHexString(url.hashCode());
        }
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String url() {
        return url;
    }

    public OfflineDownloadKind kind() {
        return kind;
    }

    public long createdAt() {
        return createdAt;
    }

    public Map<String, String> headers() {
        return Collections.unmodifiableMap(headers);
    }

    public boolean isValid() {
        return !id.isEmpty() && !url.isEmpty();
    }

    /**
     * 界面上的标题：没标题就退回原始地址，免得列表里出现一片空白。
     */
    @NonNull
    public String displayTitle() {
        return title.isEmpty() ? url : title;
    }

    /**
     * 同源前缀（scheme://host[:port]）。HLS/DASH 的分片地址与清单不同路径，只能靠同源判断。
     */
    @Nullable
    public String origin() {
        return originOf(url);
    }

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("id", id);
        object.addProperty("title", title);
        object.addProperty("url", url);
        object.addProperty("kind", kind.name());
        object.addProperty("createdAt", createdAt);
        if (!headers.isEmpty()) {
            JsonObject headerObject = new JsonObject();
            for (Map.Entry<String, String> entry : headers.entrySet()) headerObject.addProperty(entry.getKey(), entry.getValue());
            object.add("headers", headerObject);
        }
        return object;
    }

    @Nullable
    public static OfflineDownloadRecord fromJson(@Nullable String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return fromJson(JsonParser.parseString(json).getAsJsonObject());
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    public static OfflineDownloadRecord fromJson(@Nullable JsonObject object) {
        if (object == null || !object.has("id") || !object.has("url")) return null;
        Map<String, String> headers = new LinkedHashMap<>();
        if (object.has("headers") && object.get("headers").isJsonObject()) {
            JsonObject headerObject = object.getAsJsonObject("headers");
            for (String key : headerObject.keySet()) {
                if (!headerObject.get(key).isJsonPrimitive()) continue;
                headers.put(key, headerObject.get(key).getAsString());
            }
        }
        String kindName = object.has("kind") && object.get("kind").isJsonPrimitive() ? object.get("kind").getAsString() : OfflineDownloadKind.UNKNOWN.name();
        OfflineDownloadKind kind;
        try {
            kind = OfflineDownloadKind.valueOf(kindName);
        } catch (Throwable ignored) {
            kind = OfflineDownloadKind.UNKNOWN;
        }
        String title = object.has("title") && object.get("title").isJsonPrimitive() ? object.get("title").getAsString() : "";
        long createdAt = object.has("createdAt") && object.get("createdAt").isJsonPrimitive() ? object.get("createdAt").getAsLong() : 0L;
        OfflineDownloadRecord record = new OfflineDownloadRecord(
                object.get("id").getAsString(),
                title,
                object.get("url").getAsString(),
                kind,
                createdAt,
                headers);
        return record.isValid() ? record : null;
    }

    /**
     * 下载数据源回查请求头的入口。
     *
     * <p>优先按 cacheKey（= 下载 id）匹配——普通文件下载与清单请求走这条；
     * HLS/DASH 的分片地址既没有我们的 key、路径也和清单不同，只能退回「同源」匹配，
     * 否则分片会裸奔（缺 Referer 时整段 403，用户看到的是「下载失败」而不是「下不了」）。
     */
    @NonNull
    public static Map<String, String> headersFor(@Nullable String url, @Nullable String key, @Nullable Iterable<OfflineDownloadRecord> records) {
        if (records == null) return Collections.emptyMap();
        String origin = originOf(url);
        for (OfflineDownloadRecord record : records) {
            if (record == null) continue;
            if (key != null && !key.isEmpty() && key.startsWith(record.id)) return record.headers();
        }
        if (origin == null || origin.isEmpty()) return Collections.emptyMap();
        for (OfflineDownloadRecord record : records) {
            if (record == null) continue;
            if (origin.equalsIgnoreCase(record.origin())) return record.headers();
        }
        return Collections.emptyMap();
    }

    @Nullable
    static String originOf(@Nullable String url) {
        if (url == null || url.isEmpty()) return null;
        int schemeEnd = url.indexOf("://");
        if (schemeEnd <= 0) return null;
        int pathStart = url.indexOf('/', schemeEnd + 3);
        return pathStart < 0 ? url : url.substring(0, pathStart);
    }

    private static Map<String, String> sanitize(@Nullable Map<String, String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        if (headers == null) return result;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            String name = entry.getKey().trim();
            String value = entry.getValue().trim();
            if (name.isEmpty() || value.isEmpty()) continue;
            result.put(name, value);
        }
        return result;
    }
}
