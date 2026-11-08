package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.ViewingStatsLedger;
import com.fongmi.android.tv.setting.ViewingStatsSetting;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.textview.MaterialTextView;

import java.util.List;

/**
 * Shows how much has been watched, in total and by recent day.
 *
 * <p>The figures come from the same measurement that drives history progress, so what is
 * shown here matches the positions the history list reports. The dialog only reads; the
 * reset action is offered behind a confirmation because it discards an accumulated total
 * that cannot be reconstructed.
 */
public final class ViewingStatsDialog {

    /** How many recent days to list, so the panel stays short on a TV. */
    private static final int LISTED_DAYS = 7;

    private ViewingStatsDialog() {
    }

    public static void show(Context context) {
        if (context == null) return;
        ViewingStatsLedger.Totals totals = ViewingStatsSetting.getTotals();
        Dialog dialog = LightDialog.create(context, context.getString(R.string.viewing_stats_title), panel(context, totals), context.getString(R.string.dialog_positive), null, context.getString(R.string.viewing_stats_reset), view -> confirmReset(context));
        dialog.show();
    }

    private static void confirmReset(Context context) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle(R.string.viewing_stats_reset)
                .setMessage(R.string.viewing_stats_reset_confirm)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> ViewingStatsSetting.reset())
                .show();
    }

    private static View panel(Context context, ViewingStatsLedger.Totals totals) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        root.addView(totalRow(context, context.getString(R.string.viewing_stats_total), ViewingStatsLedger.format(totals.totalMs())));
        root.addView(totalRow(context, context.getString(R.string.viewing_stats_today), ViewingStatsLedger.format(totals.dayMs(ViewingStatsSetting.dayKey(System.currentTimeMillis())))));

        List<String> days = totals.daysNewestFirst();
        if (days.isEmpty()) {
            MaterialTextView empty = value(context, context.getString(R.string.viewing_stats_empty), false);
            empty.setPadding(0, ResUtil.dp2px(12), 0, 0);
            root.addView(empty);
            return root;
        }

        MaterialTextView header = value(context, context.getString(R.string.viewing_stats_recent), false);
        header.setPadding(0, ResUtil.dp2px(16), 0, ResUtil.dp2px(4));
        root.addView(header);

        // Only recent days are listed: the dialog is a summary, and the stored days exist to
        // bound the retained data rather than to be shown in full.
        for (int i = 0; i < Math.min(LISTED_DAYS, days.size()); i++) {
            String day = days.get(i);
            root.addView(totalRow(context, day, ViewingStatsLedger.format(totals.dayMs(day))));
        }
        return root;
    }

    private static View totalRow(Context context, String label, String value) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ResUtil.dp2px(6);

        MaterialTextView name = value(context, label, false);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);

        MaterialTextView amount = value(context, value, true);
        amount.setGravity(Gravity.END);
        row.addView(amount);
        row.setLayoutParams(params);
        return row;
    }

    private static MaterialTextView value(Context context, String text, boolean emphasize) {
        MaterialTextView view = new MaterialTextView(context);
        view.setText(text);
        view.setTextSize(emphasize ? 16 : 14);
        view.setTextColor(ContextCompat.getColor(context, emphasize ? R.color.dialog_outlined_button_text : R.color.dialog_outlined_button_stroke));
        if (emphasize) view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setIncludeFontPadding(false);
        return view;
    }
}
