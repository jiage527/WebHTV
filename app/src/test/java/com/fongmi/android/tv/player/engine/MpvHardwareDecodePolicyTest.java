package com.fongmi.android.tv.player.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public final class MpvHardwareDecodePolicyTest {

    @Test
    public void hardwareModeKeepsMpvDefaultSoftwareFallback() {
        assertEquals("hwdec-software-fallback",
                MpvPlayerEngine.HWDEC_SOFTWARE_FALLBACK_OPTION);
        // "no" would map to INT_MAX and turn any hardware decode failure into a
        // permanent dec=0 out=0 stall, because no software decoder takes over.
        assertEquals("3", MpvPlayerEngine.hardwareDecodeSoftwareFallbackOption(PlayerEngine.HARD));
        assertNotEquals("no", MpvPlayerEngine.hardwareDecodeSoftwareFallbackOption(PlayerEngine.HARD));
    }

    @Test
    public void explicitSoftwareModeAllowsSoftwareDecoder() {
        assertEquals("yes", MpvPlayerEngine.hardwareDecodeSoftwareFallbackOption(PlayerEngine.SOFT));
    }
}
