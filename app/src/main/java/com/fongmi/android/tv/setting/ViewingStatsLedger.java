package com.fongmi.android.tv.setting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates watched time from progress ticks.
 *
 * <p>A player reports its position roughly once a second while playing, so summing the
 * gaps between reports is the only way to measure time actually watched: the playback
 * <em>position</em> jumps by the seek amount when the viewer skips, and a paused player
 * keeps reporting the same position. This class therefore turns a stream of
 * (position, playing, wall clock) samples into watched milliseconds. It stays free of
 * Android and storage dependencies so the rule can be unit tested.
 *
 * <p>Time is credited per completed interval between two samples, never for an interval
 * that ends at a seek or a gap: a viewer who skips forward watched the seconds before the
 * skip, not the footage skipped over.
 */
public final class ViewingStatsLedger {

    /** A gap larger than this is a stall, not watching. */
    public static final long MAX_CREDITABLE_GAP_MS = 15_000L;
    /** The player's timer is not exact; a small discrepancy is not a seek. */
    private static final long DRIFT_TOLERANCE_MS = 2_000L;

    private long lastPositionMs = -1L;
    private long lastWallClockMs = -1L;
    private boolean lastPlaying;

    /**
     * Feeds one sample and returns the milliseconds to credit for the interval that ended
     * at it. The caller adds the result to its totals.
     *
     * @param positionMs the playback position the player reported
     * @param playing whether audio was actually being rendered
     * @param wallClockMs when the sample was taken, from a monotonic clock
     */
    public long sample(long positionMs, boolean playing, long wallClockMs) {
        long previousPosition = lastPositionMs;
        long previousClock = lastWallClockMs;
        boolean previousPlaying = lastPlaying;
        lastPositionMs = positionMs;
        lastWallClockMs = wallClockMs;
        lastPlaying = playing;

        // The first sample only establishes a baseline: no interval has elapsed yet, and
        // crediting one would add all the time since the previous session.
        if (previousPosition < 0 || previousClock < 0) return 0L;
        // A paused viewer watched nothing, whatever the wall clock did. Requiring both ends
        // keeps a pause from being counted once as it begins.
        if (!playing || !previousPlaying) return 0L;
        long elapsed = wallClockMs - previousClock;
        if (elapsed <= 0) return 0L;
        // A long gap means the app was frozen, the device slept, or the stream stalled.
        // Crediting it would inflate the total with time nobody watched.
        if (elapsed > MAX_CREDITABLE_GAP_MS) return 0L;
        long advanced = positionMs - previousPosition;
        // Going backwards is a backwards seek; a small negative drift is timer noise.
        if (advanced < -DRIFT_TOLERANCE_MS) return 0L;
        // Playback cannot advance faster than the wall clock beyond the timer's own drift,
        // so a large forward jump is a forward seek.
        if (advanced > elapsed + DRIFT_TOLERANCE_MS) return 0L;
        // Credit the smaller of the two: whichever is smaller is the part the clock and the
        // position agree was actually played.
        if (advanced <= 0) return elapsed;
        return Math.min(elapsed, advanced);
    }

    /** Forgets the baseline so the next sample starts a fresh interval. */
    public void reset() {
        lastPositionMs = -1L;
        lastWallClockMs = -1L;
        lastPlaying = false;
    }

    /** Watched time per day, keyed {@code yyyy-MM-dd} in the device's own zone. */
    public static final class Totals {

        private final Map<String, Long> byDay = new LinkedHashMap<>();
        private long totalMs;

        /** Adds {@code ms} to one day and to the lifetime total. */
        public void add(String day, long ms) {
            if (day == null || day.isEmpty() || ms <= 0) return;
            byDay.merge(day, ms, Long::sum);
            totalMs += ms;
        }

        /**
         * Adds {@code ms} to one day without moving the lifetime total, for loading a
         * stored map whose lifetime figure is authoritative and held separately.
         */
        void addDayOnly(String day, long ms) {
            if (day == null || day.isEmpty() || ms <= 0) return;
            byDay.merge(day, ms, Long::sum);
        }

        /** Overrides the lifetime total, which may exceed the days still retained. */
        void setTotalMs(long value) {
            totalMs = Math.max(0L, value);
        }

        public long totalMs() {
            return totalMs;
        }

        public Map<String, Long> byDay() {
            return new LinkedHashMap<>(byDay);
        }

        /** The watched time recorded for one day, or zero when nothing was watched. */
        public long dayMs(String day) {
            Long value = byDay.get(day);
            return value == null ? 0L : value;
        }

        public Totals copy() {
            Totals copy = new Totals();
            copy.byDay.putAll(byDay);
            copy.totalMs = totalMs;
            return copy;
        }

        /** The days, newest first, which is how a summary is read. */
        public List<String> daysNewestFirst() {
            List<String> days = new java.util.ArrayList<>(byDay.keySet());
            days.sort((left, right) -> right.compareTo(left));
            return days;
        }
    }

    /** Formats a duration as a compact, readable amount such as {@code 2小时13分}. */
    public static String format(long ms) {
        if (ms <= 0) return "0分";
        long totalMinutes = ms / 60_000L;
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        if (hours <= 0) return minutes + "分";
        if (minutes <= 0) return hours + "小时";
        return hours + "小时" + minutes + "分";
    }
}
