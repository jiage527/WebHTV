package androidx.media3.mpvplayer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvAudioSpdifFailurePolicyTest {

    @Test
    public void detectsNoSoundFailureLine() {
        assertTrue(MpvAudioSpdifFailurePolicy.isAudioOutputInitFailure(
                "ao: Could not open/initialize audio device -> no sound."));
    }

    @Test
    public void detectsAudioOutputInitializationFailure() {
        assertTrue(MpvAudioSpdifFailurePolicy.isAudioOutputInitFailure(
                "ao/audiotrack: audio output initialization failed"));
    }

    @Test
    public void detectsAudioDriverInitializationFailure() {
        assertTrue(MpvAudioSpdifFailurePolicy.isAudioOutputInitFailure(
                "ao: Failed to initialize audio driver 'audiotrack'"));
    }

    @Test
    public void matchesRegardlessOfCaseAndSurroundingText() {
        assertTrue(MpvAudioSpdifFailurePolicy.isAudioOutputInitFailure(
                "[ffmpeg] COULD NOT OPEN/INITIALIZE AUDIO DEVICE -> NO SOUND."));
    }

    @Test
    public void ignoresUnrelatedWarnings() {
        assertFalse(MpvAudioSpdifFailurePolicy.isAudioOutputInitFailure(
                "cplayer: Audio decoder: [ffmpeg] AVCodecContext: using 8 threads"));
        assertFalse(MpvAudioSpdifFailurePolicy.isAudioOutputInitFailure(
                "cplayer: Cache is running low (paused)"));
        assertFalse(MpvAudioSpdifFailurePolicy.isAudioOutputInitFailure(""));
        assertFalse(MpvAudioSpdifFailurePolicy.isAudioOutputInitFailure(null));
    }

    @Test
    public void retriesWhenPassthroughWasConfigured() {
        // The reported failure applies to the list actually configured.
        assertTrue(MpvAudioSpdifFailurePolicy.shouldInspect(
                "ac3,eac3,dts,dts-hd,truehd",
                "ao: Could not open/initialize audio device -> no sound.", false));
    }

    @Test
    public void doesNotRetryWhenPassthroughWasAlreadyOff() {
        // An empty list means passthrough was never configured; dropping it
        // could not change anything, so the policy must not fire.
        assertFalse(MpvAudioSpdifFailurePolicy.shouldInspect(
                "", "ao: Could not open/initialize audio device -> no sound.", false));
        assertFalse(MpvAudioSpdifFailurePolicy.shouldInspect(
                "   ", "ao: audio output initialization failed", false));
        assertFalse(MpvAudioSpdifFailurePolicy.shouldInspect(
                null, "ao: audio output initialization failed", false));
    }

    @Test
    public void attemptsOnlyOncePerMediaItem() {
        assertFalse(MpvAudioSpdifFailurePolicy.shouldInspect(
                "ac3,eac3", "ao: audio output initialization failed", true));
    }

    @Test
    public void doesNotFireWithoutARealFailureLine() {
        assertFalse(MpvAudioSpdifFailurePolicy.shouldInspect(
                "ac3,eac3", "cplayer: video is lagging behind", false));
    }

    @Test
    public void retryValueDisablesPassthrough() {
        assertEquals("", MpvAudioSpdifFailurePolicy.disabledCodecs());
    }
}
