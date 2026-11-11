package com.fongmi.android.tv.player.failover;

/**
 * Pure runtime quality scoring for the multi-source failover watchdog.
 *
 * <p>Design goals, each pinned by a unit test:
 * <ul>
 *   <li>Healthy playback must <b>never</b> trigger a source switch — missing signals
 *       score neutral rather than punitive.</li>
 *   <li>A single fatal error (403/404/decode) is enough to condemn a line.</li>
 *   <li>Repeated stalling (rebuffer ratio / low bitrate attainment) degrades a line
 *       gradually so one hiccup is tolerated but a persistent one is not.</li>
 *   <li>Scoring is bounded 0..100 so thresholds are comparable across lines.</li>
 * </ul>
 */
public final class FailoverScoringPolicy {

    public static final double SCORE_HEALTHY = 100.0;

    /** Below this score the watchdog switches to the next line. */
    public static final double SWITCH_THRESHOLD = 45.0;

    /** A window shorter than this cannot fairly judge a line. */
    public static final long MIN_WINDOW_MS = 8_000L;

    private FailoverScoringPolicy() {}

    /**
     * Score a single observation window, 0..100.
     *
     * <p>Each signal removes at most its own budget, so a truly dead line lands near 0
     * and an imperfect-but-watchable one stays above {@link #SWITCH_THRESHOLD}.
     */
    public static double score(FailoverObservation observation) {
        if (observation == null) return SCORE_HEALTHY;
        double score = SCORE_HEALTHY;

        // Fatal errors condemn the line outright: waiting cannot fix a 403 or a decoder.
        if (observation.errorKind() != null && observation.errorKind().fatal()) return 0.0;

        // Transitive errors: weighted penalty, not a verdict.
        if (observation.errorKind() != null && observation.errorKind().isError()) {
            score -= observation.errorKind().penalty();
        }

        // Stall ratio: how much of the window was spent not playing after a stall began.
        long window = Math.max(observation.windowMs(), 1L);
        double stallRatio = clamp01((double) observation.rebufferMs() / (double) window);
        score -= stallRatio * 30.0;
        score -= Math.min(observation.rebufferCount(), 10) * 3.0;

        // Bitrate attainment: throughput measured against the media's declared bitrate.
        if (observation.hasThroughput()) {
            double attainment = clamp01((double) observation.throughputBps() / (double) observation.mediaBitrateBps());
            // Below 1.0 the buffer drains; map 0.55..1.0 to 0..20 penalty.
            if (attainment < 1.0) score -= clamp01((1.0 - attainment) / 0.45) * 20.0;
        } else {
            score -= 8.0; // throughput unknown: mild caution, never enough to switch alone.
        }

        // Buffer depth: a line that keeps a healthy read-ahead is fine even with a slow burst.
        // A starved buffer together with a frozen position is the signature of a dead line, so
        // the two are reinforced below rather than merely summed.
        boolean starved = false;
        if (observation.hasBuffered()) {
            if (observation.bufferedMs() < 1_500L) {
                score -= 18.0;
                starved = true;
            } else if (observation.bufferedMs() < 4_000L) {
                score -= 8.0;
            }
        }

        // Frozen position within a long-enough observation is a strong stall signal. When the
        // buffer is also empty, nothing is coming: that is a dead line, not a slow one, and must
        // fall below the switch threshold on its own.
        if (!observation.progressing() && observation.windowMs() >= MIN_WINDOW_MS) {
            score -= starved ? 60.0 : 25.0;
        }

        return Math.max(0.0, Math.min(SCORE_HEALTHY, score));
    }

    /**
     * Whether this window justifies switching away from the current line.
     *
     * <p>Guards against acting on noise:
     * <ul>
     *   <li>a window too short to judge is ignored unless the error is fatal;</li>
     *   <li>the line must be below the threshold, not merely imperfect.</li>
     * </ul>
     */
    public static boolean shouldSwitch(FailoverObservation observation) {
        if (observation == null) return false;
        if (!trustworthy(observation)) return false;
        return score(observation) < SWITCH_THRESHOLD;
    }

    /** A short window can only condemn a line when the error itself is fatal. */
    public static boolean trustworthy(FailoverObservation observation) {
        if (observation == null) return false;
        if (observation.errorKind() != null && observation.errorKind().fatal()) return true;
        return observation.windowMs() >= MIN_WINDOW_MS;
    }

    private static double clamp01(double value) {
        if (value < 0) return 0;
        if (value > 1) return 1;
        return value;
    }
}
