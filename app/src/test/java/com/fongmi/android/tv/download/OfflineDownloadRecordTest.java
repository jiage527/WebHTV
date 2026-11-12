package com.fongmi.android.tv.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 重点盯三件事：
 * <ul>
 *   <li>同一个地址必须永远得到同一个 id——id 同时是下载的 cacheKey，播放侧靠它认「这一集下好了」，
 *       换了 id 就等于同一集下两份、播放器一份都不认。</li>
 *   <li>不同地址必须得到不同 id——碰撞会让播放器读到别人的分片，画面串台比「下不了」难查得多。</li>
 *   <li>标题与请求头要能随记录来回走一遍 JSON，否则进程重启后列表变成一片空白、需要 Referer 的直链全 403。</li>
 * </ul>
 *
 * <p>纯 JVM，不需要 Android 环境。
 */
public class OfflineDownloadRecordTest {

    @Test
    public void idIsStableForTheSameUrl() {
        assertEquals(OfflineDownloadRecord.idFor("https://cdn.example.com/a/b.mp4"), OfflineDownloadRecord.idFor("https://cdn.example.com/a/b.mp4"));
        assertEquals(OfflineDownloadRecord.idFor("https://cdn.example.com/a/b.mp4"), OfflineDownloadRecord.idFor("https://cdn.example.com/a/b.mp4"));
    }

    @Test
    public void idIsPrefixedSoItIsRecognizableInLogs() {
        assertTrue(OfflineDownloadRecord.idFor("https://cdn.example.com/a.mp4").startsWith("od-"));
    }

    /** 地址只差一个字符也要算出不同 id：不同的剧集常常只差集数。 */
    @Test
    public void idDiffersForDifferentUrls() {
        String first = OfflineDownloadRecord.idFor("https://cdn.example.com/a/01.mp4");
        String second = OfflineDownloadRecord.idFor("https://cdn.example.com/a/02.mp4");
        assertFalse(first.equals(second));
        assertFalse(OfflineDownloadRecord.idFor("https://a.example.com/x.mp4").equals(OfflineDownloadRecord.idFor("https://b.example.com/x.mp4")));
        assertFalse(OfflineDownloadRecord.idFor("https://example.com/x.mp4?a=1").equals(OfflineDownloadRecord.idFor("https://example.com/x.mp4?a=2")));
    }

    /** 空格或大小写差异属于不同地址（服务端签名参数区分大小写），不能当成同一条。 */
    @Test
    public void idTreatsCaseAndWhitespaceAsSignificant() {
        assertFalse(OfflineDownloadRecord.idFor("https://example.com/A.mp4").equals(OfflineDownloadRecord.idFor("https://example.com/a.mp4")));
        assertFalse(OfflineDownloadRecord.idFor("https://example.com/a.mp4 ").equals(OfflineDownloadRecord.idFor("https://example.com/a.mp4")));
    }

    @Test
    public void idIsEmptyWhenUrlIsMissing() {
        assertEquals("", OfflineDownloadRecord.idFor(null));
        assertEquals("", OfflineDownloadRecord.idFor(""));
    }

    @Test
    public void createDerivesKindFromUrl() {
        assertEquals(OfflineDownloadKind.HLS, OfflineDownloadRecord.create("t", "https://cdn.example.com/i.m3u8", null, 1L).kind());
        assertEquals(OfflineDownloadKind.PROGRESSIVE, OfflineDownloadRecord.create("t", "https://cdn.example.com/a.mp4", null, 1L).kind());
        assertEquals(OfflineDownloadKind.UNKNOWN, OfflineDownloadRecord.create("t", "https://cdn.example.com/live?x=1", null, 1L).kind());
    }

    @Test
    public void createKeepsHeaderOrderAndDropsBlanks() {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Referer", "https://site.example.com/");
        headers.put("User-Agent", "okhttp");
        OfflineDownloadRecord record = OfflineDownloadRecord.create("剧名 第01集", "https://cdn.example.com/a.mp4", headers, 1700000000000L);
        assertEquals("剧名 第01集", record.title());
        assertEquals("https://cdn.example.com/a.mp4", record.url());
        assertEquals(1700000000000L, record.createdAt());
        assertEquals(2, record.headers().size());
    }

    /** 空值不能进 headers：带一个空 Referer 反而会让服务端判成非法请求。 */
    @Test
    public void sanitizeDropsEmptyNamesAndValues() {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Referer", "https://site.example.com/");
        headers.put("", "x");
        headers.put("X-Empty", "");
        headers.put("X-Blank", "   ");
        headers.put(null, "y");
        OfflineDownloadRecord record = OfflineDownloadRecord.create("t", "https://cdn.example.com/a.mp4", headers, 1L);
        assertEquals(1, record.headers().size());
        assertEquals("https://site.example.com/", record.headers().get("Referer"));
    }

    @Test
    public void jsonRoundTripKeepsEveryField() {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Referer", "https://site.example.com/");
        headers.put("User-Agent", "okhttp");
        OfflineDownloadRecord origin = OfflineDownloadRecord.create("剧名 第01集", "https://cdn.example.com/a/b.m3u8", headers, 1700000000000L);
        OfflineDownloadRecord copy = OfflineDownloadRecord.fromJson(origin.toJson().toString());
        assertEquals(origin.id(), copy.id());
        assertEquals(origin.title(), copy.title());
        assertEquals(origin.url(), copy.url());
        assertEquals(origin.kind(), copy.kind());
        assertEquals(origin.createdAt(), copy.createdAt());
        assertEquals(origin.headers(), copy.headers());
    }

