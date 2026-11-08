package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.ui.SubtitleView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.DialogSubtitleBinding;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.exo.ExoUtil;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.SubtitleStyleSetting;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.bassaer.library.MDColor;

public final class SubtitleDialog extends BaseBottomSheetDialog {

    private static final float DEFAULT_TEXT_SIZE = 0.0533f;
    private static final float TEXT_STEP = 0.002f;
    private static final float POSITION_STEP = 0.005f;

    private DialogSubtitleBinding binding;
    private SubtitleView subtitleView;
    private PlayerManager player;

    public static SubtitleDialog create() {
        return new SubtitleDialog();
    }

    public SubtitleDialog view(SubtitleView subtitleView) {
        this.subtitleView = subtitleView;
        return this;
    }

    public SubtitleDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof SubtitleDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    private boolean isFull() {
        return Util.isFullscreen(getActivity());
    }

    @Override
    protected boolean transparent() {
        return isFull();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogSubtitleBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        int count = binding.getRoot().getChildCount();
        if (isFull()) for (int i = 0; i < count; i++) ((ImageView) binding.getRoot().getChildAt(i)).getDrawable().setTint(MDColor.WHITE);
    }

    @Override
    protected void initEvent() {
        binding.up.setOnClickListener(this::onUp);
        binding.down.setOnClickListener(this::onDown);
        binding.large.setOnClickListener(this::onLarge);
        binding.small.setOnClickListener(this::onSmall);
        binding.style.setOnClickListener(this::onStyle);
        binding.reset.setOnClickListener(this::onReset);
    }

    /**
     * Opens the appearance panel. Its changes are applied to the running player as
     * they happen, so the effect stays visible while the panel is open.
     */
    private void onStyle(View view) {
        SubtitleStyleDialog.show(requireActivity(), this::applyStyle);
    }

    /** Push the stored appearance onto the running engine and the on-screen views. */
    private void applyStyle() {
        if (isNativeSubtitleStyle()) player.setSubtitleStyle(PlayerSetting.getSubtitleTextSize(), PlayerSetting.getSubtitlePosition());
        else ExoUtil.applyCaptionStyle(subtitleView);
        // The secondary (dual) subtitle draws into its own view, so it needs the
        // same refresh; otherwise a colour change would only show after a restart.
        if (player != null && player.getSubtitleSession() != null) player.getSubtitleSession().refreshStyle();
    }

    private void onUp(View view) {
        if (isNativeSubtitleStyle()) {
            setNativePosition(PlayerSetting.getSubtitlePosition() + POSITION_STEP);
            return;
        }
        if (subtitleView == null) return;
        subtitleView.addPosition(POSITION_STEP);
        PlayerSetting.putSubtitlePosition(subtitleView.getPosition());
    }

    private void onDown(View view) {
        if (isNativeSubtitleStyle()) {
            setNativePosition(PlayerSetting.getSubtitlePosition() - POSITION_STEP);
            return;
        }
        if (subtitleView == null) return;
        subtitleView.subPosition(POSITION_STEP);
        PlayerSetting.putSubtitlePosition(subtitleView.getPosition());
    }

    private void onLarge(View view) {
        if (isNativeSubtitleStyle()) {
            setNativeTextSize(currentTextSize() + TEXT_STEP);
            return;
        }
        if (subtitleView == null) return;
        subtitleView.addTextSize(TEXT_STEP);
        PlayerSetting.putSubtitleTextSize(subtitleView.getTextSize());
    }

    private void onSmall(View view) {
        if (isNativeSubtitleStyle()) {
            setNativeTextSize(currentTextSize() - TEXT_STEP);
            return;
        }
        if (subtitleView == null) return;
        subtitleView.subTextSize(TEXT_STEP);
        PlayerSetting.putSubtitleTextSize(subtitleView.getTextSize());
    }

    private void onReset(View view) {
        PlayerSetting.putSubtitleTextSize(0.0f);
        PlayerSetting.putSubtitlePosition(0.0f);
        SubtitleStyleSetting.reset();
        // The view keeps its own text size and position, so it needs the reset that
        // the pixel-based path used before; the native path scales through the
        // engine instead, exactly as it did prior to this panel.
        if (isNativeSubtitleStyle()) player.setSubtitleStyle(0.0f, 0.0f);
        else if (subtitleView != null) subtitleView.reset();
        applyStyle();
    }

    private boolean isNativeSubtitleStyle() {
        return player != null && player.supportsSubtitleStyle();
    }

    private float currentTextSize() {
        float value = PlayerSetting.getSubtitleTextSize();
        return value <= 0 ? DEFAULT_TEXT_SIZE : value;
    }

    private void setNativeTextSize(float value) {
        value = Math.max(0.02f, Math.min(0.12f, value));
        PlayerSetting.putSubtitleTextSize(value);
        player.setSubtitleStyle(value, PlayerSetting.getSubtitlePosition());
    }

    private void setNativePosition(float value) {
        value = Math.max(-0.5f, Math.min(1.0f, value));
        PlayerSetting.putSubtitlePosition(value);
        player.setSubtitleStyle(PlayerSetting.getSubtitleTextSize(), value);
    }

    @Override
    public void onResume() {
        super.onResume();
        getDialog().getWindow().setLayout(ResUtil.dp2px(isFull() ? 232 : 216), -1);
    }
}
