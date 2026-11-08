package com.fongmi.android.tv.player.mpv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * The quality menu is built from server-declared numbers, so ordering, duplicate
 * handling and the "which row is selected" rule are verified here rather than only
 * on a device.
 */
public class MpvHlsQualityPolicyTest {

    private static MpvHlsVariantPolicy.Variant variant(long bits, int width, int height) {
        return new MpvHlsVariantPolicy.Variant(bits, 0, width, height);
    }

    @Test
    public void threeRatesBecomeHighestMiddleAndLowestRows() {
        List<MpvHlsQualityPolicy.Choice> choices = MpvHlsQualityPolicy.choices(List.of(
                variant(2_000_000, 1280, 720),
                variant(8_000_000, 1920, 1080),
                variant(500_000, 640, 360)));

        assertEquals(3, choices.size());
        assertEquals(MpvHlsQualityPolicy.OPTION_HIGHEST, choices.get(0).option());
        assertEquals(8_000_000, choices.get(0).bitsPerSecond());
        assertEquals("1920x1080 · 8.0Mbps", choices.get(0).label());
        assertEquals("2000000", choices.get(1).option());
        assertEquals(MpvHlsQualityPolicy.OPTION_LOWEST, choices.get(2).option());
        assertEquals(500_000, choices.get(2).bitsPerSecond());
        assertTrue(choices.get(2).isLowest());
    }

    /**
     * mpv reads an exact number as "at or below", so the top row must use max instead of
     * repeating the top rate, which a manifest may under-declare.
     */
    @Test
    public void topRowLocksHighestRatherThanNamingTheTopRate() {
        List<MpvHlsQualityPolicy.Choice> choices = MpvHlsQualityPolicy.choices(List.of(
                variant(4_000_000, 1920, 1080),
                variant(1_000_000, 1280, 720)));

        assertEquals(2, choices.size());
        assertEquals(MpvHlsQualityPolicy.OPTION_HIGHEST, choices.get(0).option());
        assertTrue(choices.get(0).isHighest());
        // The top rate must not also appear as a bare number, or it would be ambiguous.
        for (MpvHlsQualityPolicy.Choice choice : choices) {
            assertFalse("4000000".equals(choice.option()));
        }
    }

    /**
     * With two rates the bottom row is min, not the low rate's number: both select the
     * same edition, so a second row for it would be a duplicate choice.
     */
    @Test
    public void twoRatesGiveExactlyTwoDistinctRows() {
        List<MpvHlsQualityPolicy.Choice> choices = MpvHlsQualityPolicy.choices(List.of(
                variant(4_000_000, 1920, 1080),
                variant(1_000_000, 1280, 720)));

        assertEquals(2, choices.size());
        assertEquals(MpvHlsQualityPolicy.OPTION_HIGHEST, choices.get(0).option());
        assertEquals(MpvHlsQualityPolicy.OPTION_LOWEST, choices.get(1).option());
        assertTrue(MpvHlsQualityPolicy.canChoose(List.of(
                variant(4_000_000, 1920, 1080), variant(1_000_000, 1280, 720))));
    }

    @Test
    public void threeRatesAreChoosableAndKeepAMiddleRateByName() {
        List<MpvHlsVariantPolicy.Variant> variants = List.of(
                variant(8_000_000, 1920, 1080),
                variant(3_000_000, 1280, 720),
                variant(800_000, 640, 360));

        assertTrue(MpvHlsQualityPolicy.canChoose(variants));
        assertEquals("3000000", MpvHlsQualityPolicy.choices(variants).get(1).option());
    }

    @Test
    public void duplicateBitratesCollapseToOneRow() {
        List<MpvHlsQualityPolicy.Choice> choices = MpvHlsQualityPolicy.choices(List.of(
                variant(3_000_000, 1920, 1080),
                variant(3_000_000, 1280, 720)));

        // A single distinct rate is not a choice: max and min resolve to the same edition.
        assertEquals(1, choices.size());
        assertEquals("1920x1080 · 3.0Mbps", choices.get(0).label());
        assertFalse(MpvHlsQualityPolicy.canChoose(List.of(
                variant(3_000_000, 1920, 1080), variant(3_000_000, 1280, 720))));
    }

    @Test
    public void variantWithoutBitrateIsIgnored() {
        List<MpvHlsQualityPolicy.Choice> choices = MpvHlsQualityPolicy.choices(List.of(
                variant(0, 1920, 1080),
                variant(5_000_000, 1920, 1080)));

        assertEquals(1, choices.size());
        assertEquals(5_000_000, choices.get(0).bitsPerSecond());
    }

    /** A stream with no declared ladder must not produce a menu at all. */
    @Test
    public void noVariantsOfferNoMenu() {
        assertTrue(MpvHlsQualityPolicy.choices(List.of()).isEmpty());
        assertTrue(MpvHlsQualityPolicy.choices(null).isEmpty());
        assertFalse(MpvHlsQualityPolicy.canChoose(List.of()));
        assertFalse(MpvHlsQualityPolicy.canChoose(null));
    }