    /** 没有请求头时不写空对象，老记录读回来也不该报错。 */
    @Test
    public void jsonOmitsHeadersWhenEmpty() {
        OfflineDownloadRecord record = OfflineDownloadRecord.create("t", "https://cdn.example.com/a.mp4", null, 1L);
        assertFalse(record.toJson().has("headers"));
        assertTrue(OfflineDownloadRecord.fromJson(record.toJson().toString()).headers().isEmpty());
    }

    @Test
    public void jsonRejectsBrokenInput() {
        assertEquals(null, OfflineDownloadRecord.fromJson((String) null));
        assertEquals(null, OfflineDownloadRecord.fromJson(""));
        assertEquals(null, OfflineDownloadRecord.fromJson("{not json"));
        assertEquals(null, OfflineDownloadRecord.fromJson("{\"title\":\"只有标题\"}"));
        assertEquals(null, OfflineDownloadRecord.fromJson("{\"id\":\"\",\"url\":\"https://a/b.mp4\"}"));
    }

    /** 未知 kind（旧版本写的或手改过的）不能把整条记录弄丢，退回 UNKNOWN。 */
    @Test
    public void jsonFallsBackToUnknownKind() {
        OfflineDownloadRecord record = OfflineDownloadRecord.fromJson("{\"id\":\"od-1\",\"url\":\"https://a/b.mp4\",\"kind\":\"SOMETHING_NEW\"}");
        assertEquals(OfflineDownloadKind.UNKNOWN, record.kind());
    }

    @Test
    public void displayTitleFallsBackToUrl() {
        assertEquals("https://cdn.example.com/a.mp4", OfflineDownloadRecord.create("", "https://cdn.example.com/a.mp4", null, 1L).displayTitle());
        assertEquals("剧名", OfflineDownloadRecord.create("剧名", "https://cdn.example.com/a.mp4", null, 1L).displayTitle());
    }

    @Test
    public void isValidRequiresIdAndUrl() {
        assertTrue(OfflineDownloadRecord.create("t", "https://cdn.example.com/a.mp4", null, 1L).isValid());
        assertFalse(new OfflineDownloadRecord("", "t", "https://cdn.example.com/a.mp4", OfflineDownloadKind.PROGRESSIVE, 1L, null).isValid());
        assertFalse(new OfflineDownloadRecord("od-1", "t", "", OfflineDownloadKind.PROGRESSIVE, 1L, null).isValid());
    }

    /** 按 cacheKey 命中时用前缀匹配：HLS 分片 key 常常是「清单 key + 分片序号」。 */
    @Test
    public void headersMatchByCacheKeyPrefix() {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Referer", "https://site.example.com/");
        OfflineDownloadRecord record = OfflineDownloadRecord.create("t", "https://cdn.example.com/a/i.m3u8", headers, 1L);
        java.util.List<OfflineDownloadRecord> records = java.util.List.of(record);
        assertEquals(headers, OfflineDownloadRecord.headersFor("https://cdn.example.com/a/seg1.ts", record.id() + ":seg1", records));
        assertEquals(headers, OfflineDownloadRecord.headersFor("https://cdn.example.com/a/seg1.ts", record.id(), records));
    }

    /** 分片地址与清单不同路径、也没有我们的 key 时，只能靠同源兜住。 */
    @Test
    public void headersMatchByOriginWhenKeyIsMissing() {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Referer", "https://site.example.com/");
        OfflineDownloadRecord record = OfflineDownloadRecord.create("t", "https://cdn.example.com/a/i.m3u8", headers, 1L);
        java.util.List<OfflineDownloadRecord> records = java.util.List.of(record);
        assertEquals(headers, OfflineDownloadRecord.headersFor("https://cdn.example.com/hls/seg-9.ts", null, records));
        assertEquals(headers, OfflineDownloadRecord.headersFor("https://cdn.example.com/hls/seg-9.ts", "", records));
    }

    /** 别的站点的分片不能拿到这份 Referer：把 A 站的 Referer 发给 B 站会被判成盗链。 */
    @Test
    public void headersDoNotLeakToAnotherOrigin() {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Referer", "https://site.example.com/");
        OfflineDownloadRecord record = OfflineDownloadRecord.create("t", "https://cdn.example.com/a/i.m3u8", headers, 1L);
        java.util.List<OfflineDownloadRecord> records = java.util.List.of(record);
        assertTrue(OfflineDownloadRecord.headersFor("https://other.example.net/x.ts", null, records).isEmpty());
    }

    @Test
    public void headersForHandlesMissingInputs() {
        java.util.Map<String, String> headers = java.util.Map.of("Referer", "https://site.example.com/");
        OfflineDownloadRecord record = OfflineDownloadRecord.create("t", "https://cdn.example.com/a/i.m3u8", headers, 1L);
        assertTrue(OfflineDownloadRecord.headersFor(null, null, java.util.List.of(record)).isEmpty());
        assertTrue(OfflineDownloadRecord.headersFor("https://cdn.example.com/a/i.m3u8", null, null).isEmpty());
        assertTrue(OfflineDownloadRecord.headersFor("https://cdn.example.com/a/i.m3u8", null, java.util.List.of()).isEmpty());
    }

    /** 同源判定要看 scheme + host + port；端口不同就不是同一个源。 */
    @Test
    public void originKeepsSchemeHostAndPort() {
        assertEquals("https://cdn.example.com", OfflineDownloadRecord.originOf("https://cdn.example.com/a/b.ts"));
        assertEquals("https://cdn.example.com:8443", OfflineDownloadRecord.originOf("https://cdn.example.com:8443/a/b.ts"));
        assertEquals("http://cdn.example.com", OfflineDownloadRecord.originOf("http://cdn.example.com"));
        assertEquals(null, OfflineDownloadRecord.originOf("not a url"));
        assertEquals(null, OfflineDownloadRecord.originOf(null));
    }
}
