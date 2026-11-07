package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;

/**
 * Composer for sending a danmaku from the player.
 *
 * Scope note: the underlying media3 danmaku controller only renders a locally
 * injected comment (DanmakuView.sendNow appends to its own list and invalidates);
 * there is no upload endpoint in DanmakuApi, which only searches and downloads
 * existing comment files. This dialog therefore posts the comment onto the local
 * overlay immediately, which is what the API surface actually supports.
 */
public final class DanmakuSendDialog extends DialogFragment {

    private static final int MAX_LENGTH = 100;

    private TextInputEditText input;
    private MaterialButton send;
    private PlayerManager player;

    public static DanmakuSendDialog create() {
        return new DanmakuSendDialog();
    }

    public DanmakuSendDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof DanmakuSendDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        input = createInput();
        Dialog dialog = new Dialog(requireContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(createContentView());
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnShowListener(d -> {
            bindEvents();
            if (Util.isLeanback()) send.requestFocus();
            else Util.showKeyboard(input);
        });
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        Window window = dialog == null ? null : dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.copyFrom(window.getAttributes());
        params.width = Math.max(dp(300), Math.min(metrics.widthPixels - dp(32), dp(560)));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        window.setAttributes(params);
    }

    private TextInputEditText createInput() {
        TextInputEditText edit = new TextInputEditText(requireContext());
        edit.setSingleLine(true);
        edit.setMaxLines(1);
        edit.setBackground(null);
        edit.setPadding(0, 0, 0, 0);
        edit.setGravity(Gravity.CENTER_VERTICAL);
        edit.setTextColor(Color.parseColor("#202124"));
        edit.setHintTextColor(Color.parseColor("#6F7782"));
        edit.setTextSize(18);
        edit.setHint(R.string.danmaku_send_hint);
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_LENGTH)});
        edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setImeOptions(EditorInfo.IME_ACTION_SEND);
        return edit;
    }

    private LinearLayout createContentView() {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(16));
        root.setBackground(round(Color.WHITE, 24));
        root.addView(createHeader(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)));
        root.addView(createSendRow(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(createHint(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private MaterialTextView createHint() {
        MaterialTextView hint = new MaterialTextView(requireContext());
        hint.setText(R.string.danmaku_send_local_only);
        hint.setTextColor(Color.parseColor("#6F7782"));
        hint.setTextSize(12);
        hint.setPadding(0, dp(10), 0, 0);
        return hint;
    }

    private LinearLayout createHeader() {
        MaterialTextView title = new MaterialTextView(requireContext());
        title.setText(R.string.danmaku_send);
        title.setTextColor(Color.parseColor("#202124"));
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setSingleLine(true);

        MaterialButton close = new MaterialButton(requireContext());
        close.setText(R.string.danmaku_send_close);
        close.setTextSize(15);
        close.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        close.setGravity(Gravity.CENTER);
        close.setMinWidth(0);
        close.setMinHeight(dp(34));
        close.setMinimumHeight(dp(34));
        close.setPadding(0, 0, 0, 0);
        close.setCornerRadius(dp(17));
        close.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        close.setTextColor(Color.parseColor("#5F6368"));
        close.setOnClickListener(v -> dismiss());

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        row.addView(close, new LinearLayout.LayoutParams(dp(34), dp(34)));
        return row;
    }

    private LinearLayout createSendRow() {
        input.setId(View.generateViewId());
        LinearLayout inputBox = new LinearLayout(requireContext());
        inputBox.setOrientation(LinearLayout.HORIZONTAL);
        inputBox.setGravity(Gravity.CENTER_VERTICAL);
        inputBox.setPadding(dp(14), 0, dp(14), 0);
        inputBox.setBackground(round(Color.parseColor("#F8FAFD"), 12, Color.parseColor("#DADCE0")));
        inputBox.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        send = new MaterialButton(requireContext());
        send.setId(View.generateViewId());
        send.setText(R.string.danmaku_send);
        send.setTextSize(15);
        send.setAllCaps(false);
        send.setMinWidth(0);
        send.setMinHeight(dp(56));
        send.setMinimumHeight(dp(56));
        send.setPadding(dp(10), 0, dp(10), 0);
        send.setCornerRadius(dp(12));
        send.setFocusable(true);
        send.setFocusableInTouchMode(true);
        send.setIconResource(R.drawable.ic_action_send);
        send.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        send.setIconPadding(dp(6));
        send.setIconTint(ColorStateList.valueOf(Color.WHITE));
        send.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#0B57D0")));
        send.setTextColor(Color.WHITE);
        input.setNextFocusRightId(send.getId());
        send.setNextFocusLeftId(input.getId());

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, dp(56), 1);
        inputParams.setMargins(0, 0, dp(8), 0);
        row.addView(inputBox, inputParams);
        row.addView(send, new LinearLayout.LayoutParams(dp(104), dp(56)));
        return row;
    }

    private void bindEvents() {
        input.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEND && actionId != EditorInfo.IME_ACTION_DONE) return false;
            submit();
            return true;
        });
        send.setOnClickListener(v -> submit());
    }

    private void submit() {
        String text = input.getText() == null ? "" : input.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            input.setError(getString(R.string.error_empty));
            return;
        }
        input.setError(null);
        if (player == null) {
            Notify.show(R.string.danmaku_send_unavailable);
            return;
        }
        player.sendDanmaku(text);
        Util.hideKeyboard(input);
        dismiss();
    }

    private GradientDrawable round(int color, int radius) {
        return round(color, radius, Color.TRANSPARENT);
    }

    private GradientDrawable round(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(int value) {
        return ResUtil.dp2px(value);
    }
}
