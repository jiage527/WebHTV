package com.fongmi.android.tv.player.failover;

/**
 * The extractable auto-retry / switch plan for the failover watchdog.
 *
 * <p>The work order requires the auto-retry path to be "可提取" (extractable): the exact
 * escalation ladder must live in one pure policy instead of being smeared through UI code.
 * The ladder is:
 *
 * <ol>
 *   <li><b>RETRY_SAME</b> — the line is not conclusively bad yet (transient network blip):
 *       re-open the same URL up to {@code maxSameSourceRetries} times.</li>
 *   <li><b>NEXT_FLAG</b> — the line is bad: switch to the next play flag (线路), preserving
 *       the written progress.</li>
 *   <li><b>NEXT_SITE</b> — every flag of this site is exhausted: fall back to the next
 *       site (站源) via the existing search path.</li>
 *   <li><b>GIVE_UP</b> — the whole episode is dead: stop and report.</li>
 * </ol>
 *
 * <p>Pure JVM so the ladder, its round limits and its skip rule are all unit-testable.
 */
public final class FailoverRetryPolicy {

    /** Default number of same-line re-opens before moving on. */
    public static final int DEFAULT_SAME_SOURCE_RETRIES = 1;

    public enum Action {RETRY_SAME, NEXT_FLAG, NEXT_SITE, GIVE_UP}

    public record Input(
            boolean autoChangeEnabled,
            boolean errorFatal,
            double lineScore,
            int sameSourceRetries,
            int maxSameSourceRetries,
            boolean hasNextFlag,
            boolean hasNextSite) {}

    private FailoverRetryPolicy() {}

    /**
     * Decide the next recovery step.
     *
     * <p>Ordering rules that make this safe:
     * <ul>
     *   <li>Auto source change off ⇒ only same-line retries are allowed.</li>
     *   <li>A healthy-enough line is retried, never switched.</li>
     *   <li>Line exhausted ⇒ next flag; flags exhausted ⇒ next site; both exhausted ⇒ give up.</li>
     * </ul>
     */
    public static Action decide(Input input) {
        if (input == null) return Action.GIVE_UP;
        // Healthy enough: treat the failure as transient and re-open the same line.
        boolean unhealthy = input.errorFatal() || input.lineScore() < FailoverScoringPolicy.SWITCH_THRESHOLD;
        if (!unhealthy) return Action.RETRY_SAME;
        // The line is genuinely bad; switching needs the user's automatic-change consent.
        if (!input.autoChangeEnabled()) return Action.GIVE_UP;
        // Non-fatal badness is worth one same-line re-open before abandoning the line.
        if (!input.errorFatal() && input.sameSourceRetries() < Math.max(0, input.maxSameSourceRetries())) {
            return Action.RETRY_SAME;
        }
        if (input.hasNextFlag()) return Action.NEXT_FLAG;
        if (input.hasNextSite()) return Action.NEXT_SITE;
        return Action.GIVE_UP;
    }

    /**
     * Whether an in-progress line should be abandoned mid-playback.
     *
     * <p>Mid-playback switching is inherently disruptive, so it needs <b>both</b> a
     * trustworthy observation and an explicitly unhealthy line.
     */
    public static boolean shouldSwitchMidPlayback(FailoverObservation observation) {
        return FailoverScoringPolicy.shouldSwitch(observation);
    }
}
