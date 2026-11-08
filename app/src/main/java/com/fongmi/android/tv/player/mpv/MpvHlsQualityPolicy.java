package com.fongmi.android.tv.player.mpv;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the variant ladder of the current HLS stream into a menu and a decision.
 *
 * <p>mpv's {@code hls-bitrate} is read when the demuxer opens the stream, not while
 * it is running: {@code demux/demux_lavf.c} selects the initial edition by taking the
 * highest variant bitrate at or below the option, falling back to the lowest variant
 * when none qualifies. The option therefore cannot act as a live switch, and the
 * fallback also means an exact number is not always the row a user asked for. This
 * class only computes what to show and which option value a row represents, so it
 * stays free of player and Android dependencies and can be unit tested.
 */
public final class MpvHlsQualityPolicy {

    /** mpv's own value for "highest bitrate" (it resolves to INT_MAX). */
    public static final String OPTION_HIGHEST = "max";
    /** mpv's own value for "lowest bitrate" (it resolves to 0, matching no variant). */
    public static final String OPTION_LOWEST = "min";

    private MpvHlsQualityPolicy() {
    }

    /**
     * One selectable quality. {@code option} is the mpv value that selects it, and an
     * empty {@code label} means the entry has no manifest detail of its own.
     */
    public record Choice(
            String option,
            String label,
            long bitsPerSecond,
            int width,
            int height) {

        public Choice {
            option = option == null ? "" : option.trim();
            label = label == null ? "" : label;
            bitsPerSecond = Math.max(0, bitsPerSecond);
            width = Math.max(0, width);
            height = Math.max(0, height);
        }

        public boolean isHighest() {
            return OPTION_HIGHEST.equals(option);
        }

        public boolean isLowest() {
            return OPTION_LOWEST.equals(option);
        }
    }

    /**
     * Builds the menu for one ladder, highest first.
     *
     * <p>Rows are ordered by bitrate rather than by manifest order, because a menu is
     * read top to bottom and a viewer compares "higher or lower", not the order the
     * server happened to list. Duplicate rates collapse: two rows selecting the same
     * edition would be indistinguishable.
     *
     * <p>The top row carries mpv's {@code max} instead of the top variant's number.
     * Because an exact value means "at or below", a manifest that under-declares its
     * real top rate is only ever truly pinned by {@code max}. The bottom row likewise
     * carries {@code min} instead of the lowest number: both select the same edition,
     * but {@code min} survives a rate change and is what mpv falls back to anyway.
     * Middle rows keep their exact numbers, which is the only way to name them.
     */
    public static List<Choice> choices(List<MpvHlsVariantPolicy.Variant> variants) {
        List<MpvHlsVariantPolicy.Variant> sorted = distinctRatesDescending(variants);
        if (sorted.isEmpty()) return List.of();
        List<Choice> choices = new ArrayList<>(sorted.size());
        int last = sorted.size() - 1;
        for (int i = 0; i < sorted.size(); i++) {
            MpvHlsVariantPolicy.Variant variant = sorted.get(i);
            String option = i == 0 ? OPTION_HIGHEST : i == last ? OPTION_LOWEST : Long.toString(variant.selectionBitsPerSecond());
            choices.add(new Choice(option, label(variant), variant.selectionBitsPerSecond(), variant.width(), variant.height()));
        }
        return List.copyOf(choices);
    }

    /**
     * The row that should appear selected for a stored option.
     *
     * <p>A stored number that no longer exists in the ladder (the stream changed
     * between sessions) selects the closest row at or below it, and when nothing
     * qualifies it selects the lowest row, exactly mirroring mpv's own resolution.
     */
    public static int selectedIndex(List<Choice> choices, String option, long activeBitsPerSecond) {
        List<Choice> safe = choices == null ? List.of() : choices;
        if (safe.isEmpty()) return -1;
        String value = option == null ? "" : option.trim();
        for (int i = 0; i < safe.size(); i++) {
            if (!value.isEmpty() && value.equals(safe.get(i).option())) return i;
        }
        long bits = value.isEmpty() ? Math.max(0, activeBitsPerSecond) : parse(value);
        if (bits <= 0) return lowestIndex(safe);
        int closest = -1;
        for (int i = 0; i < safe.size(); i++) {
            long entry = safe.get(i).bitsPerSecond();
            if (entry <= 0 || entry > bits) continue;
            if (closest < 0 || entry > safe.get(closest).bitsPerSecond()) closest = i;
        }
        return closest < 0 ? lowestIndex(safe) : closest;
    }

