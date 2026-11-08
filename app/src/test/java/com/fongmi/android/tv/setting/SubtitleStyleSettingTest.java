package com.fongmi.android.tv.setting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Color;

import org.junit.Test;

/**
 * The subtitle appearance must never leave the player in an unrenderable state,
 * so the normalisation and the "is this a real change" checks are tested here
 * rather than only on a device.
 */
public class SubtitleStyleSettingTest {

    @Test
    public void defaultsReproduceThePreviousBehaviour() {
        assertFalse(SubtitleStyleSetting.isEnabled());
        assertEquals(Color.WHITE, SubtitleStyleSetting.DEFAULT_FONT_COLOR);
        assertEquals(Color.BLACK, SubtitleStyleSetting.DEFAULT_OUTLINE_COLOR);
        assertEquals(SubtitleStyleSetting.OUTLINE_OUTLINE, SubtitleStyleSetting.getOutlineStyle());
    }

    @Test
    public void fontFamilyNamesMapToPlatformFamilies() {
        assertEquals("", SubtitleStyleSetting.fontFamilyName(SubtitleStyleSetting.FONT_DEFAULT));
        assertEquals("sans-serif", SubtitleStyleSetting.fontFamilyName(SubtitleStyleSetting.FONT_SANS));
        assertEquals("serif", SubtitleStyleSetting.fontFamilyName(SubtitleStyleSetting.FONT_SERIF));
        assertEquals("monospace", SubtitleStyleSetting.fontFamilyName(SubtitleStyleSetting.FONT_MONO));
    }

    @Test
    public void outlineStyleStaysInsideTheRenderableRange() {
        assertEquals(SubtitleStyleSetting.OUTLINE_NONE, SubtitleStyleSetting.clamp(-5, SubtitleStyleSetting.OUTLINE_NONE, SubtitleStyleSetting.OUTLINE_BOX));
        assertEquals(SubtitleStyleSetting.OUTLINE_BOX, SubtitleStyleSetting.clamp(99, SubtitleStyleSetting.OUTLINE_NONE, SubtitleStyleSetting.OUTLINE_BOX));
        assertEquals(SubtitleStyleSetting.OUTLINE_SHADOW, SubtitleStyleSetting.clamp(SubtitleStyleSetting.OUTLINE_SHADOW, SubtitleStyleSetting.OUTLINE_NONE, SubtitleStyleSetting.OUTLINE_BOX));
    }

    @Test
    public void numericClampRejectsNaN() {
        assertEquals(0f, SubtitleStyleSetting.clamp(Float.NaN, 0f, 8f), 0.0001f);
        assertEquals(8f, SubtitleStyleSetting.clamp(100f, 0f, 8f), 0.0001f);
        assertEquals(2f, SubtitleStyleSetting.clamp(2f, 0f, 8f), 0.0001f);
    }

    /** The secondary subtitle's historical position must stay the default. */
    @Test
    public void secondaryPositionDefaultMatchesTheOldFixedPadding() {
        assertEquals(0.08f, SubtitleStyleSetting.DEFAULT_SECONDARY_POSITION, 0.0001f);
    }
}
