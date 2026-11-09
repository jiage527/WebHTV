package com.fongmi.android.tv.player.preview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SeekPreviewCacheTest {

    private static final String URL = "https://cdn.example.com/a.m3u8";

    @Test
    public void bucketRoundsDownToIntervalStart() {
        SeekPreviewCache<String> cache = new SeekPreviewCache<>(5000L, 10);
        assertEquals(0L, cache.bucket(0L));
        assertEquals(0L, cache.bucket(4999L));
        assertEquals(5000L, cache.bucket(5000L));
        assertEquals(5000L, cache.bucket(9999L));
        assertEquals(60000L, cache.bucket(63421L));
    }

    @Test
    public void negativePositionFallsBackToZero() {
        SeekPreviewCache<String> cache = new SeekPreviewCache<>(5000L, 10);
        assertEquals(0L, cache.bucket(-1L));
        assertEquals(0L, cache.bucket(-90000L));
    }

    @Test
    public void keyCombinesUrlAndBucket() {
        SeekPreviewCache<String> cache = new SeekPreviewCache<>(5000L, 10);
        assertEquals(URL + "@5000", cache.key(URL, 7200L));
        assertEquals(URL + "@0", cache.key(URL, -5L));
    }

    @Test
    public void nullUrlDoesNotCollideWithEmptyString() {
        SeekPreviewCache<String> cache = new SeekPreviewCache<>(5000L, 10);
        assertEquals("@0", cache.key(null, 0L));
    }

    @Test
    public void sameBucketReusesOneEntry() {
        SeekPreviewCache<String> cache = new SeekPreviewCache<>(5000L, 10);
        cache.put(cache.key(URL, 1000L), "a");
        cache.put(cache.key(URL, 4000L), "b");
        assertEquals(1, cache.size());
        assertEquals("b", cache.get(cache.key(URL, 2000L)));
    }

    @Test
    public void differentUrlsAreIndependent() {
        SeekPreviewCache<String> cache = new SeekPreviewCache<>(5000L, 10);
        cache.put(cache.key(URL, 1000L), "a");
        cache.put(cache.key("https://cdn.example.com/b.m3u8", 1000L), "b");
        assertEquals(2, cache.size());
        assertEquals("a", cache.get(cache.key(URL, 0L)));
    }

    @Test
    public void nullValueOnlyClearsPendingAndStoresNothing() {
        SeekPreviewCache<String> cache = new SeekPreviewCache<>(5000L, 10);
        String key = cache.key(URL, 0L);
        assertTrue(cache.begin(key));
        cache.put(key, null);
        assertEquals(0, cache.size());
        assertNull(cache.get(key));
        // 失败后必须能重新认领，否则用户拖回同一位置永远不会重试。
        assertTrue(cache.begin(key));
    }

    @Test
    public void beginIsRejectedWhileInFlight() {
        SeekPreviewCache<String> cache = new SeekPreviewCache<>(5000L, 10);
        String key = cache.key(URL, 0L);
        assertTrue(cache.begin(key));
        assertFalse(cache.begin(key));
        cache.end(key);
        assertTrue(cache.begin(key));
    }

    @Test
    public void beginIsRejectedWhenAlreadyCached() {
        SeekPreviewCache<String> cache = new SeekPreviewCache<>(5000L, 10);
        String key = cache.key(URL, 0L);
        cache.put(key, "a");
        assertFalse(cache.begin(key));
    }

    @Test
    public void beginClaimsPerBucketNotPerPosition() {
        SeekPreviewCache<String> cache = new SeekPreviewCache<>(5000L, 10);
        assertTrue(cache.begin(cache.key(URL, 1000L)));
        // 同一档位内的不同位置视为同一次抽取。
        assertFalse(cache.begin(cache.key(URL, 4900L)));
        assertTrue(cache.begin(cache.key(URL, 5000L)));
    }

    @Test
    public void trimKeepsMostRecentlyUsedEntries() {
        SeekPreviewCache<String> cache = new SeekPreviewCache<>(1000L, 3);
        for (int i = 0; i < 4; i++) cache.put(cache.key(URL, i * 1000L), "v" + i);
        assertEquals(3, cache.size());
        // 第 0 档最旧，最先被淘汰。
        assertNull(cache.get(cache.key(URL, 0L)));
        assertEquals("v3", cache.get(cache.key(URL, 3000L)));
    }

    @Test
    public void getRefreshesRecency() {
        SeekPreviewCache<String> cache = new SeekPreviewCache<>(1000L, 3);
        cache.put(cache.key(URL, 0L), "v0");
        cache.put(cache.key(URL, 1000L), "v1");
        cache.put(cache.key(URL, 2000L), "v2");
        // 读一下最旧的，把它变成最近使用。
        assertEquals("v0", cache.get(cache.key(URL, 0L)));
        cache.put(cache.key(URL, 3000L), "v3");
        assertEquals(3, cache.size());
        assertEquals("v0", cache.get(cache.key(URL, 0L)));
        assertNull(cache.get(cache.key(URL, 1000L)));
    }

    @Test
    public void clearDropsFramesAndPending() {
        SeekPreviewCache<String> cache = new SeekPreviewCache<>(5000L, 10);
        String key = cache.key(URL, 0L);
        cache.put(key, "a");
        assertTrue(cache.begin(cache.key(URL, 5000L)));
        cache.clear();
        assertEquals(0, cache.size());
        assertTrue(cache.begin(key));
    }

    @Test
    public void invalidConstructorArgumentsAreClamped() {
        SeekPreviewCache<String> cache = new SeekPreviewCache(0L, 0);
        // 档位至少 1ms（避免除零），容量至少 1。
        assertEquals(5L, cache.bucket(5L));
        cache.put(cache.key(URL, 0L), "a");
        cache.put(cache.key(URL, 1L), "b");
        assertEquals(1, cache.size());
    }

    @Test
    public void endOnUnknownKeyIsHarmless() {
        SeekPreviewCache<String> cache = new SeekPreviewCache<>(5000L, 10);
        cache.end("never-began");
        assertEquals(0, cache.size());
    }
}
