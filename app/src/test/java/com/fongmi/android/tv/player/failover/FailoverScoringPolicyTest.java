package com.fongmi.android.tv.player.failover;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FailoverScoringPolicyTest {

    private static FailoverObservation healthy() {
        return new FailoverObservation(30_000, 30_000, 0, 0, 15_000,
                8_000_000, 4_000_000, FailoverErrorKind.NONE, true);
    }

    @Test
    public void healthyPlaybackScoresFull() {
        assertEquals(100.0, FailoverScoringPolicy.score(healthy()), 0.001);
    }

    @Test
    public void healthyPlaybackNeverSwitches() {
        assertFalse(FailoverScoringPolicy.shouldSwitch(healthy()));
    }

    @Test
    public void unknownThroughputAloneDoesNotCondemnALine() {
        FailoverObservation noThroughput = new FailoverObservation(
                30_000, 30_000, 0, 0, 15_000, -1, -1, FailoverErrorKind.NONE, true);
        assertTrue(FailoverScoringPolicy.score(noThroughput) >= FailoverScoringPolicy.SWITCH_THRESHOLD);
        assertFalse(FailoverScoringPolicy.shouldSwitch(noThroughput));
    }

    @Test
    public void fatalErrorsCondemnTheLineImmediately() {
        for (FailoverErrorKind kind : new FailoverErrorKind[]{
                FailoverErrorKind.AUTH, FailoverErrorKind.NOT_FOUND, FailoverErrorKind.DECODE}) {
            assertEquals(0.0, FailoverScoringPolicy.score(
                    new FailoverObservation(30_000, 30_000, 0, 0, 15_000, 8_000_000, 4_000_000, kind, true)), 0.001);
        }
    }

    @Test
    public void authErrorIsFatalEvenInATinyWindow() {
        FailoverObservation tiny = new FailoverObservation(
                1_000, 1_000, 0, 0, -1, -1, -1, FailoverErrorKind.AUTH, true);
        assertTrue(FailoverScoringPolicy.trustworthy(tiny));
        assertTrue(FailoverScoringPolicy.shouldSwitch(tiny));
    }

    @Test
    public void transientTimeoutInAShortWindowDoesNotSwitch() {
        FailoverObservation tiny = new FailoverObservation(
                2_000, 2_000, 0, 0, -1, -1, -1, FailoverErrorKind.TIMEOUT, true);
        assertFalse(FailoverScoringPolicy.trustworthy(tiny));
        assertFalse(FailoverScoringPolicy.shouldSwitch(tiny));
    }

    @Test
    public void persistentStallingSwitches() {
        FailoverObservation stalled = new FailoverObservation(
                30_000, 2_000, 6, 24_000, 500, 500_000, 4_000_000, FailoverErrorKind.NONE, false);
        assertTrue(FailoverScoringPolicy.score(stalled) < FailoverScoringPolicy.SWITCH_THRESHOLD);
        assertTrue(FailoverScoringPolicy.shouldSwitch(stalled));
    }

    @Test
    public void aDeadLineWithAnEmptyBufferCondemnsItself() {
        // Regression: a fully frozen line with a starved buffer scored 49.0 — just above the
        // 45.0 threshold — so the watchdog refused to rescue exactly the case it exists for.
        FailoverObservation dead = new FailoverObservation(
                9_264, 0, 0, 0, 0, 0, 0, FailoverErrorKind.NONE, false);
        assertTrue("score=" + FailoverScoringPolicy.score(dead),
                FailoverScoringPolicy.score(dead) < FailoverScoringPolicy.SWITCH_THRESHOLD);
        assertTrue(FailoverScoringPolicy.shouldSwitch(dead));
    }

    @Test
    public void aFrozenLineWithABufferIsSuspiciousButNotYetDead() {
        // Still frozen, but read-ahead remains: give the source a window to recover before
        // abandoning it. (Sustained badness still escalates through the tracker.)
        FailoverObservation frozen = new FailoverObservation(
                9_000, 0, 0, 0, 12_000, 8_000_000, 4_000_000, FailoverErrorKind.NONE, false);
        assertTrue(FailoverScoringPolicy.score(frozen) < 100.0);
    }

    @Test
    public void lowBitrateAttainmentAloneDoesNotSwitch() {
        FailoverObservation slow = new FailoverObservation(
                30_000, 29_000, 0, 0, 15_000, 2_500_000, 4_000_000, FailoverErrorKind.NONE, true);
        assertTrue(FailoverScoringPolicy.score(slow) >= FailoverScoringPolicy.SWITCH_THRESHOLD);
        assertFalse(FailoverScoringPolicy.shouldSwitch(slow));
    }

    @Test
    public void cleanObservationWithoutErrorNeverSwitches() {
        assertFalse(FailoverScoringPolicy.shouldSwitch(
                new FailoverObservation(1_000, 0, 0, 0, 0, -1, -1, FailoverErrorKind.NONE, false)));
    }

    @Test
    public void nullObservationIsTreatedAsHealthy() {
        assertEquals(100.0, FailoverScoringPolicy.score(null), 0.001);
        assertFalse(FailoverScoringPolicy.shouldSwitch(null));
    }

    @Test
    public void errorClassificationOrderPrefersFatalStatus() {
        assertEquals(FailoverErrorKind.AUTH, FailoverErrorKind.classify("403 timeout", 0));
        assertEquals(FailoverErrorKind.AUTH, FailoverErrorKind.classify("", 403));
        assertEquals(FailoverErrorKind.NOT_FOUND, FailoverErrorKind.classify("", 404));
        assertEquals(FailoverErrorKind.SERVER, FailoverErrorKind.classify("", 503));
        assertEquals(FailoverErrorKind.DECODE, FailoverErrorKind.classify("mediacodec decode failed", 0));
        assertEquals(FailoverErrorKind.NETWORK, FailoverErrorKind.classify("UnknownHostException", 0));
        assertEquals(FailoverErrorKind.TIMEOUT, FailoverErrorKind.classify("read timed out", 0));
        assertEquals(FailoverErrorKind.NONE, FailoverErrorKind.classify("", 0));
    }
}
