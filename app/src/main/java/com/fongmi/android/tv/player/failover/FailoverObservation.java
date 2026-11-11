package com.fongmi.android.tv.player.failover;

/**
 * One observation window of a playing line.
 *
 * <p>All inputs are monotonic counters sampled by the watchdog. Unknown values are
 * represented as {@code -1} (or {@code 0} for bitrates) so the scoring policy can
 * simply skip signals it does not have — never punish a line for a missing metric.
 */
public record FailoverObservation(
        long windowMs,
        long playedMs,
        int rebufferCount,
        long rebufferMs,
        long bufferedMs,
        long throughputBps,
        long mediaBitrateBps,
        FailoverErrorKind errorKind,
        boolean progressing) {

    public static FailoverObservation empty() {
        return new FailoverObservation(0, 0, 0, 0, -1, -1, -1, FailoverErrorKind.NONE, false);
    }

    public boolean hasThroughput() {
        return throughputBps > 0 && mediaBitrateBps > 0;
    }

    public boolean hasBuffered() {
        return bufferedMs >= 0;
    }
}