    /**
     * True when the app can offer a real choice, which needs at least two distinct
     * declared rates. A manifest listing a single variant has nothing to choose:
     * {@code max} and {@code min} both resolve to that one edition.
     */
    public static boolean canChoose(List<MpvHlsVariantPolicy.Variant> variants) {
        return distinctRatesDescending(variants).size() > 1;
    }

    /** The description of one variant: resolution plus bitrate, when either is known. */
    public static String label(MpvHlsVariantPolicy.Variant variant) {
        if (variant == null) return "";
        return label(variant.selectionBitsPerSecond(), variant.width(), variant.height());
    }

    /**
     * A human-readable description. Resolution is only shown when the manifest
     * declared one, because inventing a size would hide that the manifest did not
     * describe the stream.
     */
    public static String label(long bitsPerSecond, int width, int height) {
        List<String> parts = new ArrayList<>(2);
        if (width > 0 && height > 0) parts.add(width + "x" + height);
        String rate = formatBitsPerSecond(bitsPerSecond);
        if (!rate.isEmpty()) parts.add(rate);
        return String.join(" · ", parts);
    }

    /** Formats a bitrate as Mbps when it is large enough to be read that way. */
    public static String formatBitsPerSecond(long bitsPerSecond) {
        if (bitsPerSecond <= 0) return "";
        if (bitsPerSecond >= 1_000_000L) {
            // One decimal is enough: manifest rates are rarely precise beyond it, and a
            // longer fraction would make every row a different width.
            return String.format(Locale.US, "%.1fMbps", bitsPerSecond / 1_000_000.0);
        }
        return Math.max(1, bitsPerSecond / 1_000L) + "kbps";
    }

    /**
     * True for the values mpv accepts for {@code hls-bitrate}: {@code no}, {@code min},
     * {@code max}, or a non-negative rate inside mpv's integer range. The on-device
     * staging check accepts the same set, so a value passing here cannot be rejected
     * there.
     */
    public static boolean isValidOption(String value) {
        if (value == null || value.isBlank()) return false;
        String option = value.trim();
        if (OPTION_HIGHEST.equals(option) || OPTION_LOWEST.equals(option) || "no".equals(option)) return true;
        // A malformed value must be rejected outright: a rate parsed as 0 would
        // otherwise be indistinguishable from a legitimate "lowest possible".
        return isNumeric(option) && parse(option) <= Integer.MAX_VALUE;
    }

    /**
     * Distinct positive rates, highest first. The first declaration of a rate wins,
     * because a ladder repeating one rate at two sizes is malformed and the first
     * entry is what the demuxer would have carried.
     */
    private static List<MpvHlsVariantPolicy.Variant> distinctRatesDescending(List<MpvHlsVariantPolicy.Variant> variants) {
        Map<Long, MpvHlsVariantPolicy.Variant> byBitrate = new LinkedHashMap<>();
        List<MpvHlsVariantPolicy.Variant> safe = variants == null ? List.of() : variants;
        for (MpvHlsVariantPolicy.Variant variant : safe) {
            if (variant == null) continue;
            long bits = variant.selectionBitsPerSecond();
            if (bits <= 0) continue;
            byBitrate.putIfAbsent(bits, variant);
        }
        List<MpvHlsVariantPolicy.Variant> sorted = new ArrayList<>(byBitrate.values());
        // Descending: the highest quality is what a viewer looks for first.
        sorted.sort(Comparator.comparingLong(MpvHlsVariantPolicy.Variant::selectionBitsPerSecond).reversed());
        return sorted;
    }

    /** The index of the "lowest bitrate" row, or the last row as a fallback. */
    private static int lowestIndex(List<Choice> choices) {
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).isLowest()) return i;
        }
        return Math.max(0, choices.size() - 1);
    }

    /** True when every character is a digit, so the value is a plain rate. */
    private static boolean isNumeric(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }

    private static long parse(String value) {
        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
