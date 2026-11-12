package com.fongmi.android.tv.download;

import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.ContentMetadata;

import com.github.catvod.crawler.SpiderDebug;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 下载缓存（Media3 {@code SimpleCache}）的只读查询层。
 *
 * <p>把「按地址或下载 id 找出已缓存区间」这件事收在一处：本地离线端点与播放侧都要问
 * 同一个问题，两处各写一份迟早会不一致（一处认 id、一处认 url，结果一边能播一边不能播）。
 * 所有方法都对异常兜底——下载模块出问题不能连带把播放弄挂。
 */
public final class OfflineDownloadCache {

    private static final String TAG = "offline-download";

    /** 一段已缓存的内容。 */
    public record Span(long position, long length, File file) {

        public long end() {
            return position + length - 1;
        }
    }

    /** 一次实际读取：从 {@code file} 的 {@code offset} 处读 {@code length} 字节。 */
    public record Chunk(File file, long offset, long length) {
    }

    private OfflineDownloadCache() {
    }

    /**
     * 把 {@code [start, end]} 拆成按序读取的块。
     *
     * <p><b>为什么缺一段就返回空表。</b>响应头一旦发出去就没法改口，读到一半才发现缺数据，
     * 播放器拿到的是一个静默损坏的流——花屏或解码报错，比干脆回源难查得多。所以先问
     * {@link #plan} 拿不到完整计划，就直接判断为「这份缓存不全」，让调用方回源。
     *
     * <p>这段区间算术是纯函数，可以直接 JVM 单测把洞、越界、半段这些情况钉住。
     */
    public static List<Chunk> plan(List<Span> spans, long start, long end) {
        List<Chunk> chunks = new ArrayList<>();
        if (spans == null || spans.isEmpty()) return chunks;
        if (start < 0 || end < start) return chunks;
        List<Span> sorted = new ArrayList<>();
        for (Span span : spans) {
            if (span == null || span.file() == null || span.length() <= 0) continue;
            sorted.add(span);
        }
        sorted.sort((left, right) -> Long.compare(left.position(), right.position()));
        long cursor = start;
        for (Span span : sorted) {
            if (span.end() < cursor) continue;
            // 空隙：中间少了一段，整段都当作不可用。
            if (span.position() > cursor) return new ArrayList<>();
            long from = Math.max(cursor, span.position());
            long to = Math.min(end, span.end());
            chunks.add(new Chunk(span.file(), from - span.position(), to - from + 1));
            cursor = to + 1;
            if (cursor > end) return chunks;
        }
        return new ArrayList<>();
    }

    /** {@code [start, end]} 是否整段都能从缓存里读到。 */
    public static boolean covers(List<Span> spans, long start, long end) {
        return !plan(spans, start, end).isEmpty();
    }

    /** 这些段合起来覆盖到哪（长度）；一段都没有时返回 0。 */
    public static long total(List<Span> spans) {
        long total = 0;
        if (spans == null) return 0;
        for (Span span : spans) {
            if (span == null || span.length() <= 0) continue;
            total = Math.max(total, span.end() + 1);
        }
        return total;
    }

    /**
     * 找出这个地址对应的 cacheKey。
     *
     * <p>两种键都要试：普通文件下载时 {@code customCacheKey} 被设成下载 id；
     * HLS/DASH 的分片与清单没有自定义键，键退化成它们自己的地址
     * （{@code SegmentDownloader} 按分片自身的 DataSpec 取键，已用 javap 核对）。
     * 先试 id 再试地址，命中哪个用哪个。
     */
    public static String keyFor(Cache cache, String url) {
        if (cache == null || url == null || url.isEmpty()) return null;
        String id = OfflineDownloadRecord.idFor(url);
        if (hasSpans(cache, id)) return id;
        if (hasSpans(cache, url)) return url;
        return null;
    }

    private static boolean hasSpans(Cache cache, String key) {
        try {
            Set<String> keys = cache.getKeys();
            return keys != null && keys.contains(key);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 已缓存区间，按位置升序；没有任何缓存时返回空表。
     */
    public static List<Span> spans(Cache cache, String key) {
        List<Span> list = new ArrayList<>();
        if (cache == null || key == null || key.isEmpty()) return list;
        try {
            Set<CacheSpan> cached = cache.getCachedSpans(key);
            if (cached == null) return list;
            for (CacheSpan span : new ArrayList<>(cached)) {
                // 只有 isCached 的段真的有数据；holeSpan 是「还没下到」的占位。
                if (span == null || !span.isCached || span.file == null) continue;
                if (span.length <= 0) continue;
                list.add(new Span(span.position, span.length, span.file));
            }
        } catch (Throwable e) {
            SpiderDebug.log(TAG, e);
            return new ArrayList<>();
        }
        list.sort((left, right) -> Long.compare(left.position(), right.position()));
        return list;
    }

    /**
     * 缓存里记下的内容总长度；未知时返回 -1。
     */
    public static long contentLength(Cache cache, String key) {
        if (cache == null || key == null || key.isEmpty()) return -1L;
        try {
            ContentMetadata metadata = cache.getContentMetadata(key);
            long length = ContentMetadata.getContentLength(metadata);
            return length > 0 ? length : -1L;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    /**
     * 这个地址是否整段都已下好。
     *
     * <p>端点靠它决定「直接读本地」还是「老实回源」：缓存被删过一半时若还硬读本地，
     * 播放器会拿到一个静默损坏的流——花屏或解码报错，比干脆回源更难查。
     */
    public static boolean complete(Cache cache, String url) {
        if (cache == null || url == null || url.isEmpty()) return false;
        String key = keyFor(cache, url);
        if (key == null) return false;
        long total = contentLength(cache, key);
        if (total <= 0) return false;
        // 区间必须首尾相接盖满整段：只看「有没有段」会把缺了中间一段的情况放过去。
        return covers(spans(cache, key), 0, total - 1);
    }
}
