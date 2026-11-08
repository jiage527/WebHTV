package com.fongmi.android.tv.ui.dialog;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.SubtitleStyleSetting;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;

import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * Lets the user shape the subtitle appearance without leaving the player.
 *
 * <p>Every change is applied immediately and the player re-renders behind the
 * panel, because a subtitle style is far easier to judge by looking at the
 * picture than by reading numbers. Touching any control also switches the style
 * on, so a control can never appear to do nothing.
 *
 * <p>The rows are built in code and reuse the dialog button styles already used
 * elsewhere, so the panel follows the app theme in light and dark mode and on
 * both the mobile and TV layouts without adding a layout file.
 */
public final class SubtitleStyleDialog {

    private static final int[] FONT_COLORS = {
            Color.WHITE, 0xFFFFE082, 0xFF80DEEA, 0xFFA5D6A7,
            0xFFF48FB1, 0xFFFFAB91, 0xFFCE93D8, 0xFFFFF176,
    };
    private static final int[] OUTLINE_COLORS = {
            Color.BLACK, 0xFF37474F, 0xFF4E342E, 0xFF7F0000,
    };
    private static final int[] BACKGROUND_COLORS = {
            0xFF000000, 0xFF263238, 0xFF1A237E, 0xFF3E2723,
    };

    private SubtitleStyleDialog() {
    }

