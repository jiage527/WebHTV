package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Stores how much has been watched, in total and per day.
 *
 * <p>Totals are kept as one compact text value rather than a database table: the figure
 * is a single running number per day, it is updated on the tick that already writes
 * playback progress, and a schema migration for two counters would not earn its cost.
 * Only the most recent days are retained, since the purpose is a recent-history summary
 * rather than an archive.
 */
public final class ViewingStatsSetting {

    private static final String KEY_DAYS = "viewing_stats_days";
    private static final String KEY_TOTAL = "viewing_stats_total";
    /** Days beyond this are dropped, bounding the stored value. */
    static final int RETAINED_DAYS = 90;

    private ViewingStatsSetting() {
    }

    /**
     * The day key for an instant, in the device's own zone. A viewer's "today" is the local
     * one, so the totals match what the clock in the app showed them.
     */
    public static String dayKey(long epochMs) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()).toString();
    }

    public static ViewingStatsLedger.Totals getTotals() {
        return parse(Prefers.getString(KEY_DAYS, ""), getTotalMs());
    }

    /**
     * Adds watched time to one day and to the lifetime total.
     *
     * <p>The lifetime total is stored apart from the per-day map so that pruning an old
     * day cannot silently reduce it.
     */
    public static void add(String day, long ms) {
        if (day == null || day.isEmpty() || ms <= 0) return;
        ViewingStatsLedger.Totals totals = getTotals();
        totals.add(day, ms);
        Prefers.put(KEY_DAYS, format(prune(totals)));
        Prefers.put(KEY_TOTAL, totals.totalMs());
    }

    public static long getTotalMs() {
        return Math.max(0L, Prefers.getLong(KEY_TOTAL));
    }

    public static long getDayMs(String day) {
        return getTotals().dayMs(day);
    }

    /** Watched time for the current local day. */
    public static long getTodayMs() {
        return getDayMs(dayKey(System.currentTimeMillis()));
    }

    public static void reset() {
        Prefers.remove(KEY_DAYS);
        Prefers.remove(KEY_TOTAL);
    }

    /**
     * Serialises the per-day totals as {@code day=ms} pairs on separate lines, newest
     * first so pruning is a truncation of the tail.
     */
    static String format(ViewingStatsLedger.Totals totals) {
        StringBuilder builder = new StringBuilder();
        for (String day : totals.daysNewestFirst()) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(day).append('=').append(totals.dayMs(day));
        }
        return builder.toString();
    }

    /** Parses the stored pairs, ignoring entries that are malformed or out of range. */
    static ViewingStatsLedger.Totals parse(String stored, long totalMs) {
        ViewingStatsLedger.Totals totals = new ViewingStatsLedger.Totals();
        if (stored != null && !stored.isEmpty()) {
            for (String line : stored.split("\n")) {
                int split = line.indexOf('=');
                if (split <= 0 || split == line.length() - 1) continue;
                long ms = parseLong(line.substring(split + 1));
                // A zero or negative value can only come from a corrupted store; drop it
                // rather than let it distort the day it belongs to.
                if (ms <= 0) continue;
                totals.addDayOnly(line.substring(0, split), ms);
            }
        }
        // The stored lifetime total is authoritative and may exceed the retained days, so
        // it is applied over the parsed days rather than summed from them.
        totals.setTotalMs(totalMs);
        return totals;
    }

    /** Keeps the newest {@link #RETAINED_DAYS} days, preserving the lifetime total. */
    static ViewingStatsLedger.Totals prune(ViewingStatsLedger.Totals totals) {
        java.util.List<String> days = totals.daysNewestFirst();
        if (days.size() <= RETAINED_DAYS) return totals;
        ViewingStatsLedger.Totals kept = new ViewingStatsLedger.Totals();
        for (int i = 0; i < RETAINED_DAYS; i++) kept.addDayOnly(days.get(i), totals.dayMs(days.get(i)));
        // Pruning drops old days only; the lifetime total must survive it.
        kept.setTotalMs(totals.totalMs());
        return kept;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
