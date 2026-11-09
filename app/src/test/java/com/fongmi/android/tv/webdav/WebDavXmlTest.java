package com.fongmi.android.tv.webdav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * WebDAV 响应的 XML 与时间格式测试。
 *
 * <p><b>为什么这层也要测。</b>响应体一旦不是合法 XML，客户端会直接报「无法连接」，
 * 而真正的原因（某个文件名里的 {@code &}）极难从现象看出来。时间格式不标准时
 * 表现更隐蔽：文件能列出来，但全部显示成同一时间或 1970 年。
 */
public class WebDavXmlTest {

    // ---------------------------------------------------------------- 转义

    @Test
    public void escapesXmlSpecialCharacters() {
        assertEquals("Tom &amp; Jerry.mp4", WebDavXml.escape("Tom & Jerry.mp4"));
        assertEquals("&lt;a&gt;", WebDavXml.escape("<a>"));
        assertEquals("&quot;q&quot;", WebDavXml.escape("\"q\""));
        assertEquals("&apos;s&apos;", WebDavXml.escape("'s'"));
    }

    /**
     * 控制字符会让整个 XML 解析失败（XML 1.0 不允许），必须丢掉。
     *
     * <p>文件名理论上不含控制字符，但一旦出现，丢掉一个字符远好过整份响应报废。
     */
    @Test
    public void dropsIllegalControlCharacters() {
        assertEquals("ab", WebDavXml.escape("a\u0000b"));
        assertEquals("ab", WebDavXml.escape("a\u0007b"));
        // tab 与换行是 XML 允许的，应当保留。
        assertEquals("a\tb", WebDavXml.escape("a\tb"));
        assertEquals("a\nb", WebDavXml.escape("a\nb"));
    }

    @Test
    public void escapeHandlesNull() {
        assertEquals("", WebDavXml.escape(null));
    }

    // ---------------------------------------------------------------- 响应条目

    @Test
    public void fileEntryHasSizeAndType() {
        String xml = WebDavXml.response("/dav/a.mp4", "a.mp4", false, 12345, 0);
        assertTrue(xml.contains("<D:href>/dav/a.mp4</D:href>"));
        assertTrue(xml.contains("<D:getcontentlength>12345</D:getcontentlength>"));
        assertTrue(xml.contains("video/mp4"));
        // 文件不是 collection，resourcetype 必须为空元素。
        assertTrue(xml.contains("<D:resourcetype></D:resourcetype>"));
        assertFalse(xml.contains("<D:collection/>"));
    }

    @Test
    public void directoryEntryIsCollection() {
        String xml = WebDavXml.response("/dav/Movies/", "Movies", true, 0, 0);
        assertTrue(xml.contains("<D:collection/>"));
        assertTrue(xml.contains("<D:href>/dav/Movies/</D:href>"));
        // 目录不应有长度与类型。
        assertFalse(xml.contains("getcontentlength"));
        assertFalse(xml.contains("getcontenttype"));
    }

    @Test
    public void displayNameIsAlwaysPresent() {
        String xml = WebDavXml.response("/dav/a.mp4", "a.mp4", false, 1, 0);
        assertTrue(xml.contains("<D:displayname>a.mp4</D:displayname>"));
    }

    /** 文件名里的 & 必须转义成实体，否则整份 XML 非法。 */
    @Test
    public void entryEscapesNameAndHref() {
        String xml = WebDavXml.response("/dav/Tom%20%26%20Jerry.mp4", "Tom & Jerry.mp4", false, 1, 0);
        assertTrue(xml.contains("Tom &amp; Jerry.mp4"));
        assertFalse(xml.contains("Tom & Jerry.mp4"));
    }

    @Test
    public void modifiedTimeIsOmittedWhenUnknown() {
        assertFalse(WebDavXml.response("/dav/a.mp4", "a.mp4", false, 1, 0).contains("getlastmodified"));
        assertFalse(WebDavXml.response("/dav/a.mp4", "a.mp4", false, 1, -1).contains("getlastmodified"));
        assertTrue(WebDavXml.response("/dav/a.mp4", "a.mp4", false, 1, 1700000000000L).contains("getlastmodified"));
    }

    /** supportedlock 缺失会让部分客户端认为服务端能力未知而拒绝写入。 */
    @Test
    public void declaresSupportedLock() {
        assertTrue(WebDavXml.response("/dav/a.mp4", "a.mp4", false, 1, 0).contains("supportedlock"));
    }

    // ---------------------------------------------------------------- multistatus

    @Test
    public void multistatusWrapsEntries() {
        String xml = WebDavXml.multistatus(List.of(
                WebDavXml.response("/dav/", "WebDAV", true, 0, 0),
                WebDavXml.response("/dav/a.mp4", "a.mp4", false, 10, 0)));
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>"));
        assertTrue(xml.contains("<D:multistatus xmlns:D=\"DAV:\">"));
        assertTrue(xml.endsWith("</D:multistatus>"));
        // 两个条目都要在。
        assertEquals(2, countOccurrences(xml, "<D:response>"));
        assertEquals(2, countOccurrences(xml, "</D:response>"));
    }

    @Test
    public void multistatusHandlesNull() {
        String xml = WebDavXml.multistatus(null);
        assertTrue(xml.contains("<D:multistatus"));
        assertTrue(xml.contains("</D:multistatus>"));
    }

    // ---------------------------------------------------------------- 时间格式

    /**
     * 必须是 RFC 1123 的固定 GMT 格式。
     *
     * <p>若用默认 Locale，某些地区会输出本地化的星期/月名，客户端解析不了，
     * 表现为「所有文件都没有修改时间」。
     */
    @Test
    public void httpDateUsesRfc1123() {
        // 2026-11-09 00:00:00 UTC = 1794182400
        assertEquals("Mon, 09 Nov 2026 00:00:00 GMT", WebDavXml.httpDate(1794182400000L));
    }

    @Test
    public void httpDateIsAlwaysGmtRegardlessOfDefaultZone() {
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
            assertEquals("Mon, 09 Nov 2026 00:00:00 GMT", WebDavXml.httpDate(1794182400000L));
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }

    // ---------------------------------------------------------------- MIME

    @Test
    public void mimeByExtension() {
        assertEquals("video/mp4", WebDavXml.mimeOf("a.mp4"));
        assertEquals("video/x-matroska", WebDavXml.mimeOf("a.MKV"));
        assertEquals("video/mp2t", WebDavXml.mimeOf("a.ts"));
        assertEquals("audio/flac", WebDavXml.mimeOf("a.flac"));
        assertEquals("application/x-subrip", WebDavXml.mimeOf("a.srt"));
        assertEquals("text/x-ssa", WebDavXml.mimeOf("a.ass"));
    }

    /** 未知类型回 octet-stream，让客户端当二进制处理而不是猜成可能出错的文本类型。 */
    @Test
    public void unknownMimeIsOctetStream() {
        assertEquals("application/octet-stream", WebDavXml.mimeOf("a.xyz"));
        assertEquals("application/octet-stream", WebDavXml.mimeOf("noextension"));
        assertEquals("application/octet-stream", WebDavXml.mimeOf("trailing."));
        assertEquals("application/octet-stream", WebDavXml.mimeOf(null));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
