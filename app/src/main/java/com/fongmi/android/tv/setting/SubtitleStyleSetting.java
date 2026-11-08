package com.fongmi.android.tv.setting;

import android.graphics.Color;

import com.github.catvod.utils.Prefers;

/**
 * The app-owned subtitle appearance, independent of the system caption style.
 *
 * <p>Everything here is opt-in. While {@link #isEnabled()} is false the player
 * keeps using exactly the style it used before this setting existed, so an
 * upgrade never changes how a subtitle already looks on a user's device. The
 * values are stored as plain preferences and are normalised on read, because a
 * preference written by an older build (or edited by hand) must never produce an
 * unrenderable style.
 */
public final class SubtitleStyleSetting {

    public static final int OUTLINE_NONE = 0;
    public static final int OUTLINE_OUTLINE = 1;
    public static final int OUTLINE_SHADOW = 2;
    public static final int OUTLINE_BOX = 3;

    public static final int FONT_DEFAULT = 0;
    public static final int FONT_SANS = 1;
    public static final int FONT_SERIF = 2;
    public static final int FONT_MONO = 3;

    public static final int DEFAULT_FONT_COLOR = Color.WHITE;
    public static final int DEFAULT_OUTLINE_COLOR = Color.BLACK;
    public static final int DEFAULT_BACKGROUND_COLOR = Color.BLACK;

    private static final float DEFAULT_OUTLINE_SIZE = 3f;
    private static final int DEFAULT_BACKGROUND_OPACITY = 0;
    /** Matches the fixed top padding the secondary subtitle used before this setting. */
    public static final float DEFAULT_SECONDARY_POSITION = 0.08f;

    private SubtitleStyleSetting() {
    }

    public static boolean isEnabled() {
        return Prefers.getBoolean("subtitle_style_enabled");
    }

    public static void putEnabled(boolean enabled) {
        Prefers.put("subtitle_style_enabled", enabled);
    }

    public static int getFontColor() {
        return color("subtitle_style_font_color", DEFAULT_FONT_COLOR);
    }

    public static void putFontColor(int value) {
        Prefers.put("subtitle_style_font_color", value);
    }

    public static int getOutlineColor() {
        return color("subtitle_style_outline_color", DEFAULT_OUTLINE_COLOR);
    }

    public static void putOutlineColor(int value) {
        Prefers.put("subtitle_style_outline_color", value);
    }

    public static int getBackgroundColor() {
        return color("subtitle_style_background_color", DEFAULT_BACKGROUND_COLOR);
    }

    public static void putBackgroundColor(int value) {
        Prefers.put("subtitle_style_background_color", value);
    }

    public static int getOutlineStyle() {
        return clamp(Prefers.getInt("subtitle_style_outline_style", OUTLINE_OUTLINE), OUTLINE_NONE, OUTLINE_BOX);
    }

    public static void putOutlineStyle(int value) {
        Prefers.put("subtitle_style_outline_style", clamp(value, OUTLINE_NONE, OUTLINE_BOX));
    }

    public static float getOutlineSize() {
        return clamp(Prefers.getFloat("subtitle_style_outline_size", DEFAULT_OUTLINE_SIZE), 0f, 8f);
    }

    public static void putOutlineSize(float value) {
        Prefers.put("subtitle_style_outline_size", clamp(value, 0f, 8f));
    }

    public static int getBackgroundOpacity() {
        return clamp(Prefers.getInt("subtitle_style_background_opacity", DEFAULT_BACKGROUND_OPACITY), 0, 100);
    }

    public static void putBackgroundOpacity(int value) {
        Prefers.put("subtitle_style_background_opacity", clamp(value, 0, 100));
    }

    public static boolean isBold() {
        return Prefers.getBoolean("subtitle_style_bold");
    }

    public static void putBold(boolean value) {
        Prefers.put("subtitle_style_bold", value);
    }

    public static boolean isItalic() {
        return Prefers.getBoolean("subtitle_style_italic");
    }

    public static void putItalic(boolean value) {
        Prefers.put("subtitle_style_italic", value);
    }

    public static int getFontFamily() {
        return clamp(Prefers.getInt("subtitle_style_font_family", FONT_DEFAULT), FONT_DEFAULT, FONT_MONO);
    }

    public static void putFontFamily(int value) {
        Prefers.put("subtitle_style_font_family", clamp(value, FONT_DEFAULT, FONT_MONO));
    }

    /** The secondary subtitle's distance from the top of the screen, as a fraction. */
    public static float getSecondaryPosition() {
        return clamp(Prefers.getFloat("subtitle_style_secondary_position", DEFAULT_SECONDARY_POSITION), 0f, 0.45f);
    }

    public static void putSecondaryPosition(float value) {
        Prefers.put("subtitle_style_secondary_position", clamp(value, 0f, 0.45f));
    }

    /** The background colour with the configured opacity applied. */
    public static int getBackgroundWithOpacity() {
        int opacity = getBackgroundOpacity();
        if (opacity <= 0) return Color.TRANSPARENT;
        return (getBackgroundColor() & 0x00FFFFFF) | (opacity * 255 / 100 << 24);
    }

    /** The font family the player should request, or {@code ""} for the platform default. */
    public static String getFontFamilyName() {
        return fontFamilyName(getFontFamily());
    }

    static String fontFamilyName(int family) {
        return switch (family) {
            case FONT_SANS -> "sans-serif";
            case FONT_SERIF -> "serif";
            case FONT_MONO -> "monospace";
            default -> "";
        };
    }

    public static void reset() {
        Prefers.remove("subtitle_style_font_color");
        Prefers.remove("subtitle_style_outline_color");
        Prefers.remove("subtitle_style_background_color");
        Prefers.remove("subtitle_style_outline_style");
        Prefers.remove("subtitle_style_outline_size");
        Prefers.remove("subtitle_style_background_opacity");
        Prefers.remove("subtitle_style_bold");
        Prefers.remove("subtitle_style_italic");
        Prefers.remove("subtitle_style_font_family");
        Prefers.remove("subtitle_style_secondary_position");
    }

    /**
     * A stored colour is only trusted when it round-trips through the preference
     * store; a value that came back truncated falls back to the default instead
     * of painting an invisible (fully transparent) subtitle.
     */
    private static int color(String key, int fallback) {
        int value = Prefers.getInt(key, fallback);
        if (value == 0) return fallback;
        return value | 0xFF000000;
    }

    static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static float clamp(float value, float minimum, float maximum) {
        if (Float.isNaN(value)) return minimum;
        return Math.max(minimum, Math.min(maximum, value));
    }
}
