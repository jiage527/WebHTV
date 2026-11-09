package com.fongmi.android.tv.webdav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 路径解析与安全校验的测试。
 *
 * <p><b>为什么这组测试最要紧。</b>WebDAV 服务端把本机文件开放给局域网内任何设备，
 * 并且默认支持写入。这一层只要有漏洞，别人就能读到或写到共享目录之外的东西
 * （配置、数据库、口令文件）。所以把已知的逃逸手法逐条钉成断言。
 */
public class WebDavPathTest {

    // ---------------------------------------------------------------- 常规

    @Test
    public void rootStaysEmpty() {
        assertEquals("", WebDavPath.normalize("/"));
        assertEquals("", WebDavPath.normalize(""));
    }

    @Test
    public void simplePath() {
        // normalize 拿到的是「已去掉 /dav 前缀」的路径（前缀由 WebDavServer.stripPrefix 处理）。
        assertEquals("Movies/a.mp4", WebDavPath.normalize("/Movies/a.mp4"));
    }

    @Test
    public void leadingAndTrailingSlashesCollapse() {
        assertEquals("a/b", WebDavPath.normalize("///a///b///"));
    }

    @Test
    public void dotsAreRemoved() {
        assertEquals("a/b", WebDavPath.normalize("/a/./b/."));
    }

    // ---------------------------------------------------------------- 逃逸

    /** 「..」压到根为止，绝不越过根目录。 */
    @Test
    public void parentNeverEscapesRoot() {
        assertEquals("etc", WebDavPath.normalize("/../../etc"));
        assertEquals("", WebDavPath.normalize("/.."));
        assertEquals("a/b", WebDavPath.normalize("/a/../../a/b"));
    }

    @Test
    public void internalParentStillWorks() {
        assertEquals("b", WebDavPath.normalize("/a/../b"));
    }

    /**
     * 编码后的「..」必须照样被识别。
     *
     * <p>若先整体解码再切段，{@code %2E%2E} 就会变回 {@code ..} 并生效；逐段解码则
     * 它在自己那一段里被认出来。
     */
    @Test
    public void encodedParentIsRecognized() {
        assertEquals("etc", WebDavPath.normalize("/%2E%2E/%2E%2E/etc"));
        assertEquals("", WebDavPath.normalize("/%2e%2e"));
        // 整个合成一段时解码出 "./../etc"，段内含分隔符 → 判为逃逸而拒绝，
        // 这与「逐段解码后分隔符不会生效」是同一结论，拒绝比压到根更保守。
        assertNull(WebDavPath.normalize("/..%2F..%2Fetc"));
    }

    /**
     * 双重编码的分隔符必须被拒绝。
     *
     * <p>{@code %252F} 解码一次是 {@code %2F}，再解一次是 {@code /}。逐段解码只解一次，
     * 段里剩下 {@code %2F}；这类段绝不是合法文件名，直接判非法。
     */
    @Test
    public void doubleEncodedSeparatorIsRejected() {
        assertNull(WebDavPath.normalize("/a%252F..%252F..%252Fetc"));
        assertNull(WebDavPath.normalize("/a%252Fb"));
    }

    /** 反斜杠也当分隔符：Windows 客户端可能发这种路径。 */
    @Test
    public void backslashIsTreatedAsSeparator() {
        assertEquals("etc", WebDavPath.normalize("/..\\..\\etc"));
        assertEquals("a/b", WebDavPath.normalize("/a\\b"));
    }

    /** 编码后的反斜杠同样不能绕过。 */
    @Test
    public void encodedBackslashIsRejected() {
        assertNull(WebDavPath.normalize("/a%5C..%5C..%5Cetc"));
    }

    @Test
    public void controlCharactersAreRejected() {
        assertNull(WebDavPath.normalize("/a%00b"));
        assertNull(WebDavPath.normalize("/a%0Ab"));
    }

    /** 查询串与片段不属于路径，必须切掉。 */
    @Test
    public void queryAndFragmentAreCut() {
        assertEquals("a/b", WebDavPath.normalize("/a/b?x=1"));
        assertEquals("a/b", WebDavPath.normalize("/a/b#frag"));
    }

