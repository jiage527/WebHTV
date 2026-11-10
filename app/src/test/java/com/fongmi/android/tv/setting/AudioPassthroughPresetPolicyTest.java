package com.fongmi.android.tv.setting;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AudioPassthroughPresetPolicyTest {

    @Test
    public void exoPresetEnablesPassthroughButMpvKeepsItOff() {
        // MPV defaults to off: a negotiated carrier the active output cannot open
        // leaves a dead audio path, and since video-sync follows the audio clock
        // the picture then stalls. Exo keeps the capability-negotiated default.
        assertTrue(KernelPerformanceSetting.audioPassthroughForPreset(
                PlayerSetting.EXO));
        assertFalse(KernelPerformanceSetting.audioPassthroughForPreset(
                PlayerSetting.MPV));
        assertFalse(KernelPerformanceSetting.audioPassthroughForPreset(
                PlayerSetting.IJK));
    }

    @Test
    public void mpvDefaultIsOffWhileOtherKernelsKeepPassthrough() {
        assertFalse(KernelPerformanceSetting.defaultAudioPassThrough(
                PlayerSetting.MPV));
        assertTrue(KernelPerformanceSetting.defaultAudioPassThrough(
                PlayerSetting.EXO));
        assertTrue(KernelPerformanceSetting.defaultAudioPassThrough(
                PlayerSetting.IJK));
        // An unknown value must not be mistaken for the MPV kernel.
        assertTrue(KernelPerformanceSetting.defaultAudioPassThrough(99));
    }

    @Test
    public void inheritedMpvPassthroughIsTurnedOffUnlessChosen() {
        // Nobody chose it: the old default left true behind, so it is re-seeded.
        assertTrue(PlaybackPerformanceSetting.shouldDisableMpvPassthroughDefault(
                PlayerSetting.MPV, true, false));
        // Already off, nothing to do.
        assertFalse(PlaybackPerformanceSetting.shouldDisableMpvPassthroughDefault(
                PlayerSetting.MPV, false, false));
        // A viewer whose receiver really decodes Dolby/DTS keeps the choice.
        assertFalse(PlaybackPerformanceSetting.shouldDisableMpvPassthroughDefault(
                PlayerSetting.MPV, true, true));
        // Other kernels are not part of this change.
        assertFalse(PlaybackPerformanceSetting.shouldDisableMpvPassthroughDefault(
                PlayerSetting.EXO, true, false));
        assertFalse(PlaybackPerformanceSetting.shouldDisableMpvPassthroughDefault(
                PlayerSetting.IJK, true, false));
    }

    @Test
    public void migrationPreservesExplicitUserChoices() {
        assertTrue(PlaybackPerformanceSetting
                .shouldMigrateAudioPassthroughDefault(
                        PlaybackPerformanceSetting.PROFILE_AUTO,
                        false,
                        false));
        assertFalse(PlaybackPerformanceSetting
                .shouldMigrateAudioPassthroughDefault(
                        PlaybackPerformanceSetting.PROFILE_AUTO,
                        true,
                        false));
        assertFalse(PlaybackPerformanceSetting
                .shouldMigrateAudioPassthroughDefault(
                        PlaybackPerformanceSetting.PROFILE_CUSTOM,
                        false,
                        false));
        assertFalse(PlaybackPerformanceSetting
                .shouldMigrateAudioPassthroughDefault(
                        PlaybackPerformanceSetting.PROFILE_LIGHTWEIGHT,
                        false,
                        true));
    }
}
