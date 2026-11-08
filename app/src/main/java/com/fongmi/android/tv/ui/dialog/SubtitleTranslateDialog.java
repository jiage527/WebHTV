package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.PlayerHelper;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.subtitle.SubtitleFileTranslator;
import com.fongmi.android.tv.player.subtitle.SubtitleSource;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns an external subtitle into a translated one while playback continues.
 *
 * <p>The dialog only collects the two choices (target language, and whether the
 * original line is kept) and hands the work to a background thread. The result is
 * written next to the cache and applied through the same {@link Sub} path as a
 * subtitle chosen by hand, so no new playback code path is introduced and every
 * engine keeps its existing behaviour.
 */
public final class SubtitleTranslateDialog {

    private SubtitleTranslateDialog() {
    }

    /** True when the current item carries an external subtitle this dialog can rewrite. */
    public static boolean hasExternalSubtitle(PlayerManager player) {
        return player != null && !externalSubtitles(player).isEmpty();
    }

    public static void show(FragmentActivity activity, PlayerManager player) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || player == null) return;
        FragmentManager manager = activity.getSupportFragmentManager();
        List<String> sources = externalSubtitles(player);
        if (sources.isEmpty()) {
            Notify.show(R.string.subtitle_translate_no_subtitle);
            return;
        }
        if (sources.size() == 1) {
            chooseLanguage(activity, manager, player, sources.get(0));
            return;
        }
        CharSequence[] labels = new CharSequence[sources.size()];
        for (int i = 0; i < sources.size(); i++) labels[i] = com.fongmi.android.tv.utils.UrlUtil.path(sources.get(i));
        ChoiceDialog.showSingle(manager, activity.getString(R.string.subtitle_translate_pick), labels, 0,
                which -> chooseLanguage(activity, manager, player, sources.get(which)));
    }

    /** The external subtitles of the current item, in the order the player got them. */
    private static List<String> externalSubtitles(PlayerManager player) {
        Set<String> urls = new LinkedHashSet<>();
        MediaItem item = player.getCurrentMediaItem();
        if (item != null && item.localConfiguration != null) {
            List<MediaItem.SubtitleConfiguration> subs = item.localConfiguration.subtitleConfigurations;
            for (MediaItem.SubtitleConfiguration sub : subs) {
                String value = sub.uri == null ? "" : sub.uri.toString();
                if (!value.isEmpty()) urls.add(value);
            }
        }
        return new ArrayList<>(urls);
    }

    private static void chooseLanguage(Context context, FragmentManager manager, PlayerManager player, String source) {
        if (manager == null || manager.isStateSaved()) return;
        List<String> names = SubtitleFileTranslator.languageNames();
        Dialog[] holder = new Dialog[1];
        holder[0] = LightDialog.create(context, context.getString(R.string.subtitle_translate), createPanel(context, manager, player, source, holder, names));
        holder[0].show();
    }

    private static View createPanel(Context context, FragmentManager manager, PlayerManager player, String source, Dialog[] holder, List<String> names) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        MaterialTextView label = new MaterialTextView(context);
        label.setText(R.string.subtitle_translate_language);
        label.setTextSize(16);
        label.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(label);

        LinearLayout languages = new LinearLayout(context);
        languages.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams languageParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        languageParams.topMargin = dp(context, 8);
        root.addView(languages, languageParams);
        for (int i = 0; i < names.size(); i++) {
            final String target = SubtitleFileTranslator.TARGET_CODES[i];
            MaterialButton button = rowButton(context, names.get(i));
            button.setOnClickListener(view -> {
                dismiss(holder);
                chooseMode(context, manager, player, source, target);
            });
            languages.addView(button, rowParams(context, i == 0 ? 0 : 6));
        }
        return root;
    }

    private static void chooseMode(Context context, FragmentManager manager, PlayerManager player, String source, String target) {
        if (manager == null || manager.isStateSaved()) return;
        CharSequence[] modes = {
                context.getString(R.string.subtitle_translate_replace),
                context.getString(R.string.subtitle_translate_bilingual),
        };
        ChoiceDialog.showSingle(manager, context.getString(R.string.subtitle_translate_mode), modes, 0,
                which -> translate(player, source, target, which == 1));
    }

    private static void translate(PlayerManager player, String source, String target, boolean bilingual) {
        Notify.show(R.string.subtitle_translate_working);
        App.post(() -> {
            String text = SubtitleSource.read(source);
            if (text == null || text.isEmpty()) {
                finish(R.string.subtitle_translate_read_failed);
                return;
            }
            String translated = SubtitleFileTranslator.translate(text, target, bilingual);
            if (translated.equals(text)) {
                finish(R.string.subtitle_translate_no_change);
                return;
            }
            String format = SubtitleFileTranslator.detectFormat(text);
            String url = SubtitleSource.write(source + "#" + target + (bilingual ? "-bi" : ""), translated, format);
            if (url == null) {
                finish(R.string.subtitle_translate_write_failed);
                return;
            }
            App.post(() -> {
                Notify.dismiss();
                apply(player, url, bilingual);
            });
        });
    }

    private static void apply(PlayerManager player, String url, boolean bilingual) {
        long position = player.getPosition();
        int nameRes = bilingual ? R.string.subtitle_translate_name_bilingual : R.string.subtitle_translate_name;
        Sub sub = Sub.create(App.get().getString(nameRes), url, "", PlayerHelper.getSubtitleMimeType(url));
        sub.setFlag(C.SELECTION_FLAG_DEFAULT);
        player.setSub(sub);
        // Applying an external subtitle rebuilds the media item; the position is
        // restored explicitly so translating never sends playback back to zero.
        if (position > 0) App.post(() -> player.seekTo(position), 800);
        Notify.show(R.string.subtitle_translate_applied);
    }

    private static void finish(int message) {
        App.post(() -> {
            Notify.dismiss();
            Notify.show(message);
        });
    }

    private static MaterialButton rowButton(Context context, String text) {
        MaterialButton button = new MaterialButton(context);
        button.setAllCaps(false);
        button.setText(text);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setFocusable(true);
        button.setFocusableInTouchMode(Util.isLeanback());
        button.setMinHeight(dp(context, 40));
        button.setMinimumHeight(dp(context, 40));
        button.setCornerRadius(dp(context, 6));
        button.setTextColor(ContextCompat.getColorStateList(context, R.color.dialog_tonal_button_text));
        button.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.dialog_tonal_button_bg));
        return button;
    }

    private static LinearLayout.LayoutParams rowParams(Context context, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, topMargin);
        return params;
    }

    private static int dp(Context context, int value) {
        return ResUtil.dp2px(value);
    }

    private static void dismiss(Dialog[] holder) {
        try {
            if (holder[0] != null) holder[0].dismiss();
        } catch (Throwable ignored) {
        }
    }
}
