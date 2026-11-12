package com.fongmi.android.tv.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 重点盯三件事：
 * <ul>
 *   <li>落盘再读回来必须一字不差——记录表丢了就等于所有请求头都丢了，用户此后的下载全 403。</li>
 *   <li>文件写坏/半截不能把整张表弄崩：单条读坏只丢那一条，其余照旧。</li>
 *   <li>回查请求头要走通「按 id / 按同源」两条路，且不能把 A 站的 Referer 发给 B 站。</li>
 * </ul>
 *
 * <p>用临时目录落盘，纯 JVM。
 */
public class OfflineDownloadRegistryTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void roundTripsThroughDisk() throws IOException {
        File file = new File(folder.getRoot(), "registry.json");
        OfflineDownloadRegistry first = new OfflineDownloadRegistry(file);
        first.put(record("剧A 第01集", "https://cdn.example.com/a/01.mp4", headers("Referer", "https://site.example.com/")));
        first.put(record("剧B 第02集", "https://cdn.example.com/b/02.m3u8", headers("User-Agent", "okhttp")));
        first.save();
        assertTrue(file.length() > 0);

        OfflineDownloadRegistry second = new OfflineDownloadRegistry(file);
        assertEquals(2, second.size());
        String id = OfflineDownloadRecord.idFor("https://cdn.example.com/a/01.mp4");
        assertEquals("剧A 第01集", second.titleOf(id, "兜底"));
        assertEquals("https://site.example.com/", second.headersFor("https://cdn.example.com/a/01.mp4", id).get("Referer"));
    }

    @Test
    public void startsEmptyWhenFileIsMissingOrBlank() throws IOException {
        OfflineDownloadRegistry missing = new OfflineDownloadRegistry(new File(folder.getRoot(), "nope.json"));
        assertTrue(missing.isEmpty());
        assertEquals(0, missing.size());
        File blank = folder.newFile("blank.json");
        assertTrue(new OfflineDownloadRegistry(blank).isEmpty());
        assertTrue(new OfflineDownloadRegistry(null).isEmpty());
    }

    /** 单条读坏只丢那一条：已经进表的保留，整份读坏也只是退回空表，绝不能抛。 */
    @Test
    public void brokenEntryOnlyDropsItself() throws IOException {
        File file = folder.newFile("broken.json");
        String good = "{\"id\":\"od-good\",\"url\":\"https://cdn.example.com/a.mp4\",\"kind\":\"PROGRESSIVE\"}";
        String bad = "{\"title\":\"没有 id 和 url\"}";
        Files.write(file.toPath(), ("[" + good + "," + bad + "]").getBytes(StandardCharsets.UTF_8));
        OfflineDownloadRegistry registry = new OfflineDownloadRegistry(file);
        assertEquals(1, registry.size());
        assertEquals("https://cdn.example.com/a.mp4", registry.get("od-good").url());
    }

    @Test
    public void garbageFileDoesNotThrow() throws IOException {
        File file = folder.newFile("garbage.json");
        Files.write(file.toPath(), "{not json at all".getBytes(StandardCharsets.UTF_8));
        assertTrue(new OfflineDownloadRegistry(file).isEmpty());
        File object = folder.newFile("object.json");
        Files.write(object.toPath(), "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        assertTrue(new OfflineDownloadRegistry(object).isEmpty());
    }

    @Test
    public void putRejectsInvalidRecords() {
        OfflineDownloadRegistry registry = new OfflineDownloadRegistry(null);
        registry.put(null);
        registry.put(new OfflineDownloadRecord("", "t", "https://cdn.example.com/a.mp4", OfflineDownloadKind.PROGRESSIVE, 1L, null));
        registry.put(new OfflineDownloadRecord("od-x", "t", "", OfflineDownloadKind.PROGRESSIVE, 1L, null));
        assertTrue(registry.isEmpty());
    }

    /** 同一个 id 再放一次是覆盖，不是追加两条（重下一遍不会让列表出现两行）。 */
    @Test
    public void puttingSameIdReplacesInsteadOfDuplicating() {
        OfflineDownloadRegistry registry = new OfflineDownloadRegistry(null);
        String url = "https://cdn.example.com/a.mp4";
        registry.put(record("旧标题", url, null));
        registry.put(record("新标题", url, null));
        assertEquals(1, registry.size());
        assertEquals("新标题", registry.titleOf(OfflineDownloadRecord.idFor(url), "兜底"));
    }

    @Test
    public void removeAndRetainOnlyKeepWhatIsAsked() {
        OfflineDownloadRegistry registry = new OfflineDownloadRegistry(null);
        registry.put(record("a", "https://cdn.example.com/a.mp4", null));
        registry.put(record("b", "https://cdn.example.com/b.mp4", null));
        registry.put(record("c", "https://cdn.example.com/c.mp4", null));
        registry.remove(OfflineDownloadRecord.idFor("https://cdn.example.com/b.mp4"));
        assertEquals(2, registry.size());
        assertNull(registry.get(OfflineDownloadRecord.idFor("https://cdn.example.com/b.mp4")));
        registry.retain(List.of(OfflineDownloadRecord.idFor("https://cdn.example.com/c.mp4")));
        assertEquals(1, registry.size());
        assertFalse(registry.isEmpty());
        registry.remove(null);
        registry.remove("");
        registry.retain(null);
        assertEquals(1, registry.size());
    }

    /** 标题兜底：记录在就用自己的标题，没有标题或整条不在才用兜底值。 */
    @Test
    public void titleFallsBackWhenRecordOrTitleIsMissing() {
        OfflineDownloadRegistry registry = new OfflineDownloadRegistry(null);
        String url = "https://cdn.example.com/a.mp4";
        registry.put(record("", url, null));
        assertEquals("兜底", registry.titleOf(OfflineDownloadRecord.idFor(url), "兜底"));
        assertEquals("", registry.titleOf(OfflineDownloadRecord.idFor("https://cdn.example.com/z.mp4"), null));
        assertEquals("", registry.titleOf(null, null));
        assertEquals("", registry.titleOf("", ""));
    }

    /** 请求头两条路都要通：cacheKey 前缀（HLS 分片）与同源兜底。 */
    @Test
    public void headersResolveByKeyThenByOrigin() {
        OfflineDownloadRegistry registry = new OfflineDownloadRegistry(null);
        String url = "https://cdn.example.com/a/i.m3u8";
        registry.put(record("t", url, headers("Referer", "https://site.example.com/")));
        String id = OfflineDownloadRecord.idFor(url);
        assertEquals("https://site.example.com/", registry.headersFor("https://cdn.example.com/a/seg1.ts", id + ":seg1").get("Referer"));
        assertEquals("https://site.example.com/", registry.headersFor("https://cdn.example.com/other/seg1.ts", null).get("Referer"));
        assertTrue(registry.headersFor("https://elsewhere.example.net/x.ts", null).isEmpty());
        assertTrue(registry.headersFor(null, null).isEmpty());
    }

    /** 排序：新的在前，同刻按 id，保证面板刷新时行序稳定。 */
    @Test
    public void sortedPutsNewestFirstAndIsStable() {
        OfflineDownloadRegistry registry = new OfflineDownloadRegistry(null);
        registry.put(new OfflineDownloadRecord("od-b", "b", "https://cdn.example.com/b.mp4", OfflineDownloadKind.PROGRESSIVE, 100L, null));
        registry.put(new OfflineDownloadRecord("od-a", "a", "https://cdn.example.com/a.mp4", OfflineDownloadKind.PROGRESSIVE, 100L, null));
        registry.put(new OfflineDownloadRecord("od-c", "c", "https://cdn.example.com/c.mp4", OfflineDownloadKind.PROGRESSIVE, 300L, null));
        List<OfflineDownloadRecord> sorted = registry.sorted();
        assertEquals(List.of("od-c", "od-a", "od-b"), List.of(sorted.get(0).id(), sorted.get(1).id(), sorted.get(2).id()));
    }

    /** snapshot 是副本：调用方改它不该影响表（否则面板一次误操作就把记录删了）。 */
    @Test
    public void snapshotIsADetachedCopy() {
        OfflineDownloadRegistry registry = new OfflineDownloadRegistry(null);
        registry.put(record("a", "https://cdn.example.com/a.mp4", null));
        List<OfflineDownloadRecord> copy = registry.snapshot();
        copy.clear();
        assertEquals(1, registry.size());
    }

    /** 文件所在目录不存在时要自己建出来，否则第一次下载就写不进记录表。 */
    @Test
    public void saveCreatesMissingParentDirectory() {
        File file = new File(new File(folder.getRoot(), "deep/dir"), "registry.json");
        OfflineDownloadRegistry registry = new OfflineDownloadRegistry(file);
        registry.put(record("a", "https://cdn.example.com/a.mp4", null));
        registry.save();
        assertTrue(file.exists());
        assertEquals(1, new OfflineDownloadRegistry(file).size());
    }

    private static OfflineDownloadRecord record(String title, String url, Map<String, String> headers) {
        return OfflineDownloadRecord.create(title, url, headers, 1700000000000L);
    }

    private static Map<String, String> headers(String name, String value) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(name, value);
        return headers;
    }
}
