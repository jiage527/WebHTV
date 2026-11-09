package com.fongmi.android.tv.netdisk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link FtpPassiveAddress} 的被动模式地址解析。
 *
 * <p>这些用例盯的是端口的算法：PASV 给的是两个字节，端口要算成 {@code p1 * 256 + p2}。
 * 直接取最后一个数字是错的，会连到一个完全不同的端口，表现为「登录成功但列目录卡死」。
 */
public class FtpPassiveAddressTest {

    @Test
    public void computesPortFromTwoBytes() {
        FtpPassiveAddress address = FtpPassiveAddress.fromPasv("227 Entering Passive Mode (192,168,1,10,195,149).");
        assertEquals("192.168.1.10", address.getHost());
        // 195 * 256 + 149 = 50069
        assertEquals(195 * 256 + 149, address.getPort());
        assertTrue(address.isValid());
    }

    @Test
    public void handlesSpaceSeparatedNumbers() {
        // 有些实现用空格而不是逗号分隔，按逗号 split 会解析失败。
        FtpPassiveAddress address = FtpPassiveAddress.fromPasv("227 Entering Passive Mode (10 0 0 5 4 1)");
        assertEquals("10.0.0.5", address.getHost());
        assertEquals(4 * 256 + 1, address.getPort());
    }

    @Test
    public void ignoresCodeNumberOutsideParens() {
        // 227 这个码本身也是数字，取最前六个数就会把它算进去。
        FtpPassiveAddress address = FtpPassiveAddress.fromPasv("227 Entering Passive Mode (10,0,0,5,4,1)");
        assertEquals("10.0.0.5", address.getHost());
        assertEquals(4 * 256 + 1, address.getPort());
    }

    @Test
    public void rejectsIncompleteTuple() {
        assertFalse(FtpPassiveAddress.fromPasv("227 Entering Passive Mode (10,0,0,5,4)").isValid());
        assertFalse(FtpPassiveAddress.fromPasv("227 broken").isValid());
        assertFalse(FtpPassiveAddress.fromPasv(null).isValid());
    }

    @Test
    public void rejectsOutOfRangeBytes() {
        // 超过 255 说明这行不是 PASV 元组，猜一个端口出来只会连到别人的服务。
        assertFalse(FtpPassiveAddress.fromPasv("227 (999,0,0,5,4,1)").isValid());
    }

    @Test
    public void parsesEpsvPort() {
        assertEquals(6446, FtpPassiveAddress.portFromEpsv("229 Entering Extended Passive Mode (|||6446|)"));
        assertEquals(50069, FtpPassiveAddress.portFromEpsv("229 Entering Extended Passive Mode (|||50069|)"));
    }

    @Test
    public void rejectsBadEpsv() {
        assertEquals(0, FtpPassiveAddress.portFromEpsv(null));
        assertEquals(0, FtpPassiveAddress.portFromEpsv("229 no digits here"));
        assertEquals(0, FtpPassiveAddress.portFromEpsv("229 (|||70000|)"));
    }

    @Test
    public void zeroPortIsInvalid() {
        assertFalse(FtpPassiveAddress.fromPasv("227 (10,0,0,5,0,0)").isValid());
    }
}