    @Test
    public void oneVariantIsNotWorthAMenu() {
        List<MpvHlsVariantPolicy.Variant> variants = List.of(variant(2_000_000, 1280, 720));
        assertEquals(1, MpvHlsQualityPolicy.choices(variants).size());
        assertFalse(MpvHlsQualityPolicy.canChoose(variants));
    }

    @Test
    public void storedOptionSelectsItsOwnRow() {
        List<MpvHlsQualityPolicy.Choice> choices = MpvHlsQualityPolicy.choices(List.of(
                variant(2_000_000, 1280, 720),
                variant(8_000_000, 1920, 1080),
                variant(500_000, 640, 360)));

        assertEquals(0, MpvHlsQualityPolicy.selectedIndex(choices, MpvHlsQualityPolicy.OPTION_HIGHEST, 0));
        assertEquals(1, MpvHlsQualityPolicy.selectedIndex(choices, "2000000", 0));
        assertEquals(2, MpvHlsQualityPolicy.selectedIndex(choices, MpvHlsQualityPolicy.OPTION_LOWEST, 0));
        // The lowest rate is named by min, so its bare number resolves to the same row.
        assertEquals(2, MpvHlsQualityPolicy.selectedIndex(choices, "500000", 0));
    }

    /** A stored rate absent from a changed ladder falls back to the closest rate at or below. */
    @Test
    public void staleRateSelectsTheClosestLowerRow() {
        List<MpvHlsQualityPolicy.Choice> choices = MpvHlsQualityPolicy.choices(List.of(
                variant(8_000_000, 1920, 1080),
                variant(2_000_000, 1280, 720)));

        assertEquals(1, MpvHlsQualityPolicy.selectedIndex(choices, "3000000", 0));
    }

    /** Below every declared rate, mpv falls back to the lowest variant, not the highest. */
    @Test
    public void rateBelowTheLadderSelectsTheLowestRow() {
        List<MpvHlsQualityPolicy.Choice> choices = MpvHlsQualityPolicy.choices(List.of(
                variant(8_000_000, 1920, 1080),
                variant(2_000_000, 1280, 720)));

        assertEquals(1, MpvHlsQualityPolicy.selectedIndex(choices, "100000", 0));
        assertTrue(choices.get(1).isLowest());
    }

    @Test
    public void liveBitrateIsUsedWhenNothingIsStored() {
        List<MpvHlsQualityPolicy.Choice> choices = MpvHlsQualityPolicy.choices(List.of(
                variant(8_000_000, 1920, 1080),
                variant(2_000_000, 1280, 720)));

        assertEquals(0, MpvHlsQualityPolicy.selectedIndex(choices, "", 9_000_000));
        assertEquals(1, MpvHlsQualityPolicy.selectedIndex(choices, "", 2_000_000));
        // Unknown live value must still highlight a real row.
        assertEquals(1, MpvHlsQualityPolicy.selectedIndex(choices, "", 0));
    }

    @Test
    public void emptyMenuHasNoSelection() {
        assertEquals(-1, MpvHlsQualityPolicy.selectedIndex(List.of(), "max", 0));
        assertEquals(-1, MpvHlsQualityPolicy.selectedIndex(null, "max", 0));
    }

    @Test
    public void bitrateFormattingStaysReadable() {
        assertEquals("8.0Mbps", MpvHlsQualityPolicy.formatBitsPerSecond(8_000_000));
        assertEquals("15.0Mbps", MpvHlsQualityPolicy.formatBitsPerSecond(15_000_000));
        assertEquals("500kbps", MpvHlsQualityPolicy.formatBitsPerSecond(500_000));
        assertEquals("", MpvHlsQualityPolicy.formatBitsPerSecond(0));
    }

    @Test
    public void labelOmitsUnknownResolution() {
        assertEquals("4.0Mbps", MpvHlsQualityPolicy.label(variant(4_000_000, 0, 0)));
        assertEquals("1920x1080", MpvHlsQualityPolicy.label(variant(0, 1920, 1080)));
    }

    @Test
    public void optionValidationMatchesWhatMpvAccepts() {
        assertTrue(MpvHlsQualityPolicy.isValidOption("max"));
        assertTrue(MpvHlsQualityPolicy.isValidOption("min"));
        assertTrue(MpvHlsQualityPolicy.isValidOption("no"));
        assertTrue(MpvHlsQualityPolicy.isValidOption("8000000"));
        assertTrue(MpvHlsQualityPolicy.isValidOption("0"));
        assertTrue(MpvHlsQualityPolicy.isValidOption(Integer.toString(Integer.MAX_VALUE)));
        assertFalse(MpvHlsQualityPolicy.isValidOption(""));
        assertFalse(MpvHlsQualityPolicy.isValidOption("   "));
        assertFalse(MpvHlsQualityPolicy.isValidOption(null));
        // A malformed value must be rejected, not read as a rate of zero.
        assertFalse(MpvHlsQualityPolicy.isValidOption("8mbps"));
        assertFalse(MpvHlsQualityPolicy.isValidOption("-1"));
        assertFalse(MpvHlsQualityPolicy.isValidOption("2147483648"));
    }
}