    /** @param onChanged called after every change so the running player can re-apply the style. */
    public static void show(FragmentActivity activity, Runnable onChanged) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        FragmentManager manager = activity.getSupportFragmentManager();
        if (manager.isStateSaved()) return;
        LightDialog.create(activity, activity.getString(R.string.subtitle_style), panel(activity, onChanged)).show();
    }

    private static View panel(Context context, Runnable onChanged) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        MaterialButton enabled = button(context, switchLabel(context, SubtitleStyleSetting.isEnabled()));
        refreshSwitch(enabled);
        // Touching any control turns the style on first, so the effect is always
        // visible instead of silently stored for a later session.
        Runnable ensureEnabled = () -> {
            if (SubtitleStyleSetting.isEnabled()) return;
            SubtitleStyleSetting.putEnabled(true);
            refreshSwitch(enabled);
        };

        enabled.setOnClickListener(view -> {
            SubtitleStyleSetting.putEnabled(!SubtitleStyleSetting.isEnabled());
            refreshSwitch(enabled);
            notifyChanged(onChanged);
        });
        addRow(content, enabled, 0);

        addLabel(content, context, R.string.subtitle_style_secondary_position);
        addRow(content, stepper(context, SubtitleStyleSetting.getSecondaryPosition(), 0f, 0.45f, 0.02f,
                SubtitleStyleSetting::putSecondaryPosition, ensureEnabled, onChanged), 0);

        addLabel(content, context, R.string.subtitle_style_font_family);
        addRow(content, choices(context, fontFamilyLabels(context), SubtitleStyleSetting.getFontFamily(),
                SubtitleStyleSetting::putFontFamily, ensureEnabled, onChanged), 0);

        addLabel(content, context, R.string.subtitle_style_font_style);
        addRow(content, toggles(context, ensureEnabled, onChanged), 0);

        addLabel(content, context, R.string.subtitle_style_font_color);
        addRow(content, swatches(context, FONT_COLORS, SubtitleStyleSetting::getFontColor,
                SubtitleStyleSetting::putFontColor, ensureEnabled, onChanged), 0);

        addLabel(content, context, R.string.subtitle_style_outline);
        addRow(content, choices(context, outlineStyleLabels(context), SubtitleStyleSetting.getOutlineStyle(),
                SubtitleStyleSetting::putOutlineStyle, ensureEnabled, onChanged), 0);
        addRow(content, swatches(context, OUTLINE_COLORS, SubtitleStyleSetting::getOutlineColor,
                SubtitleStyleSetting::putOutlineColor, ensureEnabled, onChanged), 8);
        addRow(content, stepper(context, SubtitleStyleSetting.getOutlineSize(), 0f, 8f, 0.5f,
                SubtitleStyleSetting::putOutlineSize, ensureEnabled, onChanged), 8);

        addLabel(content, context, R.string.subtitle_style_background);
        addRow(content, swatches(context, BACKGROUND_COLORS, SubtitleStyleSetting::getBackgroundColor,
                SubtitleStyleSetting::putBackgroundColor, ensureEnabled, onChanged), 0);
        addRow(content, stepper(context, SubtitleStyleSetting.getBackgroundOpacity(), 0f, 100f, 5f,
                value -> SubtitleStyleSetting.putBackgroundOpacity(Math.round(value)), ensureEnabled, onChanged), 8);

        return cappedScroll(context, content);
    }

    /**
     * Wraps the rows in a scroller whose height is the content height, capped so the
     * panel still fits a phone in landscape. The cap can only be applied after the
     * dialog window has been sized, hence the one-shot post.
     */
    private static View cappedScroll(Context context, LinearLayout content) {
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.post(() -> {
            int max = Math.min(Math.round(ResUtil.getScreenHeight(context) * 0.55f), ResUtil.dp2px(420));
            int measured = content.getMeasuredHeight();
            if (measured <= 0) return;
            ViewGroup.LayoutParams params = scroll.getLayoutParams();
            if (params == null) return;
            params.height = Math.min(measured, max);
            scroll.setLayoutParams(params);
        });
        return scroll;
    }

    private static String switchLabel(Context context, boolean enabled) {
        return context.getString(R.string.subtitle_style) + " : "
                + context.getString(enabled ? R.string.subtitle_style_on : R.string.subtitle_style_off);
    }

    private static void refreshSwitch(MaterialButton button) {
        boolean enabled = SubtitleStyleSetting.isEnabled();
        button.setText(switchLabel(button.getContext(), enabled));
        button.setAlpha(enabled ? 1f : 0.7f);
    }

    private static void addLabel(LinearLayout content, Context context, int textRes) {
        MaterialTextView label = new MaterialTextView(context);
        label.setText(textRes);
        label.setTextSize(13);
        label.setAlpha(0.75f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ResUtil.dp2px(14);
        content.addView(label, params);
    }

    private static void addRow(LinearLayout content, View row, int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ResUtil.dp2px(topMarginDp);
        content.addView(row, params);
    }

    /** A row of mutually exclusive choices; the selected one is highlighted. */
    private static View choices(Context context, String[] labels, int selected, IntConsumer apply, Runnable ensureEnabled, Runnable onChanged) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            MaterialButton button = button(context, labels[i]);
            button.setTag(index);
            highlight(button, i == selected);
            button.setOnClickListener(view -> {
                ensureEnabled.run();
                apply.accept(index);
                for (int child = 0; child < row.getChildCount(); child++) {
                    View item = row.getChildAt(child);
                    if (item instanceof MaterialButton material) highlight(material, Integer.valueOf(index).equals(material.getTag()));
                }
                notifyChanged(onChanged);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) params.leftMargin = ResUtil.dp2px(6);
            row.addView(button, params);
        }
        return row;
    }

    private static View toggles(Context context, Runnable ensureEnabled, Runnable onChanged) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton bold = button(context, context.getString(R.string.subtitle_style_bold));
        MaterialButton italic = button(context, context.getString(R.string.subtitle_style_italic));
        highlight(bold, SubtitleStyleSetting.isBold());
        highlight(italic, SubtitleStyleSetting.isItalic());
        bold.setOnClickListener(view -> {
            ensureEnabled.run();
            SubtitleStyleSetting.putBold(!SubtitleStyleSetting.isBold());
            highlight(bold, SubtitleStyleSetting.isBold());
            notifyChanged(onChanged);
        });
        italic.setOnClickListener(view -> {
            ensureEnabled.run();
            SubtitleStyleSetting.putItalic(!SubtitleStyleSetting.isItalic());
            highlight(italic, SubtitleStyleSetting.isItalic());
            notifyChanged(onChanged);
        });
        row.addView(bold, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        second.leftMargin = ResUtil.dp2px(6);
        row.addView(italic, second);
        return row;
    }

    /**
     * A row of colour swatches. The swatches overflow a narrow phone screen, so they
     * live in a horizontal scroller instead of being squeezed unreadably thin.
     */
    private static View swatches(Context context, int[] colors, IntSupplier get, IntConsumer apply, Runnable ensureEnabled, Runnable onChanged) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int color : colors) {
            MaterialButton swatch = button(context, "");
            swatch.setTag(color);
            swatch.setBackgroundTintList(ColorStateList.valueOf(color));
            highlightSwatch(swatch, get.get() == color);
            swatch.setOnClickListener(view -> {
                ensureEnabled.run();
                apply.accept(color);
                for (int child = 0; child < row.getChildCount(); child++) {
                    View item = row.getChildAt(child);
                    if (item instanceof MaterialButton material) highlightSwatch(material, Integer.valueOf(color).equals(material.getTag()));
                }
                notifyChanged(onChanged);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ResUtil.dp2px(44), ResUtil.dp2px(36));
            params.leftMargin = ResUtil.dp2px(6);
            row.addView(swatch, params);
        }
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    /** A minus / value / plus row for a bounded numeric value. */
    private static View stepper(Context context, float value, float minimum, float maximum, float step, FloatConsumer apply, Runnable ensureEnabled, Runnable onChanged) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton minus = button(context, "-");
        MaterialButton plus = button(context, "+");
        MaterialTextView text = new MaterialTextView(context);
        text.setGravity(Gravity.CENTER);
        text.setText(format(value));
        text.setTextSize(15);
        final float[] current = {value};
        minus.setOnClickListener(view -> {
            ensureEnabled.run();
            current[0] = Math.max(minimum, current[0] - step);
            apply.accept(current[0]);
            text.setText(format(current[0]));
            notifyChanged(onChanged);
        });
        plus.setOnClickListener(view -> {
            ensureEnabled.run();
            current[0] = Math.min(maximum, current[0] + step);
            apply.accept(current[0]);
            text.setText(format(current[0]));
            notifyChanged(onChanged);
        });
        row.addView(minus, new LinearLayout.LayoutParams(ResUtil.dp2px(48), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(plus, new LinearLayout.LayoutParams(ResUtil.dp2px(48), ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private static String format(float value) {
        return Math.abs(value - Math.round(value)) < 0.001f
                ? String.valueOf(Math.round(value)) : String.format(Locale.US, "%.2f", value);
    }

    private static String[] fontFamilyLabels(Context context) {
        return new String[]{
                context.getString(R.string.subtitle_style_font_default),
                context.getString(R.string.subtitle_style_font_sans),
                context.getString(R.string.subtitle_style_font_serif),
                context.getString(R.string.subtitle_style_font_mono),
        };
    }

    private static String[] outlineStyleLabels(Context context) {
        return new String[]{
                context.getString(R.string.subtitle_style_outline_none),
                context.getString(R.string.subtitle_style_outline_line),
                context.getString(R.string.subtitle_style_outline_shadow),
                context.getString(R.string.subtitle_style_outline_box),
        };
    }

    private static void highlight(MaterialButton button, boolean selected) {
        button.setAlpha(selected ? 1f : 0.6f);
        button.setStrokeWidth(ResUtil.dp2px(selected ? 2 : 0));
    }

    private static void highlightSwatch(MaterialButton swatch, boolean selected) {
        swatch.setStrokeWidth(ResUtil.dp2px(selected ? 3 : 0));
    }

    private static MaterialButton button(Context context, String text) {
        MaterialButton button = new MaterialButton(context);
        button.setAllCaps(false);
        button.setText(text);
        button.setSingleLine(true);
        button.setFocusable(true);
        button.setFocusableInTouchMode(Util.isLeanback());
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(ResUtil.dp2px(40));
        button.setMinimumHeight(ResUtil.dp2px(40));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setCornerRadius(ResUtil.dp2px(6));
        button.setTextColor(ContextCompat.getColorStateList(context, R.color.dialog_outlined_button_text));
        button.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.dialog_outlined_button_bg));
        button.setStrokeColor(ContextCompat.getColorStateList(context, R.color.dialog_outlined_button_stroke));
        button.setStrokeWidth(ResUtil.dp2px(1));
        return button;
    }

    private static void notifyChanged(Runnable onChanged) {
        if (onChanged != null) onChanged.run();
    }

    private interface IntSupplier {
        int get();
    }

    private interface FloatConsumer {
        void accept(float value);
    }
}
