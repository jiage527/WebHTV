package com.fongmi.android.tv.netdisk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * {@link FtpReply} 的应答解析。
 *
 * <p>这些用例盯的是多行应答的续行规则：只读首行会让后续行被当成下一条命令的应答，
 * 从那一刻起整个控制连接的命令与应答永久错位，表现为「列目录拿到的是上一条的结果」。
 */
public class FtpReplyTest {

    @Test
    public void singleLineReply() {
        FtpReply reply = FtpReply.parse(List.of("230 Login successful."));
        assertEquals(230, reply.getCode());
        assertEquals("Login successful.", reply.getText());
        assertTrue(reply.isPositive());
    }

    @Test
    public void multilineReplyTakesCodeFromFirstLine() {
        FtpReply reply = FtpReply.parse(Arrays.asList(
                "211-Features:",
                " UTF8",
                " MDTM",
                "211 End"));
        assertEquals(211, reply.getCode());
        assertTrue(reply.getText().contains("End"));
        assertTrue(reply.isPositive());
    }

    @Test
    public void multilineIsNotMistakenForEnding() {
        // "211-..." 是续行开头，不是结束行；误判就会漏读后面的行。
        assertTrue(FtpReply.isMultilineStart("211-Features:"));
        assertFalse(FtpReply.isMultilineStart("211 End"));
        assertTrue(FtpReply.isMultilineEnd("211", "211 End"));
        assertFalse(FtpReply.isMultilineEnd("211", "211- More"));
    }

    @Test
    public void endLineMustMatchSameCode() {
        // 不同码的行不是结束行，否则会提前结束、把剩余续行留给下一条命令。
        assertFalse(FtpReply.isMultilineEnd("211", "230 Done"));
    }

    @Test
    public void codeClassification() {
        assertTrue(FtpReply.parse(List.of("150 Opening data connection")).isPreliminary());
        assertTrue(FtpReply.parse(List.of("226 Transfer complete")).isPositive());
        assertTrue(FtpReply.parse(List.of("331 Password required")).isType(3));
        assertTrue(FtpReply.parse(List.of("550 Not found")).isType(5));
        assertFalse(FtpReply.parse(List.of("550 Not found")).isPositive());
    }

    @Test
    public void malformedReplyHasZeroCode() {
        // 认不出三位码时给出 0，让调用方按失败处理，而不是当成成功。
        assertEquals(0, FtpReply.parse(List.of("garbage")).getCode());
        assertEquals(0, FtpReply.parse(null).getCode());
        assertEquals(0, FtpReply.parse(List.of()).getCode());
        assertFalse(FtpReply.hasCode("abc"));
        assertFalse(FtpReply.hasCode("21 x"));
        // 第 4 个字符必须是 '-' 或空格，否则不是合法应答开头。
        assertFalse(FtpReply.hasCode("211x"));
        assertTrue(FtpReply.hasCode("211 x"));
    }
}
