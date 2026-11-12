package com.fongmi.android.tv.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 重点盯三件事：跨分片拼接的区间算术不能少读也不能多读、中间缺一段必须整体判为不可用
 * （否则播放器会拿到一个静默损坏的流）、地址↔端点地址的转换必须能原样来回。
 */
public class OfflineDownloadCacheTest {

    private static List<OfflineDownloadCache.Span> spans(long... positions) {
        List<OfflineDownloadCache.Span> list = new java.util.ArrayList<>();
        for (long position : positions) list.add(new OfflineDownloadCache.Span(position, 100, new File(position + ".bin")));
        return list;
    }

    @Test
    public void planReadsOneChunkWhenTheRangeSitsInsideOneSpan() {
        List<OfflineDownloadCache.Chunk> chunks = OfflineDownloadCache.plan(spans(0, 100, 200), 10, 20);
        assertEquals(1, chunks.size());
        assertEquals(10L, chunks.get(0).offset());
        assertEquals(11L, chunks.get(0).length());
    }

    @Test
    public void planSpansMultipleChunksInOrder() {
        List<OfflineDownloadCache.Chunk> chunks = OfflineDownloadCache.plan(spans(0, 100, 200), 50, 149);
        // 0..99 里的 50..99（50 字节）+ 100..149（50 字节）
        assertEquals(2, chunks.size());
        assertEquals(50L, chunks.get(0).offset());
        assertEquals(50L, chunks.get(0).length());
        assertEquals(0L, chunks.get(1).offset());
        assertEquals(50L, chunks.get(1).length());
    }

    @Test
    public void planSortsSpansThatArrivedOutOfOrder() {
        List<OfflineDownloadCache.Span> shuffled = new java.util.ArrayList<>(spans(200, 0, 100));
        List<OfflineDownloadCache.Chunk> chunks = OfflineDownloadCache.plan(shuffled, 0, 299);
        assertEquals(3, chunks.size());
        assertEquals(new File("0.bin"), chunks.get(0).file());
        assertEquals(new File("100.bin"), chunks.get(1).file());
        assertEquals(new File("200.bin"), chunks.get(2).file());
    }

    @Test
    public void planRefusesRangesWithAHole() {
        // 缺 100..199：整段都不可用，而不是给出前半截。
        assertTrue(OfflineDownloadCache.plan(spans(0, 200), 0, 299).isEmpty());
        assertFalse(OfflineDownloadCache.covers(spans(0, 200), 0, 299));
    }

    @Test
    public void planRefusesRangesPastTheEnd() {
        assertTrue(OfflineDownloadCache.plan(spans(0), 0, 200).isEmpty());
    }

    @Test
    public void planRejectsDegenerateInput() {
        assertTrue(OfflineDownloadCache.plan(null, 0, 10).isEmpty());
        assertTrue(OfflineDownloadCache.plan(Collections.emptyList(), 0, 10).isEmpty());
        assertTrue(OfflineDownloadCache.plan(spans(0), -1, 10).isEmpty());
        assertTrue(OfflineDownloadCache.plan(spans(0), 10, 5).isEmpty());
    }

    @Test
    public void planSkipsSpansBeforeTheStart() {
        List<OfflineDownloadCache.Chunk> chunks = OfflineDownloadCache.plan(spans(0, 100), 120, 130);
        assertEquals(1, chunks.size());
        assertEquals(new File("100.bin"), chunks.get(0).file());
        assertEquals(20L, chunks.get(0).offset());
    }

    @Test
    public void totalMeasuresHowFarTheSpansReach() {
        assertEquals(0L, OfflineDownloadCache.total(null));
        assertEquals(0L, OfflineDownloadCache.total(Collections.emptyList()));
        assertEquals(300L, OfflineDownloadCache.total(spans(0, 100, 200)));
    }

    @Test
    public void coversAcceptsEachSpanIndividually() {
        assertTrue(OfflineDownloadCache.covers(spans(0, 100), 100, 199));
        assertTrue(OfflineDownloadCache.covers(Arrays.asList(new OfflineDownloadCache.Span(0, 300, new File("a"))), 0, 299));
        assertFalse(OfflineDownloadCache.covers(spans(0, 100), 199, 250));
    }
}
