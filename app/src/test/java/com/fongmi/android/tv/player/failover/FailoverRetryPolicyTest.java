package com.fongmi.android.tv.player.failover;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FailoverRetryPolicyTest {

    private static FailoverRetryPolicy.Input input(boolean auto, boolean fatal, double score,
                                                   int retries, int maxRetries,
                                                   boolean hasFlag, boolean hasSite) {
        return new FailoverRetryPolicy.Input(auto, fatal, score, retries, maxRetries, hasFlag, hasSite);
    }

    @Test
    public void healthyLineIsRetriedNotSwitched() {
        assertEquals(FailoverRetryPolicy.Action.RETRY_SAME,
                FailoverRetryPolicy.decide(input(true, false, 90, 0, 1, true, true)));
    }

    @Test
    public void transientBadnessGetsOneSameSourceRetryFirst() {
        assertEquals(FailoverRetryPolicy.Action.RETRY_SAME,
                FailoverRetryPolicy.decide(input(true, false, 30, 0, 1, true, true)));
    }

    @Test
    public void exhaustedSameSourceRetriesMoveToNextFlag() {
        assertEquals(FailoverRetryPolicy.Action.NEXT_FLAG,
                FailoverRetryPolicy.decide(input(true, false, 30, 1, 1, true, true)));
    }

    @Test
    public void fatalErrorSkipsSameSourceRetry() {
        assertEquals(FailoverRetryPolicy.Action.NEXT_FLAG,
                FailoverRetryPolicy.decide(input(true, true, 0, 0, 1, true, true)));
    }

    @Test
    public void lastFlagFallsBackToNextSite() {
        assertEquals(FailoverRetryPolicy.Action.NEXT_SITE,
                FailoverRetryPolicy.decide(input(true, true, 0, 0, 1, false, true)));
    }

    @Test
    public void noFlagsAndNoSitesGivesUp() {
        assertEquals(FailoverRetryPolicy.Action.GIVE_UP,
                FailoverRetryPolicy.decide(input(true, true, 0, 0, 1, false, false)));
    }

    @Test
    public void autoChangeOffNeverSwitchesSource() {
        assertEquals(FailoverRetryPolicy.Action.GIVE_UP,
                FailoverRetryPolicy.decide(input(false, true, 0, 0, 1, true, true)));
    }

    @Test
    public void autoChangeOffStillRetriesAHealthyLine() {
        assertEquals(FailoverRetryPolicy.Action.RETRY_SAME,
                FailoverRetryPolicy.decide(input(false, false, 90, 0, 1, true, true)));
    }

    @Test
    public void nullInputGivesUp() {
        assertEquals(FailoverRetryPolicy.Action.GIVE_UP, FailoverRetryPolicy.decide(null));
    }

    @Test
    public void midPlaybackSwitchNeedsATrustworthyBadWindow() {
        assertTrue(FailoverRetryPolicy.shouldSwitchMidPlayback(new FailoverObservation(
                30_000, 2_000, 6, 24_000, 500, 500_000, 4_000_000, FailoverErrorKind.NONE, false)));
        assertFalse(FailoverRetryPolicy.shouldSwitchMidPlayback(new FailoverObservation(
                30_000, 30_000, 0, 0, 15_000, 8_000_000, 4_000_000, FailoverErrorKind.NONE, true)));
    }
}
