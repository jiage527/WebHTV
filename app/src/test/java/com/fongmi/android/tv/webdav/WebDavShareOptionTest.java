package com.fongmi.android.tv.webdav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 共享开关三个状态与下标之间的映射。
 *
 * <p>本次故障的实质是「下标 ↔ 状态」这层对应关系没有任何测试盯着，而它一旦错位
 * 就表现为「点确定没有变化」。这里把它钉住。
 */
public class WebDavShareOptionTest {

    @Test
    public void offIsTheFirstOptionSoTheDialogOpensOnItWhenSharingIsOff() {
        assertEquals(0, WebDavShareOption.OFF.index());
        assertEquals(WebDavShareOption.OFF, WebDavShareOption.of(0));
    }

    @Test
    public void everyOptionRoundTripsThroughItsIndex() {
        for (WebDavShareOption option : WebDavShareOption.values()) {
            assertEquals(option, WebDavShareOption.of(option.index()));
        }
    }

    @Test
    public void disabledSharingOpensOnOffRatherThanOnAGuess() {
        assertEquals(WebDavShareOption.OFF, WebDavShareOption.preselect(false, false));
        // 关着的时候写权限不该有意义，传 true 也只能落到 OFF。
        assertEquals(WebDavShareOption.OFF, WebDavShareOption.preselect(false, true));
    }

    @Test
    public void anEnabledAccountShowsWhatItActuallyIs() {
        assertEquals(WebDavShareOption.READ_ONLY, WebDavShareOption.preselect(true, false));
        assertEquals(WebDavShareOption.WRITABLE, WebDavShareOption.preselect(true, true));
    }

    @Test
    public void onlyOffMeansTheServerStaysClosed() {
        assertFalse(WebDavShareOption.OFF.allowed());
        assertTrue(WebDavShareOption.READ_ONLY.allowed());
        assertTrue(WebDavShareOption.WRITABLE.allowed());
    }

    @Test
    public void onlyWritableLetsTheOtherSideChangeFiles() {
        assertFalse(WebDavShareOption.OFF.writable());
        assertFalse(WebDavShareOption.READ_ONLY.writable());
        assertTrue(WebDavShareOption.WRITABLE.writable());
    }

    @Test
    public void anUnknownIndexFallsBackToStayingClosed() {
        assertFalse(WebDavShareOption.of(-1).allowed());
        assertFalse(WebDavShareOption.of(99).allowed());
        assertEquals(WebDavShareOption.OFF, WebDavShareOption.of(-1));
        assertEquals(WebDavShareOption.OFF, WebDavShareOption.of(99));
    }

    @Test
    public void thereAreExactlyThreeStates() {
        assertEquals(3, WebDavShareOption.values().length);
    }
}