    /** 真实文件名里的合法字符不能被误伤。 */
    @Test
    public void legitimateNamesSurvive() {
        assertEquals("My Movie (2024) [1080p].mp4", WebDavPath.normalize("/My%20Movie%20(2024)%20%5B1080p%5D.mp4"));
        assertEquals("中文目录/电影.mkv", WebDavPath.normalize("/%E4%B8%AD%E6%96%87%E7%9B%AE%E5%BD%95/%E7%94%B5%E5%BD%B1.mkv"));
        assertEquals("a+b", WebDavPath.normalize("/a+b"));
        // 单个 % 不是合法转义，应原样保留（真实文件名里确实可能有 %）。
        assertEquals("100%.mp4", WebDavPath.normalize("/100%.mp4"));
    }

    /** 名字里含「..」但不是独立段的不能被误判成上级目录。 */
    @Test
    public void dotsInsideNameAreNotParent() {
        assertEquals("a..b", WebDavPath.normalize("/a..b"));
        assertEquals("..hidden", WebDavPath.normalize("/..hidden"));
    }

    // ---------------------------------------------------------------- 切分与还原

    @Test
    public void segmentsAndJoinRoundTrip() {
        List<String> segments = WebDavPath.segmentsOf("a/b/c");
        assertEquals(3, segments.size());
        assertEquals("a", segments.get(0));
        assertEquals("c", segments.get(2));
        assertEquals("a/b/c", WebDavPath.join(segments));
    }

    @Test
    public void parentAndName() {
        assertEquals("a/b", WebDavPath.parent("a/b/c.mp4"));
        assertEquals("c.mp4", WebDavPath.name("a/b/c.mp4"));
        assertEquals("", WebDavPath.parent("c.mp4"));
        assertEquals("c.mp4", WebDavPath.name("c.mp4"));
        assertEquals("", WebDavPath.parent(""));
        assertEquals("", WebDavPath.name(""));
    }

    // ---------------------------------------------------------------- 生成 href

    /** href 里的空格、中文、# 必须转义，否则客户端会解析出别的路径。 */
    @Test
    public void encodeEscapesSpecialCharacters() {
        assertEquals("/a/b", WebDavPath.encode("a/b"));
        assertEquals("/My%20Movie.mp4", WebDavPath.encode("My Movie.mp4"));
        assertEquals("/a%23b", WebDavPath.encode("a#b"));
        assertEquals("/a%3Fb", WebDavPath.encode("a?b"));
        assertEquals("/", WebDavPath.encode(""));
    }

    @Test
    public void encodeLeavesUnreservedAlone() {
        assertEquals("/a-b_c.d~e", WebDavPath.encode("a-b_c.d~e"));
    }

    /** 编码再解析必须回到原值，否则生成 href 与解析请求会不一致。 */
    @Test
    public void encodeThenDecodeRoundTrips() {
        String name = "My Movie (2024) #1 [1080p].mp4";
        assertEquals(name, WebDavPath.decodeSegment(WebDavPath.encodeSegment(name)));
    }

    @Test
    public void decodeSegmentHandlesBrokenEscapes() {
        assertEquals("abc", WebDavPath.decodeSegment("abc"));
        // 结尾的单个 % 原样保留。
        assertEquals("%", WebDavPath.decodeSegment("%"));
        assertEquals("%zz", WebDavPath.decodeSegment("%zz"));
        assertEquals("a", WebDavPath.decodeSegment("%61"));
    }

    // ---------------------------------------------------------------- 显示与排序

    @Test
    public void hiddenNamesAreNotVisible() {
        assertFalse(WebDavPath.isVisibleName(".nomedia"));
        assertFalse(WebDavPath.isVisibleName("."));
        assertFalse(WebDavPath.isVisibleName(".."));
        assertFalse(WebDavPath.isVisibleName(""));
        assertTrue(WebDavPath.isVisibleName("movie.mp4"));
    }

    @Test
    public void directoriesSortFirstThenByName() {
        assertTrue(WebDavPath.compare(true, "z", false, "a") < 0);
        assertTrue(WebDavPath.compare(false, "a", true, "z") > 0);
        assertEquals(0, WebDavPath.compare(false, "Movie.mp4", false, "movie.mp4"));
        assertTrue(WebDavPath.compare(false, "a.mp4", false, "b.mp4") < 0);
    }
}
