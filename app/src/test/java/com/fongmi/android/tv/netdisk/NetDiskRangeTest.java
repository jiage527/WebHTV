package com.fongmi.android.tv.netdisk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link NetDiskRange} 的解析规则。
 *
 * <p>这些用例值得写，是因为 Range 解析错了不会立刻报错，而是在用户拖进度条时才
 * 表现成「跳回开头」或「拖动没反应」，且很难看出是解析的问题。区间必须压到
 * 文件长度内，否则底层读接口会越界。
 */
public class NetDiskRangeTest {

    private static final long TOTAL = 1000;

    @Test
    public void noHeaderMeansWholeFileAndNotPartial() {
        NetDiskRange.Result result = NetDiskRange.parse(null, TOTAL);
        assertFalse(result.isValid());
        assertEquals(0, result.getStart());
        assertEquals(999, result.getEnd());
        assertFalse(result.isPartial(TOTAL));
    }

    @Test
    public void openEndedRangeRunsToEndOfFile() {
        NetDiskRange.Result result = NetDiskRange.parse("bytes=500-", TOTAL);
        assertTrue(result.isValid());
        assertEquals(500, result.getStart());
        assertEquals(999, result.getEnd());
        assertEquals(500, result.length());
        assertTrue(result.isPartial(TOTAL));
    }

    @Test
    public void closedRangeIsHonoured() {
        NetDiskRange.Result result = NetDiskRange.parse("bytes=100-199", TOTAL);
        assertEquals(100, result.getStart());
        assertEquals(199, result.getEnd());
        assertEquals(100, result.length());
        assertTrue(result.isPartial(TOTAL));
    }

    @Test
    public void endBeyondFileIsClamped() {
        NetDiskRange.Result result = NetDiskRange.parse("bytes=900-99999", TOTAL);
        assertEquals(900, result.getStart());
        assertEquals(999, result.getEnd());
    }

    @Test
    public void startAtOrBeyondEndIsUnsatisfiable() {
        // 必须回 416，而不是回 0 字节的 206：后者会让播放器以为文件提前结束。
        assertTrue(NetDiskRange.parse("bytes=1000-", TOTAL).isUnsatisfiable());
        assertTrue(NetDiskRange.parse("bytes=5000-6000", TOTAL).isUnsatisfiable());
    }

    @Test
    public void suffixRangeReturnsTail() {
        NetDiskRange.Result result = NetDiskRange.parse("bytes=-200", TOTAL);
        assertTrue(result.isValid());
        assertEquals(800, result.getStart());
        assertEquals(999, result.getEnd());
        assertTrue(result.isPartial(TOTAL));
    }

    @Test
    public void suffixLargerThanFileStartsAtZero() {
        NetDiskRange.Result result = NetDiskRange.parse("bytes=-5000", TOTAL);
        assertEquals(0, result.getStart());
        assertEquals(999, result.getEnd());
        assertFalse(result.isPartial(TOTAL));
    }

    @Test
    public void zeroSuffixIsUnsatisfiable() {
        assertTrue(NetDiskRange.parse("bytes=-0", TOTAL).isUnsatisfiable());
    }

    @Test
    public void reversedRangeIsUnsatisfiable() {
        assertTrue(NetDiskRange.parse("bytes=500-100", TOTAL).isUnsatisfiable());
    }

    @Test
    public void multiRangeFallsBackToWholeFile() {
        // 多段需要 multipart 响应体，播放器从不发这种请求；按 RFC 降级成整文件即可。
        NetDiskRange.Result result = NetDiskRange.parse("bytes=0-9,20-29", TOTAL);
        assertFalse(result.isValid());
        assertEquals(0, result.getStart());
        assertEquals(999, result.getEnd());
    }

    @Test
    public void malformedHeadersFallBackToWholeFile() {
        for (String header : new String[]{"", "items=0-9", "bytes=", "bytes=abc-def", "bytes=x-", "bytes=-abc"}) {
            NetDiskRange.Result result = NetDiskRange.parse(header, TOTAL);
            assertFalse("header=" + header, result.isValid());
            assertEquals("header=" + header, 0, result.getStart());
            assertEquals("header=" + header, 999, result.getEnd());
        }
    }

    @Test
    public void headerNameMatchingIsCaseInsensitive() {
        NetDiskRange.Result result = NetDiskRange.parse("BYTES=10-19", TOTAL);
        assertTrue(result.isValid());
        assertEquals(10, result.getStart());
        assertEquals(19, result.getEnd());
    }

    @Test
    public void toleratesSurroundingWhitespace() {
        NetDiskRange.Result result = NetDiskRange.parse("  bytes= 10 - 19 ", TOTAL);
        assertTrue(result.isValid());
        assertEquals(10, result.getStart());
        assertEquals(19, result.getEnd());
    }

    @Test
    public void unknownTotalStillAcceptsExplicitRange() {
        // 长度未知时（少数 WebDAV 服务端不报 Content-Length）仍要能按客户端给的区间读。
        NetDiskRange.Result result = NetDiskRange.parse("bytes=100-199", -1);
        assertTrue(result.isValid());
        assertEquals(100, result.getStart());
        assertEquals(199, result.getEnd());
        // 起点不为 0 就必须回 206：播放器靠这一点判断该源能不能拖进度条。
        assertTrue(result.isPartial(0));
    }

    @Test
    public void unknownTotalRangeFromZeroIsNotPartial() {
        // 起点为 0、长度又未知，整段就是全文件，回 200 即可。
        NetDiskRange.Result result = NetDiskRange.parse("bytes=0-", -1);
        assertTrue(result.isValid());
        assertFalse(result.isPartial(0));
    }

    @Test
    public void unknownTotalKeepsOpenEndedUpperBoundOpen() {
        NetDiskRange.Result result = NetDiskRange.parse("bytes=100-", -1);
        assertTrue(result.isValid());
        assertEquals(100, result.getStart());
        assertEquals(Long.MAX_VALUE, result.getEnd());
    }

    @Test
    public void contentRangeHeaderFormat() {
        assertEquals("bytes 0-999/1000", NetDiskRange.contentRange(0, 999, TOTAL));
        assertNull(NetDiskRange.contentRange(0, 9, -1));
        assertEquals("bytes */1000", NetDiskRange.unsatisfiedContentRange(TOTAL));
    }
}
