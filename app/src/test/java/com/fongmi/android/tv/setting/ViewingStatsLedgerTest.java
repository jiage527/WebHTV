package com.fongmi.android.tv.setting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

/**
 * Watched time is inferred from progress ticks, so the rules that decide whether an
 * interval counts are verified here rather than only by watching a video.
 */
public class ViewingStatsLedgerTest {

    private static final long T0 = 1_000_000L;

    @Test
    public void playingIntervalsAreCredited() {
        ViewingStatsLedger ledger = new ViewingStatsLedger();
        ledger.sample(0, true, T0);

        assertEquals(1_000L, ledger.sample(1_000, true, T0 + 1_000));
        assertEquals(1_000L, ledger.sample(2_000, true, T0 + 2_000));
    }

    /** The first sample has no earlier interval, so it must not credit anything. */
    @Test
    public void firstSampleCreditsNothing() {
        ViewingStatsLedger ledger = new ViewingStatsLedger();
        assertEquals(0L, ledger.sample(30_000, true, T0));
    }

    @Test
    public void pausedIntervalsAreNotCredited() {
        ViewingStatsLedger ledger = new ViewingStatsLedger();
        ledger.sample(1_000, true, T0);

        // Paused at both ends: the wall clock moved, but nothing was watched.
        assertEquals(0L, ledger.sample(1_000, false, T0 + 5_000));
        // Still paused.
        assertEquals(0L, ledger.sample(1_000, false, T0 + 10_000));
        // The pause must also not be credited on resume.
        assertEquals(0L, ledger.sample(1_000, true, T0 + 15_000));
        assertEquals(1_000L, ledger.sample(2_000, true, T0 + 16_000));
    }

    /** Skipping forward must not credit the footage skipped over. */
    @Test
    public void forwardSeekIsNotCredited() {
        ViewingStatsLedger ledger = new ViewingStatsLedger();
        ledger.sample(10_000, true, T0);

        // One second of wall clock, but the position jumped ten minutes.
        assertEquals(0L, ledger.sample(610_000, true, T0 + 1_000));
        // Watching then continues normally from the new position.
        assertEquals(1_000L, ledger.sample(611_000, true, T0 + 2_000));
    }

    @Test
    public void backwardSeekIsNotCredited() {
        ViewingStatsLedger ledger = new ViewingStatsLedger();
        ledger.sample(600_000, true, T0);

        assertEquals(0L, ledger.sample(10_000, true, T0 + 1_000));
        assertEquals(1_000L, ledger.sample(11_000, true, T0 + 2_000));
    }

    /** A frozen app, a sleeping device or a stalled stream must not inflate the total. */
    @Test
    public void longGapIsNotCredited() {
        ViewingStatsLedger ledger = new ViewingStatsLedger();
        ledger.sample(1_000, true, T0);

        assertEquals(0L, ledger.sample(1_000 + ViewingStatsLedger.MAX_CREDITABLE_GAP_MS + 1, true, T0 + ViewingStatsLedger.MAX_CREDITABLE_GAP_MS + 1));
    }

    @Test
    public void gapAtTheCreditableLimitIsStillCounted() {
        ViewingStatsLedger ledger = new ViewingStatsLedger();
        ledger.sample(1_000, true, T0);

        long credited = ledger.sample(1_000 + ViewingStatsLedger.MAX_CREDITABLE_GAP_MS, true, T0 + ViewingStatsLedger.MAX_CREDITABLE_GAP_MS);
        assertEquals(ViewingStatsLedger.MAX_CREDITABLE_GAP_MS, credited);
    }

    /** The player's timer slips, so a small disagreement is not a seek. */
    @Test
    public void smallTimerDriftIsTolerated() {
        ViewingStatsLedger ledger = new ViewingStatsLedger();
        ledger.sample(1_000, true, T0);

        // The position advanced slightly faster than the clock: still watching.
        assertEquals(1_000L, ledger.sample(2_500, true, T0 + 1_000));
    }

    @Test
    public void repeatedPositionStillCountsWhilePlaying() {
        ViewingStatsLedger ledger = new ViewingStatsLedger();
        ledger.sample(5_000, true, T0);

        // A stalled decoder while the player still reports "playing": the clock advanced,
        // so the time was watched even though the position did not move.
        assertEquals(1_000L, ledger.sample(5_000, true, T0 + 1_000));
    }

    @Test
    public void resetStartsAFreshBaseline() {
        ViewingStatsLedger ledger = new ViewingStatsLedger();
        ledger.sample(1_000, true, T0);
        ledger.reset();

        assertEquals(0L, ledger.sample(900_000, true, T0 + 500_000));
        assertEquals(1_000L, ledger.sample(901_000, true, T0 + 501_000));
    }

    @Test
    public void totalsAccumulatePerDayAndOverall() {
        ViewingStatsLedger.Totals totals = new ViewingStatsLedger.Totals();
        totals.add("2026-08-19", 60_000);
        totals.add("2026-08-20", 90_000);
        totals.add("2026-08-19", 30_000);

        assertEquals(180_000, totals.totalMs());
        assertEquals(90_000, totals.dayMs("2026-08-19"));
        assertEquals(90_000, totals.dayMs("2026-08-20"));
        assertEquals(0, totals.dayMs("2026-08-21"));
    }

