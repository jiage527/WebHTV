package com.fongmi.android.tv.setting;

/**
 * Turns the player's progress ticks into stored watched time.
 *
 * <p>Called on the tick that already reports playback progress, so it adds no timer of
 * its own. Credits are accumulated in memory and written in batches: the tick runs once
 * a second, and writing an app preference on every one of them would be pointless work.
 * A flush also happens when a session ends, so a viewer who stops is never left with
 * unrecorded time.
 *
 * <p>Watching is not counted in incognito mode, which exists precisely so that playback
 * leaves no trace.
 */
public final class ViewingStatsSession {

    /** How much credited time to gather before writing, balancing loss and write cost. */
    private static final long FLUSH_THRESHOLD_MS = 15_000L;

    private static final ViewingStatsSession INSTANCE = new ViewingStatsSession();

    private final ViewingStatsLedger ledger = new ViewingStatsLedger();
    private long pendingMs;

    private ViewingStatsSession() {
    }

    public static ViewingStatsSession get() {
        return INSTANCE;
    }

    /**
     * Feeds one progress sample.
     *
     * @param positionMs the playback position the player reported
     * @param playing whether playback was actually running
     * @param wallClockMs a monotonic clock reading for this sample
     */
    public synchronized void onProgress(long positionMs, boolean playing, long wallClockMs) {
        if (Setting.isIncognito()) {
            // Incognito must not record anything, and the baseline must be dropped so that
            // leaving incognito mid-playback cannot credit the hidden interval.
            ledger.reset();
            return;
        }
        long credited = ledger.sample(positionMs, playing, wallClockMs);
        if (credited <= 0) return;
        pendingMs += credited;
        if (pendingMs >= FLUSH_THRESHOLD_MS) flush();
    }

    /** Ends the current session, writing whatever is still pending. */
    public synchronized void onSessionEnd() {
        flush();
        ledger.reset();
    }

    /** Writes pending time and returns the amount written. */
    public synchronized long flush() {
        if (pendingMs <= 0) return 0L;
        long amount = pendingMs;
        pendingMs = 0L;
        // Attribute the batch to today: a batch is at most a few seconds of viewing, so it
        // cannot meaningfully straddle midnight, and splitting it would add no accuracy.
        ViewingStatsSetting.add(ViewingStatsSetting.dayKey(System.currentTimeMillis()), amount);
        return amount;
    }

    /** Drops the in-memory baseline and pending time without writing it. */
    public synchronized void discard() {
        pendingMs = 0L;
        ledger.reset();
    }
}
