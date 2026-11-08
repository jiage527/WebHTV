package com.fongmi.android.tv.ui.dialog;

import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.mpv.MpvHlsQualityPolicy;
import com.fongmi.android.tv.player.mpv.MpvHlsVariantPolicy;
import com.fongmi.android.tv.setting.MpvPerformanceSetting;

import java.util.List;

/**
 * Lets the viewer pick a quality from the real variant ladder of the stream playing.
 *
 * <p>The ladder comes from the app's own HLS proxy, which is the only place the
 * manifest's declared variants are known. mpv's {@code hls-bitrate} selects the
 * edition when the demuxer opens the stream, so a choice cannot take effect on a
 * running stream: it is stored and the stream is reloaded through the same path a
 * settings change already uses, which keeps the position for a VOD item.
 *
 * <p>Choosing here retires the automatic bitrate control, because a viewer who picked
 * a quality expects that quality rather than a later automatic downgrade.
 */
public final class MpvQualityDialog {

    private MpvQualityDialog() {
    }

    /** True when a quality menu can be offered for the stream now playing. */
    public static boolean canShow(PlayerManager player) {
        return player != null && player.supportsHlsQualityChoice();
    }

    public static void show(FragmentActivity activity, PlayerManager player) {
        if (activity == null || player == null) return;
        List<MpvHlsVariantPolicy.Variant> variants = player.getHlsPolicyVariants();
        if (!MpvHlsQualityPolicy.canChoose(variants)) return;
        List<MpvHlsQualityPolicy.Choice> choices = MpvHlsQualityPolicy.choices(variants);
        CharSequence[] items = new CharSequence[choices.size()];
        for (int i = 0; i < choices.size(); i++) items[i] = label(activity, choices.get(i));
        String stored = MpvPerformanceSetting.getHlsBitrateOption();
        int selected = MpvHlsQualityPolicy.selectedIndex(choices, stored, player.getHlsSelectedBitsPerSecond());
        ChoiceDialog.showSingle(activity, R.string.play_quality, items, selected, which -> apply(activity, choices, which));
    }

    private static void apply(FragmentActivity activity, List<MpvHlsQualityPolicy.Choice> choices, int which) {
        if (which < 0 || which >= choices.size()) return;
        MpvPerformanceSetting.putHlsBitrateManual(choices.get(which).option());
        Toast.makeText(activity, activity.getString(R.string.play_quality_applied), Toast.LENGTH_SHORT).show();
        // Reuses the established settings-apply path, which reloads MPV while keeping
        // the VOD position and restoring play state, speed and repeat mode.
        ConfigEvent.playerPerformance();
    }

    @NonNull
    private static CharSequence label(FragmentActivity activity, MpvHlsQualityPolicy.Choice choice) {
        if (choice.isHighest()) {
            String detail = choice.label().isEmpty() ? "" : " · " + choice.label();
            return activity.getString(R.string.play_quality_highest) + detail;
        }
        if (choice.isLowest()) return activity.getString(R.string.play_quality_lowest);
        return choice.label().isEmpty() ? choice.option() : choice.label();
    }
}
