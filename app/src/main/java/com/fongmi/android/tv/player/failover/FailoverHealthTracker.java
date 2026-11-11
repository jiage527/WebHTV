package com.fongmi.android.tv.player.failover;

/**
 * Accumulates runtime health for one playing line.
 *
 * <p>Pure JVM: the watchdog feeds it one sample per second and reads back the current verdict.
 *
 * <p>Three deliberate design choices, each pinned by a test:
 * <ul>
 *   <li><b>Judgement windows, not raw ticks.</b> A one-second sample is far too short to
 *       judge a line, so samples accumulate into a window of at least
 *       {@link FailoverScoringPolicy#MIN_WINDOW_MS} before a non-fatal verdict is formed.</li>
 *   <li><b><i>Consecutive</i> bad windows, not a sticky minimum.</b> A line that recovered is
 *       fine again: switching is driven by current, sustained badness. A single bad window is
 *       tolerated (it is usually a transient hiccup), a severely bad one is not.</li>
 *   <li><b>Fatal errors bypass the window.</b> A 403 or a decoder failure cannot improve by
 *       waiting for another window.</li>
 * </ul>
 */
public final class FailoverHealthTracker {

    /** How many consecutive bad windows before the watchdog acts. */
    public static final int BAD_WINDOWS_TO_SWITCH = 2;
    /** A window this bad acts on its own: the line is effectively dead, not merely slow. */
    public static final double SEVERE_SCORE = 20.0;

    private long windowStartMs;
    private long windowPositionMs = -1;
    private long previousPositionMs = -1;
    private long previousSampleMs;
    private int windowRebufferCount;
    private long windowRebufferMs;
    private long windowPlayedMs;
    private boolean windowProgressed;

    private double lastScore = FailoverScoringPolicy.SCORE_HEALTHY;
    private int consecutiveBadWindows;
    private boolean severe;
    private boolean everProgressed;
    private FailoverErrorKind lastError = FailoverErrorKind.NONE;
    private FailoverObservation lastObservation = FailoverObservation.empty();

    public void reset() {
        windowStartMs = 0;
        windowPositionMs = -1;
        previousPositionMs = -1;
        previousSampleMs = 0;
        windowRebufferCount = 0;
        windowRebufferMs = 0;
        windowPlayedMs = 0;
        windowProgressed = false;
        lastScore = FailoverScoringPolicy.SCORE_HEALTHY;
        consecutiveBadWindows = 0;
        severe = false;
        everProgressed = false;
        lastError = FailoverErrorKind.NONE;
        lastObservation = FailoverObservation.empty();
    }

    /**
     * Feed one cumulative sample. Returns the completed observation when this sample closed a
     * judgement window, or {@code null} when the window is still open.
     *
     * @param nowMs            monotonic clock
     * @param positionMs       current playback position
     * @param rebufferCount    cumulative rebuffer count for this line
     * @param rebufferMs       cumulative rebuffer duration for this line
     * @param bufferedMs       current read-ahead depth, or -1 when unknown
     * @param throughputBps    measured throughput, or -1 when unknown
     * @param mediaBitrateBps  declared media bitrate, or -1 when unknown
     */
    public FailoverObservation observe(long nowMs, long positionMs, int rebufferCount, long rebufferMs,
                                       long bufferedMs, long throughputBps, long mediaBitrateBps) {
        if (windowPositionMs < 0) {
            // First sample of a window only establishes the baseline.
            windowStartMs = nowMs;
            windowPositionMs = positionMs;
            previousPositionMs = positionMs;
            previousSampleMs = nowMs;
            windowRebufferCount = rebufferCount;
            windowRebufferMs = rebufferMs;
            windowProgressed = false;
            return null;
        }
        long window = nowMs - windowStartMs;
        if (window <= 0) return null;

        // Accumulate per-sample progress, clamped by the elapsed time since the previous sample:
        // a seek or a resumed start must not read as extra play time.
        long elapsed = Math.max(0, nowMs - previousSampleMs);
        long delta = positionMs - previousPositionMs;
        if (delta > 0) {
            windowPlayedMs += Math.min(delta, elapsed);
            windowProgressed = true;
            everProgressed = true;
        }
        previousPositionMs = positionMs;
        previousSampleMs = nowMs;

        lastObservation = new FailoverObservation(
                window,
                windowPlayedMs,
                Math.max(0, rebufferCount - windowRebufferCount),
                Math.max(0, rebufferMs - windowRebufferMs),
                bufferedMs,
                throughputBps,
                mediaBitrateBps,
                FailoverErrorKind.NONE,
                windowProgressed);

        if (window < FailoverScoringPolicy.MIN_WINDOW_MS) return null;

        // The window matured: form the verdict, then start the next window from this sample.
        FailoverObservation completed = lastObservation;
        record(FailoverScoringPolicy.score(completed));
        windowStartMs = nowMs;
        windowPositionMs = positionMs;
        previousPositionMs = positionMs;
        previousSampleMs = nowMs;
        windowRebufferCount = rebufferCount;
        windowRebufferMs = rebufferMs;
        windowPlayedMs = 0;
        windowProgressed = false;
        return completed;
    }

    /** Record a hard error reported by the player; fatal kinds condemn the line immediately. */
    public void observeError(FailoverErrorKind kind) {
        if (kind == null || !kind.isError()) return;
        lastError = kind;
        if (kind.fatal()) {
            severe = true;
            consecutiveBadWindows = Math.max(consecutiveBadWindows, BAD_WINDOWS_TO_SWITCH);
            lastScore = 0.0;
            return;
        }
        // A non-fatal error (timeout, transient network) scores by its own penalty only. It must
        // not be conflated with a frozen window: the position may have been advancing fine. Its
        // penalty is well under the switch threshold by construction, so the watchdog acts on the
        // error itself rather than on this score.
        lastScore = Math.max(0.0, FailoverScoringPolicy.SCORE_HEALTHY - kind.penalty());
        lastObservation = new FailoverObservation(
                FailoverScoringPolicy.MIN_WINDOW_MS, windowPlayedMs, 0,
                Math.max(0, windowRebufferMs), -1, -1, -1, kind, true);
        if (lastScore < FailoverScoringPolicy.SWITCH_THRESHOLD) {
            consecutiveBadWindows++;
            if (lastScore <= SEVERE_SCORE) severe = true;
        }
    }

    private void record(double score) {
        lastScore = score;
        if (score < FailoverScoringPolicy.SWITCH_THRESHOLD) {
            consecutiveBadWindows++;
            if (score <= SEVERE_SCORE) severe = true;
        } else {
            consecutiveBadWindows = 0;
            severe = false;
        }
    }

    /** The current verdict 0..100 (latest judgement window, or the last error's score). */
    public double score() {
        return lastScore;
    }

    /** Whether the watchdog should act on this line's present state. */
    public boolean shouldAct() {
        return severe || consecutiveBadWindows >= BAD_WINDOWS_TO_SWITCH;
    }

    public int consecutiveBadWindows() {
        return consecutiveBadWindows;
    }

    /** The most recent observation, or an empty one before the first judgement window. */
    public FailoverObservation lastObservation() {
        return lastObservation;
    }

    public FailoverErrorKind lastError() {
        return lastError;
    }

    /** Whether this line ever produced forward playback, used for diagnostics only. */
    public boolean everProgressed() {
        return everProgressed;
    }
}