    @Test
    public void totalsIgnoreEmptyOrNonPositiveAdditions() {
        ViewingStatsLedger.Totals totals = new ViewingStatsLedger.Totals();
        totals.add(null, 1_000);
        totals.add("", 1_000);
        totals.add("2026-08-20", 0);
        totals.add("2026-08-20", -5_000);

        assertEquals(0, totals.totalMs());
        assertTrue(totals.byDay().isEmpty());
    }

    @Test
    public void daysAreListedNewestFirst() {
        ViewingStatsLedger.Totals totals = new ViewingStatsLedger.Totals();
        totals.add("2026-08-18", 1_000);
        totals.add("2026-08-20", 1_000);
        totals.add("2026-08-19", 1_000);

        assertEquals(java.util.List.of("2026-08-20", "2026-08-19", "2026-08-18"), totals.daysNewestFirst());
    }

    @Test
    public void copyIsIndependentOfTheOriginal() {
        ViewingStatsLedger.Totals totals = new ViewingStatsLedger.Totals();
        totals.add("2026-08-20", 1_000);

        ViewingStatsLedger.Totals copy = totals.copy();
        copy.add("2026-08-20", 5_000);

        assertEquals(1_000, totals.totalMs());
        assertEquals(6_000, copy.totalMs());
    }

    @Test
    public void dayOnlyAdditionLeavesTheLifetimeTotalAlone() {
        ViewingStatsLedger.Totals totals = new ViewingStatsLedger.Totals();
        totals.addDayOnly("2026-08-20", 4_000);
        totals.setTotalMs(999_000);

        assertEquals(999_000, totals.totalMs());
        assertEquals(4_000, totals.dayMs("2026-08-20"));
    }

    @Test
    public void durationFormattingIsReadable() {
        assertEquals("0分", ViewingStatsLedger.format(0));
        assertEquals("0分", ViewingStatsLedger.format(-1));
        assertEquals("1分", ViewingStatsLedger.format(60_000));
        assertEquals("59分", ViewingStatsLedger.format(59 * 60_000L));
        assertEquals("1小时", ViewingStatsLedger.format(60 * 60_000L));
        assertEquals("2小时13分", ViewingStatsLedger.format((2 * 60 + 13) * 60_000L));
    }

    @Test
    public void storedTotalsRoundTrip() {
        ViewingStatsLedger.Totals totals = new ViewingStatsLedger.Totals();
        totals.add("2026-08-19", 120_000);
        totals.add("2026-08-20", 240_000);

        String stored = ViewingStatsSetting.format(totals);
        ViewingStatsLedger.Totals parsed = ViewingStatsSetting.parse(stored, totals.totalMs());

        assertEquals(360_000, parsed.totalMs());
        assertEquals(120_000, parsed.dayMs("2026-08-19"));
        assertEquals(240_000, parsed.dayMs("2026-08-20"));
    }

    @Test
    public void storedTotalsKeepALifetimeTotalBeyondRetainedDays() {
        // A lifetime figure larger than the days still stored must survive parsing.
        ViewingStatsLedger.Totals parsed = ViewingStatsSetting.parse("2026-08-20=60000", 10_000_000);
        assertEquals(10_000_000, parsed.totalMs());
        assertEquals(60_000, parsed.dayMs("2026-08-20"));
    }

    @Test
    public void malformedStoredEntriesAreIgnored() {
        ViewingStatsLedger.Totals parsed = ViewingStatsSetting.parse("garbage\n=100\n2026-08-20=0\n2026-08-19=-5\n2026-08-18=abc\n2026-08-17=70000", 70_000);

        assertEquals(70_000, parsed.dayMs("2026-08-17"));
        assertEquals(0, parsed.dayMs("2026-08-20"));
        assertEquals(0, parsed.dayMs("2026-08-18"));
        assertEquals(1, parsed.byDay().size());
    }

    @Test
    public void emptyStoredValueParsesToNothing() {
        assertEquals(0, ViewingStatsSetting.parse(null, 0).totalMs());
        assertTrue(ViewingStatsSetting.parse("", 0).byDay().isEmpty());
    }

    @Test
    public void pruningKeepsTheNewestDaysAndTheLifetimeTotal() {
        ViewingStatsLedger.Totals totals = new ViewingStatsLedger.Totals();
        // More days than are retained, each with a distinguishable value.
        for (int i = 0; i < ViewingStatsSetting.RETAINED_DAYS + 10; i++) {
            String day = String.format(java.util.Locale.US, "2026-%02d-%02d", 1 + i / 28, 1 + i % 28);
            totals.add(day, 60_000);
        }
        long lifetime = totals.totalMs();

        ViewingStatsLedger.Totals pruned = ViewingStatsSetting.prune(totals);

        assertEquals(ViewingStatsSetting.RETAINED_DAYS, pruned.byDay().size());
        // Pruning old days must not reduce the lifetime figure.
        assertEquals(lifetime, pruned.totalMs());
        // The newest day survives, the oldest does not.
        assertTrue(pruned.dayMs(totals.daysNewestFirst().get(0)) > 0);
        assertEquals(0, pruned.dayMs(totals.daysNewestFirst().get(totals.daysNewestFirst().size() - 1)));
    }
}
