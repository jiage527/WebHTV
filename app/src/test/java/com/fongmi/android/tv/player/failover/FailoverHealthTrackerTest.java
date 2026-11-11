package com.fongmi.android.tv.player.failover;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FailoverHealthTrackerTest {

    /** Feed {count} one-second samples of smooth playback and return the last matured window. */
    private static FailoverObservation runSmooth(FailoverHealthTracker tracker, int seconds) {
        FailoverObservation matured = null;
        for (int i = 1; i <= seconds; i++) {
            FailoverObservation result = tracker.observe(
                    i * 1_000L, i * 1_000L - 900, 0, 0, 12_000, 8_000_000, 4_000_000);
            if (result != null) matured = result;
        }
        return matured;
    }

    @Test
    public void freshTrackerIsPerfectUntilSampled() {
        FailoverHealthTracker tracker = new FailoverHealthTracker();
        assertEquals(100.0, tracker.score(), 0.001);
        assertFalse(tracker.everProgressed());
    }

    @Test
    public void firstSampleOnlyEstablishesABaseline() {
        FailoverHealthTracker tracker = new FailoverHealthTracker();
        assertNull(tracker.observe(1_000, 5_000, 0, 0, 8_000, 8_000_000, 4_000_000));
        assertEquals(100.0, tracker.score(), 0.001);
        assertFalse(tracker.everProgressed());
    }

    @Test
    public void shortWindowDoesNotMatureYet() {
        FailoverHealthTracker tracker = new FailoverHealthTracker();
        tracker.observe(0, 0, 0, 0, 12_000, 8_000_000, 4_000_000);
        assertNull(tracker.observe(1_000, 900, 0, 0, 12_000, 8_000_000, 4_000_000));
        assertNull(tracker.observe(5_000, 4_900, 0, 0, 12_000, 8_000_000, 4_000_000));
    }

    @Test
    public void windowMaturesAtTheJudgementBoundary() {
        FailoverHealthTracker tracker = new FailoverHealthTracker();
        tracker.observe(0, 0, 0, 0, 12_000, 8_000_000, 4_000_000);
        // 7s: still too short; 8s: matured.
        assertNull(tracker.observe(7_000, 6_900, 0, 0, 12_000, 8_000_000, 4_000_000));
        FailoverObservation matured = tracker.observe(
                FailoverScoringPolicy.MIN_WINDOW_MS, FailoverScoringPolicy.MIN_WINDOW_MS - 100,
                0, 0, 12_000, 8_000_000, 4_000_000);
        assertNotNull(matured);
        assertEquals(FailoverScoringPolicy.MIN_WINDOW_MS, matured.windowMs());
    }

    @Test
    public void smoothPlaybackKeepsFullScore() {
        FailoverHealthTracker tracker = new FailoverHealthTracker();
        assertNotNull(runSmooth(tracker, 30));
        assertEquals(100.0, tracker.score(), 0.001);
        assertTrue(tracker.everProgressed());
    }

    @Test
    public void playedMsIsRealProgressNotAnAccumulatingSum() {
        // Regression: summing the per-sample distance from the window baseline gave
        // 1+2+...+8 = 36s of "played" inside an 8s window. Played must stay <= window.
        FailoverHealthTracker tracker = new FailoverHealthTracker();
        FailoverObservation matured = null;
        for (int i = 0; i <= 8; i++) {
            FailoverObservation result = tracker.observe(
                    i * 1_000L, i * 1_000L, 0, 0, 12_000, 8_000_000, 4_000_000);
            if (result != null) matured = result;
        }
        assertNotNull(matured);
        assertEquals(FailoverScoringPolicy.MIN_WINDOW_MS, matured.windowMs());
        assertTrue("played=" + matured.playedMs(), matured.playedMs() <= matured.windowMs());
        assertTrue("played=" + matured.playedMs(), matured.playedMs() >= 7_000);
    }

    @Test
    public void aForwardSeekIsNotCountedAsPlayTime() {
        FailoverHealthTracker tracker = new FailoverHealthTracker();
        tracker.observe(0, 0, 0, 0, 12_000, 8_000_000, 4_000_000);
        // Jump to 300s within a two-second window: that is a seek, not playback.
        tracker.observe(2_000, 300_000, 0, 0, 12_000, 8_000_000, 4_000_000);
        assertTrue(tracker.lastObservation().playedMs() <= 2_000);
    }

    @Test
    public void moderateRebufferingStaysWatchable() {
        FailoverHealthTracker tracker = new FailoverHealthTracker();
        tracker.observe(0, 0, 0, 0, 12_000, 8_000_000, 4_000_000);
        // Still advancing with a healthy buffer and one short hiccup: watchable.
        FailoverObservation matured = tracker.observe(
                8_000, 6_500, 1, 1_500, 10_000, 7_000_000, 4_000_000);
        assertNotNull(matured);
        assertFalse(tracker.shouldAct());
    }

    @Test
    public void aSingleBadWindowIsTolerated() {
        FailoverHealthTracker tracker = new FailoverHealthTracker();
        tracker.observe(0, 0, 0, 0, 12_000, 8_000_000, 4_000_000);
        // Position frozen for one judgement window: bad, but one hiccup must not switch a line.
        assertNotNull(tracker.observe(
                FailoverScoringPolicy.MIN_WINDOW_MS, 0, 1, 8_000, 8_000, 8_000_000, 4_000_000));
        assertTrue(tracker.score() < FailoverScoringPolicy.SWITCH_THRESHOLD);
        assertEquals(1, tracker.consecutiveBadWindows());
        assertFalse(tracker.shouldAct());
    }

    @Test
    public void sustainedBadnessActsOnTheSecondWindow() {
        FailoverHealthTracker tracker = new FailoverHealthTracker();
        tracker.observe(0, 0, 0, 0, 12_000, 8_000_000, 4_000_000);
        tracker.observe(10_000, 500, 2, 9_000, 500, 500_000, 4_000_000);
        assertFalse(tracker.shouldAct());
        tracker.observe(20_000, 1_000, 4, 18_000, 500, 500_000, 4_000_000);
        assertTrue(tracker.shouldAct());
        assertTrue(tracker.consecutiveBadWindows() >= FailoverHealthTracker.BAD_WINDOWS_TO_SWITCH);
    }

    @Test
    public void aRecoveredLineIsNotCondemned() {
        FailoverHealthTracker tracker = new FailoverHealthTracker();
        tracker.observe(0, 0, 0, 0, 12_000, 8_000_000, 4_000_000);
        // One bad window, then a fully healthy one: the line recovered.
        tracker.observe(10_000, 500, 2, 9_000, 500, 500_000, 4_000_000);
        tracker.observe(20_000, 10_500, 2, 9_000, 14_000, 8_000_000, 4_000_000);
        assertFalse(tracker.shouldAct());
        assertEquals(0, tracker.consecutiveBadWindows());
    }

    @Test
    public void aSeverelyBadWindowActsImmediately() {
        FailoverHealthTracker tracker = new FailoverHealthTracker();
        tracker.observe(0, 0, 0, 0, 12_000, 8_000_000, 4_000_000);
        // Frozen, tiny buffer, throughput far under bitrate: effectively dead, not merely slow.
        assertNotNull(tracker.observe(20_000, 0, 5, 20_000, 100, 100_000, 4_000_000));
        assertTrue(tracker.score() <= FailoverHealthTracker.SEVERE_SCORE);
        assertTrue(tracker.shouldAct());
    }

    @Test
    public void fatalErrorCondemnsImmediately() {
        FailoverHealthTracker tracker = new FailoverHealthTracker();
        tracker.observeError(FailoverErrorKind.NOT_FOUND);
        assertEquals(0.0, tracker.score(), 0.001);
        assertEquals(FailoverErrorKind.NOT_FOUND, tracker.lastError());
    }

    @Test
    public void nonFatalErrorDoesNotCondemnOnItsOwn() {
        FailoverHealthTracker tracker = new FailoverHealthTracker();
        tracker.observeError(FailoverErrorKind.TIMEOUT);
        assertTrue(tracker.score() >= FailoverScoringPolicy.SWITCH_THRESHOLD);
        assertFalse(tracker.shouldAct());
    }

    @Test
    public void nonErrorIsIgnored() {
        FailoverHealthTracker tracker = new FailoverHealthTracker();
        tracker.observeError(FailoverErrorKind.NONE);
        assertEquals(100.0, tracker.score(), 0.001);
    }

    @Test
    public void resetClearsEverything() {
        FailoverHealthTracker tracker = new FailoverHealthTracker();
        tracker.observeError(FailoverErrorKind.AUTH);
        tracker.reset();
        assertEquals(100.0, tracker.score(), 0.001);
        assertFalse(tracker.everProgressed());
    }

    @Test
    public void nonMonotonicClockIsIgnored() {
        FailoverHealthTracker tracker = new FailoverHealthTracker();
        tracker.observe(5_000, 5_000, 0, 0, 8_000, 8_000_000, 4_000_000);
        assertNull(tracker.observe(4_000, 9_000, 0, 0, 8_000, 8_000_000, 4_000_000));
        assertEquals(100.0, tracker.score(), 0.001);
    }
}
