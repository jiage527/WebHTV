package com.fongmi.android.tv.ui.dialog;

import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.setting.KeepPlaylist;
import com.fongmi.android.tv.setting.KeepPlaylistStore;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textview.MaterialTextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「我的片单」整理面板：新建 / 重命名 / 删除片单，批量移动收藏，编辑标签，导入导出。
 *
 * <p>两端（手机与电视）共用同一个面板，并共用 {@link #buildFilterBar} 的筛选条。
 * 所有改动写回 {@link KeepPlaylistStore}，面板自身在原地重建以反映最新数据。
 *
 * <p>刻意复用 {@link ChoiceDialog} 做单选与多选，避免为两端各写一套列表控件。
 */
public final class KeepOrganizeDialog {

    private KeepOrganizeDialog() {
    }

    // ------------------------------------------------------------------ 筛选条

    /**
     * 在容器里构建筛选条：全部 / 未分类 / 各片单 / 整理入口。
     * 回调的 {@code playlistId} 为 null 表示「全部」。
     */
    public static void buildFilterBar(AppCompatActivity activity, ViewGroup container, String selected, FilterListener listener) {
        container.removeAllViews();
        KeepPlaylist model = KeepPlaylistStore.get();
        List<String> labels = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        labels.add(activity.getString(R.string.keep_playlist_all));
        ids.add(null);
        labels.add(activity.getString(R.string.keep_playlist_unfiled));
        ids.add(KeepPlaylist.UNFILED);
        for (KeepPlaylist.Playlist item : model.getPlaylists()) {
            labels.add(item.getName());
            ids.add(item.getId());
        }
        for (int i = 0; i < labels.size(); i++) {
            String id = ids.get(i);
            MaterialButton button = chip(activity, labels.get(i));
            button.setAlpha(isActive(id, selected) ? 1f : 0.7f);
            button.setOnClickListener(view -> listener.onFilter(id));
            addChip(container, button);
        }
        MaterialButton organize = chip(activity, activity.getString(R.string.keep_organize));
        organize.setOnClickListener(view -> show(activity, listener::onChanged));
        addChip(container, organize);
    }

    private static boolean isActive(String id, String selected) {
        return id == null ? selected == null : id.equals(selected);
    }

    public interface FilterListener {

        /** @param playlistId null = 全部，空串 = 未分类，其余为片单 id。 */
        void onFilter(String playlistId);

        /** 整理面板改动数据后刷新。 */
        void onChanged();
    }

    // ------------------------------------------------------------------ 面板

    /** 打开整理面板。 */
    public static void show(AppCompatActivity activity, Runnable onChanged) {
        LinearLayout root = column(activity);
        LightDialog.create(activity, activity.getString(R.string.keep_organize), cappedScroll(activity, root)).show();
        fill(activity, root, onChanged);
    }

    /**
     * 原地重建面板内容。任何改动后都走这里，而不是关闭再打开对话框，
     * 这样新建片单、加标签等操作能立刻看到结果。
     */
    private static void fill(AppCompatActivity activity, LinearLayout root, Runnable onChanged) {
        root.removeAllViews();
        Runnable rebuild = () -> {
            fill(activity, root, onChanged);
            onChanged.run();
        };
        List<Keep> items = Keep.getVod();
        KeepPlaylist model = KeepPlaylistStore.get();
        if (items.isEmpty()) {
            root.addView(hint(activity, activity.getString(R.string.keep_manage_none)));
            root.addView(section(activity, R.string.keep_export));
            root.addView(transferRow(activity, rebuild));
            return;
        }
        // 一次性建好 key -> 剧名 映射，避免逐条回查数据库。
        Map<String, String> titles = new LinkedHashMap<>();
        for (Keep item : items) titles.put(item.getKey(), item.getVodName());
        root.addView(section(activity, R.string.keep_playlist));
        root.addView(playlistRow(activity, model, titles, rebuild));
        root.addView(actionRow(activity, titles, rebuild));
        root.addView(section(activity, R.string.keep_tags));
        root.addView(tagRow(activity, model, rebuild));
        root.addView(section(activity, R.string.keep_export));
        root.addView(transferRow(activity, rebuild));
    }

    // ------------------------------------------------------------------ 片单

    private static View playlistRow(AppCompatActivity activity, KeepPlaylist model, Map<String, String> titles, Runnable rebuild) {
        LinearLayout row = row(activity);
        for (KeepPlaylist.Playlist item : model.getPlaylists()) {
            MaterialButton button = chip(activity, item.getName());
            button.setOnClickListener(view -> showPlaylistActions(activity, item, titles, rebuild));
            addChip(row, button);
        }
        MaterialButton create = chip(activity, activity.getString(R.string.keep_playlist_new));
        create.setOnClickListener(view -> askName(activity, R.string.keep_playlist_new, "", value -> {
            if (model.createPlaylist(value, System.currentTimeMillis()) == null) return false;
            KeepPlaylistStore.save();
            Notify.show(R.string.keep_playlist_created);
            rebuild.run();
            return true;
        }));
        addChip(row, create);
        return scrollRow(activity, row);
    }

    private static void showPlaylistActions(AppCompatActivity activity, KeepPlaylist.Playlist item, Map<String, String> titles, Runnable rebuild) {
        CharSequence[] actions = {
                activity.getString(R.string.keep_playlist_rename),
                activity.getString(R.string.keep_manage_move),
                activity.getString(R.string.keep_playlist_delete),
        };
        ChoiceDialog.showSingle(activity, R.string.keep_playlist, actions, -1, which -> {
            if (which == 0) askName(activity, R.string.keep_playlist_rename, item.getName(), value -> {
                if (!KeepPlaylistStore.get().renamePlaylist(item.getId(), value)) return false;
                KeepPlaylistStore.save();
                Notify.show(R.string.keep_playlist_renamed);
                rebuild.run();
                return true;
            });
            else if (which == 1) pickItemsThenMove(activity, item.getId(), titles, rebuild);
            else confirmDeletePlaylist(activity, item, rebuild);
        });
    }

    private static void confirmDeletePlaylist(AppCompatActivity activity, KeepPlaylist.Playlist item, Runnable rebuild) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.keep_playlist_delete)
                .setMessage(R.string.keep_playlist_delete_confirm)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    if (!KeepPlaylistStore.get().deletePlaylist(item.getId())) return;
                    KeepPlaylistStore.save();
                    Notify.show(R.string.keep_playlist_deleted);
                    rebuild.run();
                })
                .show();
    }

    // ------------------------------------------------------------------ 批量

    private static View actionRow(AppCompatActivity activity, Map<String, String> titles, Runnable rebuild) {
        LinearLayout row = row(activity);
        MaterialButton move = chip(activity, activity.getString(R.string.keep_manage_move));
        move.setOnClickListener(view -> pickPlaylistThenMove(activity, titles, rebuild));
        addChip(row, move);
        MaterialButton tag = chip(activity, activity.getString(R.string.keep_manage_tag));
        tag.setOnClickListener(view -> pickItems(activity, R.string.keep_manage_tag, titles, selected -> askTags(activity, selected, rebuild)));
        addChip(row, tag);
        return scrollRow(activity, row);
    }

    private static void pickPlaylistThenMove(AppCompatActivity activity, Map<String, String> titles, Runnable rebuild) {
        KeepPlaylist model = KeepPlaylistStore.get();
        List<CharSequence> labels = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        labels.add(activity.getString(R.string.keep_playlist_unfiled));
        ids.add(KeepPlaylist.UNFILED);
        for (KeepPlaylist.Playlist item : model.getPlaylists()) {
            labels.add(item.getName());
            ids.add(item.getId());
        }
        ChoiceDialog.showSingle(activity, R.string.keep_manage_move, labels.toArray(new CharSequence[0]), -1, which -> {
            if (which < 0 || which >= ids.size()) return;
            pickItemsThenMove(activity, ids.get(which), titles, rebuild);
        });
    }

    /** 多选收藏条目后整体移动到目标片单（含「未分类」）。 */
    private static void pickItemsThenMove(AppCompatActivity activity, String playlistId, Map<String, String> titles, Runnable rebuild) {
        KeepPlaylist model = KeepPlaylistStore.get();
        pickItems(activity, R.string.keep_manage_move, titles, selected -> {
            if (!model.move(selected, playlistId)) return;
            KeepPlaylistStore.save();
            Notify.show(activity.getString(R.string.keep_manage_move_done, selected.size()));
            rebuild.run();
        }, key -> playlistId.equals(model.playlistOf(key)));
    }

    private static void pickItems(AppCompatActivity activity, int titleRes, Map<String, String> titles, SelectedHandler handler) {
        pickItems(activity, titleRes, titles, handler, key -> false);
    }

    /** 多选条目；已选中的初始状态由 {@code preselect} 决定。空选不回调。 */
    private static void pickItems(AppCompatActivity activity, int titleRes, Map<String, String> titles, SelectedHandler handler, Preselect preselect) {
        List<String> keys = new ArrayList<>(titles.keySet());
        CharSequence[] labels = new CharSequence[keys.size()];
        boolean[] checked = new boolean[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            labels[i] = titles.get(keys.get(i));
            checked[i] = preselect.isSelected(keys.get(i));
        }
        ChoiceDialog.showMulti(activity, titleRes, labels, checked, selection -> {
            List<String> selected = new ArrayList<>();
            for (int i = 0; i < selection.length; i++) if (selection[i]) selected.add(keys.get(i));
            if (selected.isEmpty()) {
                Notify.show(R.string.keep_manage_none);
                return;
            }
            handler.onSelected(selected);
        });
    }

    private interface Preselect {
        boolean isSelected(String key);
    }

    private interface SelectedHandler {
        void onSelected(List<String> keys);
    }

    // ------------------------------------------------------------------ 标签

    private static View tagRow(AppCompatActivity activity, KeepPlaylist model, Runnable rebuild) {
        LinearLayout row = row(activity);
        List<String> tags = model.allTags();
        if (tags.isEmpty()) {
            row.addView(hint(activity, activity.getString(R.string.keep_tag_none)));
        } else {
            for (String tag : tags) {
                MaterialButton button = chip(activity, tag);
                button.setOnClickListener(view -> confirmRemoveTag(activity, tag, rebuild));
                addChip(row, button);
            }
        }
        return scrollRow(activity, row);
    }

    private static void confirmRemoveTag(AppCompatActivity activity, String tag, Runnable rebuild) {
        int count = KeepPlaylistStore.get().keysWithTag(tag).size();
        new MaterialAlertDialogBuilder(activity)
                .setTitle(tag)
                .setMessage(activity.getString(R.string.keep_tag_remove_confirm, count))
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    KeepPlaylist model = KeepPlaylistStore.get();
                    for (String key : model.keysWithTag(tag)) {
                        List<String> tags = new ArrayList<>(model.tagsOf(key));
                        tags.removeIf(item -> item.equalsIgnoreCase(tag));
                        model.setTags(key, tags);
                    }
                    KeepPlaylistStore.save();
                    Notify.show(R.string.keep_tag_saved);
                    rebuild.run();
                })
                .show();
    }

    // ------------------------------------------------------------------ 导入导出

    private static View transferRow(AppCompatActivity activity, Runnable rebuild) {
        LinearLayout row = row(activity);
        MaterialButton export = chip(activity, activity.getString(R.string.keep_export));
        export.setOnClickListener(view -> {
            if (KeepPlaylistStore.get().isEmpty()) {
                Notify.show(R.string.keep_export_empty);
                return;
            }
            Util.copy(KeepPlaylistStore.export());
            Notify.show(R.string.keep_export_done);
        });
        addChip(row, export);
        MaterialButton merge = chip(activity, activity.getString(R.string.keep_import_merge));
        merge.setOnClickListener(view -> importFromClipboard(activity, true, rebuild));
        addChip(row, merge);
        MaterialButton replace = chip(activity, activity.getString(R.string.keep_import));
        replace.setOnClickListener(view -> importFromClipboard(activity, false, rebuild));
        addChip(row, replace);
        return scrollRow(activity, row);
    }

    private static void importFromClipboard(AppCompatActivity activity, boolean merge, Runnable rebuild) {
        String error = KeepPlaylistStore.importFrom(String.valueOf(Util.getClipText()), merge);
        if (error != null) {
            Notify.show(error);
            return;
        }
        Notify.show(R.string.keep_import_done);
        rebuild.run();
    }

    // ------------------------------------------------------------------ 输入

    private interface NameHandler {
        /** 返回 false 表示拒绝（重名或空名），对话框保持打开。 */
        boolean onValue(String value);
    }

    private static void askName(AppCompatActivity activity, int titleRes, String initial, NameHandler handler) {
        EditText input = input(activity, activity.getString(R.string.keep_playlist_name_hint));
        input.setText(initial);
        input.setSelection(input.length());
        new MaterialAlertDialogBuilder(activity)
                .setTitle(titleRes)
                .setView(wrap(activity, input))
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    String value = KeepPlaylist.normalizeName(input.getText().toString());
                    if (value.isEmpty()) Notify.show(R.string.keep_playlist_name_required);
                    else if (!handler.onValue(value)) Notify.show(R.string.keep_playlist_exists);
                })
                .show();
    }

    private static void askTags(AppCompatActivity activity, List<String> keys, Runnable rebuild) {
        KeepPlaylist model = KeepPlaylistStore.get();
        EditText input = input(activity, activity.getString(R.string.keep_tag_hint));
        input.setText(String.join(", ", model.tagsOf(keys.get(0))));
        input.setSelection(input.length());
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.keep_tags)
                .setView(wrap(activity, input))
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    List<String> tags = KeepPlaylist.sanitizeTags(Arrays.asList(input.getText().toString().split("[,，]")));
                    for (String key : keys) model.setTags(key, tags);
                    KeepPlaylistStore.save();
                    Notify.show(R.string.keep_tag_saved);
                    rebuild.run();
                })
                .show();
    }

    // ------------------------------------------------------------------ 控件

    private static EditText input(Context context, String hint) {
        EditText input = new EditText(context);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextColor(ContextCompat.getColor(context, R.color.dialog_outlined_button_text));
        return input;
    }

    private static View wrap(Context context, EditText input) {
        LinearLayout container = column(context);
        int padding = ResUtil.dp2px(20);
        container.setPadding(padding, ResUtil.dp2px(8), padding, 0);
        container.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return container;
    }

    private static LinearLayout column(Context context) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        return content;
    }

    private static LinearLayout row(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private static MaterialTextView section(Context context, int textRes) {
        MaterialTextView label = new MaterialTextView(context);
        label.setText(textRes);
        label.setTextSize(13);
        label.setAlpha(0.75f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ResUtil.dp2px(14);
        label.setLayoutParams(params);
        return label;
    }

    private static MaterialTextView hint(Context context, String text) {
        MaterialTextView label = new MaterialTextView(context);
        label.setText(text);
        label.setTextSize(13);
        label.setAlpha(0.6f);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPadding(0, ResUtil.dp2px(10), 0, ResUtil.dp2px(10));
        return label;
    }

    private static MaterialButton chip(Context context, String text) {
        MaterialButton button = new MaterialButton(context);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(13);
        button.setSingleLine(true);
        button.setIncludeFontPadding(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setCornerRadius(ResUtil.dp2px(16));
        button.setPadding(ResUtil.dp2px(14), ResUtil.dp2px(8), ResUtil.dp2px(14), ResUtil.dp2px(8));
        button.setTextColor(ContextCompat.getColorStateList(context, R.color.dialog_outlined_button_text));
        button.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.dialog_outlined_button_bg));
        button.setStrokeColor(ContextCompat.getColorStateList(context, R.color.dialog_outlined_button_stroke));
        button.setStrokeWidth(ResUtil.dp2px(1));
        button.setFocusable(true);
        button.setFocusableInTouchMode(Util.isLeanback());
        return button;
    }

    private static void addChip(ViewGroup row, MaterialButton button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = ResUtil.dp2px(8);
        row.addView(button, params);
    }

    private static View scrollRow(Context context, LinearLayout row) {
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    /**
     * 内容用滚动容器包一层并限高，保证手机横屏时按钮仍可点。
     *
     * <p>高度必须随内容变化重算：面板会在原地重建（新建片单、加标签等），
     * 一次性的 post 会让高度停留在旧值。
     */
    private static View cappedScroll(Context context, LinearLayout content) {
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top == oldBottom - oldTop) return;
            int max = Math.min(Math.round(ResUtil.getScreenHeight(context) * 0.55f), ResUtil.dp2px(440));
            int measured = bottom - top;
            if (measured <= 0) return;
            ViewGroup.LayoutParams params = scroll.getLayoutParams();
            if (params == null) return;
            int target = Math.min(measured, max);
            if (params.height == target) return;
            params.height = target;
            scroll.setLayoutParams(params);
        });
        return scroll;
    }
}
