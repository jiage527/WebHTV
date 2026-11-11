package com.fongmi.android.tv.player.failover;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Ladder tests for the extractable retry path, exercised through records alone so no device,
 * clock or player is needed. The watchdog itself is a thin sampler over exactly this logic.
 */
public final class FailoverLadderTest {

    @Test
    public void ladderRetriesThenSwitchesFlagThenSiteThenGivesUp() {
        // 1) unhealthy but non-fatal and rounds remain -> same-line retry
        assertEquals(FailoverRetryPolicy.Action.RETRY_SAME, FailoverRetryPolicy.decide(
                new FailoverRetryPolicy.Input(true, false, 20, 0, 1, true, true)));
        // 2) rounds spent -> next flag
        assertEquals(FailoverRetryPolicy.Action.NEXT_FLAG, FailoverRetryPolicy.decide(
                new FailoverRetryPolicy.Input(true, false, 20, 1, 1, true, true)));
        // 3) no more flags -> next site
        assertEquals(FailoverRetryPolicy.Action.NEXT_SITE, FailoverRetryPolicy.decide(
                new FailoverRetryPolicy.Input(true, false, 20, 1, 1, false, true)));
        // 4) nothing left -> give up
        assertEquals(FailoverRetryPolicy.Action.GIVE_UP, FailoverRetryPolicy.decide(
                new FailoverRetryPolicy.Input(true, false, 20, 1, 1, false, false)));
    }

    @Test
    public void configuredRoundsAreClampedToAReasonableRange() {
        // 0 rounds means "switch immediately" and is honoured.
        assertEquals(FailoverRetryPolicy.Action.NEXT_FLAG, FailoverRetryPolicy.decide(
                new FailoverRetryPolicy.Input(true, false, 20, 0, 0, true, true)));
        // 3 rounds keeps retrying the same line for longer.
        assertEquals(FailoverRetryPolicy.Action.RETRY_SAME, FailoverRetryPolicy.decide(
                new FailoverRetryPolicy.Input(true, false, 20, 2, 3, true, true)));
        assertEquals(FailoverRetryPolicy.Action.NEXT_FLAG, FailoverRetryPolicy.decide(
                new FailoverRetryPolicy.Input(true, false, 20, 3, 3, true, true)));
    }

    @Test
    public void withoutAutoChangeTheLadderNeverLeavesTheLine() {
        for (boolean fatal : new boolean[]{false, true}) {
            assertEquals(FailoverRetryPolicy.Action.GIVE_UP, FailoverRetryPolicy.decide(
                    new FailoverRetryPolicy.Input(false, fatal, 0, 0, 1, true, true)));
        }
    }

    @Test
    public void fatalErrorsAlwaysEscalateImmediately() {
        assertEquals(FailoverRetryPolicy.Action.NEXT_FLAG, FailoverRetryPolicy.decide(
                new FailoverRetryPolicy.Input(true, true, 0, 0, 3, true, true)));
    }

    @Test
    public void errorClassificationCoversTheDocumentedCategories() {
        assertTrue(FailoverErrorKind.classify("403", 0).fatal());
        assertTrue(FailoverErrorKind.classify("", 404).fatal());
        assertTrue(FailoverErrorKind.classify("mediacodec decode failed", 0).fatal());
        assertEquals(FailoverErrorKind.TIMEOUT, FailoverErrorKind.classify("read timed out", 0));
        assertEquals(FailoverErrorKind.NETWORK, FailoverErrorKind.classify("connection reset", 0));
        assertEquals(FailoverErrorKind.SERVER, FailoverErrorKind.classify("", 502));
    }
}
