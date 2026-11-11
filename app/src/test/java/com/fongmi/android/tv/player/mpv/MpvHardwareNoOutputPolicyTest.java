package com.fongmi.android.tv.player.mpv;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MpvHardwareNoOutputPolicyTest {

    @Test
    public void engagedHardwareWithoutFrameFallsBackToSoftware() {
        assertTrue(MpvHardwareNoOutputPolicy.shouldRetryWithSoftware(
                true, false, "mediacodec", 100, false));
    }

    @Test
    public void engagedCopyHardwareWithoutFrameFallsBackToSoftware() {
        assertTrue(MpvHardwareNoOutputPolicy.shouldRetryWithSoftware(
                true, false, "mediacodec-copy", 100, false));
    }

    @Test
    public void submittedFrameIsNeverRetried() {
        assertFalse(MpvHardwareNoOutputPolicy.shouldRetryWithSoftware(
                true, true, "mediacodec", 100, false));
    }

    @Test
    public void softwareDecodeIsNeverRetried() {
        assertFalse(MpvHardwareNoOutputPolicy.shouldRetryWithSoftware(
                false, false, "mediacodec", 100, false));
    }

    @Test
    public void unengagedDecoderIsNeverRetried() {
        // Source slow or broken: no hardware decoder engaged yet.
        assertFalse(MpvHardwareNoOutputPolicy.shouldRetryWithSoftware(
                true, false, "", 100, false));
        assertFalse(MpvHardwareNoOutputPolicy.shouldRetryWithSoftware(
                true, false, "no", 100, false));
        assertFalse(MpvHardwareNoOutputPolicy.shouldRetryWithSoftware(
                true, false, "auto", 100, false));
    }

    @Test
    public void starvedSourceIsNeverRetried() {
        assertFalse(MpvHardwareNoOutputPolicy.shouldRetryWithSoftware(
                true, false, "mediacodec", 40, false));
    }

    @Test
    public void retryHappensAtMostOnce() {
        assertFalse(MpvHardwareNoOutputPolicy.shouldRetryWithSoftware(
                true, false, "mediacodec", 100, true));
    }

    @Test
    public void hardwareDecoderDetection() {
        assertTrue(MpvHardwareNoOutputPolicy.isHardwareDecoder("mediacodec"));
        assertTrue(MpvHardwareNoOutputPolicy.isHardwareDecoder("mediacodec-copy"));
        assertTrue(MpvHardwareNoOutputPolicy.isHardwareDecoder("vaapi"));
        assertFalse(MpvHardwareNoOutputPolicy.isHardwareDecoder(null));
        assertFalse(MpvHardwareNoOutputPolicy.isHardwareDecoder(""));
        assertFalse(MpvHardwareNoOutputPolicy.isHardwareDecoder("no"));
        assertFalse(MpvHardwareNoOutputPolicy.isHardwareDecoder("auto-safe"));
    }

    @Test
    public void sourceStarvationDetection() {
        // 0 means the cache never reported a wait; only 1..99 is a real stall.
        assertFalse(MpvHardwareNoOutputPolicy.isSourceStarved(0));
        assertTrue(MpvHardwareNoOutputPolicy.isSourceStarved(40));
        assertFalse(MpvHardwareNoOutputPolicy.isSourceStarved(100));
    }

    // --- second failure mode: a frame WAS submitted, then the decoder wedged ---

    @Test
    public void frozenHardwareAfterFirstFrameFallsBackToSoftware() {
        assertTrue(MpvHardwareNoOutputPolicy.shouldRetryFrozenWithSoftware(
                true, true, "mediacodec", 6_000, 100, false, false, true, false));
    }

    @Test
    public void frozenProbeNeedsAnAlreadySubmittedFrame() {
        // Never a frame: that is the other detector's job, not this one's.
        assertFalse(MpvHardwareNoOutputPolicy.shouldRetryFrozenWithSoftware(
                true, false, "mediacodec", 6_000, 100, false, false, true, false));
    }

    @Test
    public void movingPositionIsNeverAFreeze() {
        assertFalse(MpvHardwareNoOutputPolicy.shouldRetryFrozenWithSoftware(
                true, true, "mediacodec", 3_000, 100, false, false, true, false));
    }

    @Test
    public void userPauseAndFinishedStreamAreNotStalls() {
        assertFalse(MpvHardwareNoOutputPolicy.shouldRetryFrozenWithSoftware(
                true, true, "mediacodec", 30_000, 100, false, false, false, false));
        assertFalse(MpvHardwareNoOutputPolicy.shouldRetryFrozenWithSoftware(
                true, true, "mediacodec", 30_000, 100, false, true, true, false));
    }

    @Test
    public void starvedSourceExplainsTheFreezeSoSoftwareIsNotTried() {
        // Demuxer still waiting for data: the network, not the decoder, is the bottleneck.
        assertFalse(MpvHardwareNoOutputPolicy.shouldRetryFrozenWithSoftware(
                true, true, "mediacodec", 30_000, 40, false, false, true, false));
    }

    @Test
    public void growingCacheProvesTheDecoderIsTheBottleneck() {
        // Cache bytes grew yet the position did not move: data is arriving fine.
        assertTrue(MpvHardwareNoOutputPolicy.shouldRetryFrozenWithSoftware(
                true, true, "mediacodec", 30_000, 40, true, false, true, false));
    }

    @Test
    public void frozenSoftwareDecodeAndRetriedSessionsAreLeftAlone() {
        assertFalse(MpvHardwareNoOutputPolicy.shouldRetryFrozenWithSoftware(
                false, true, "mediacodec", 30_000, 100, false, false, true, false));
        assertFalse(MpvHardwareNoOutputPolicy.shouldRetryFrozenWithSoftware(
                true, true, "mediacodec", 30_000, 100, false, false, true, true));
    }
}
