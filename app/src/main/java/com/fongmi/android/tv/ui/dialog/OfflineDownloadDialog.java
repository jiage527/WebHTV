package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.exoplayer.offline.Download;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.download.OfflineDownloadKind;
import com.fongmi.android.tv.download.OfflineDownloadManager;
import com.fongmi.android.tv.download.OfflineDownloadRecord;
import com.fongmi.android.tv.download.OfflineDownloadStatus;
import com.fongmi.android.tv.download.OfflineDownloadUrl;
import com.fongmi.android.tv.service.OfflineDownloadService;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.ThemeColors;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 离线下载面板：看队列/进度、暂停继续、删除、以及直接播下好的内容。
 *
 * <p>沿用 {@link NetDiskDialog} 那套「代码搭界面、两端共用」的写法（项目里
 * {@code PlayQueueDialog}、{@code KeepOrganizeDialog} 都是这个路子），因为手机与电视的
 * XML 各自维护，同一份面板没必要写两遍。
 *
 * <p><b>高度必须显式设置</b>：{@link BottomSheetDialog} 按内容测量高度，而这个面板的内容
 * 是一列标准控件，量出来远小于容器，sheet 会缩成屏幕底部一条、动作按钮被裁到屏外，
 * 用户看到的就是「点了没反应」。正确写法见 {@code OneKeySyncDialog#setBehavior} 与
 * {@code DanmakuSettingDialog#setBehavior}，这里照做。
 *
 * <p>所有耗时动作（读索引、删文件）都放后台线程，只把结果 {@code App.post} 回主线程。
 */
public final class OfflineDownloadDialog {

    private final Context context;
    private final BottomSheetDialog dialog;
    private final TextView titleView;
    private final TextView emptyView;
    private final RecyclerView listView;
    private final LinearLayout actionRow;
    private final ItemAdapter adapter = new ItemAdapter();

    private OfflineDownloadDialog(Context context) {
        this.context = context;
        this.dialog = new BottomSheetDialog(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(8));
        titleView = new TextView(context);
        titleView.setTextSize(16f);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setTextColor(ThemeColors.onSurface(context));
        titleView.setText(R.string.offline_download_title);
        root.addView(titleView, matchWrap());
        emptyView = new TextView(context);
        emptyView.setTextSize(13f);
        emptyView.setTextColor(ThemeColors.secondary(context));
        emptyView.setText(R.string.offline_download_none);
        emptyView.setPadding(0, ResUtil.dp2px(24), 0, ResUtil.dp2px(24));
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView, matchWrap());
        listView = new RecyclerView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ResUtil.dp2px(320)));
        actionRow = new LinearLayout(context);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.END);
        root.addView(actionRow, matchWrap());
        dialog.setContentView(root);
        expandSheet();
    }

    public static void show(@Nullable FragmentActivity activity) {
        if (activity == null) return;
        OfflineDownloadDialog panel = new OfflineDownloadDialog(activity);
        panel.dialog.show();
        panel.reload();
    }

    /**
     * 播放页「下载」按钮的入口：把当前正在播的这一集加进下载队列。
     *
     * <p>只有「地址已经解析出来」时才谈得上下载——历史与收藏里存的是原始剧集地址，
     * 可能还需要二次解析（{@code Result.shouldUseParse()}），那种地址直接丢给下载器
     * 只会下到一段 HTML。所以本入口只认播放器手上的地址，解析不出就直接告诉用户。
     *
     * <p>已经在队列里（含已下完）时不重复入队，改成打开面板，让用户看到它就在那里。
     */
    public static void enqueueCurrent(@Nullable FragmentActivity activity, @Nullable String title, @Nullable String url, @Nullable Map<String, String> headers) {
        if (activity == null) return;
        if (TextUtils.isEmpty(url)) {
            Notify.show(R.string.offline_download_unavailable);
            return;
        }
        String clean = url.trim();
        if (!OfflineDownloadUrl.isDownloadable(clean)) {
            Notify.show(R.string.offline_download_unavailable);
            return;
        }
        OfflineDownloadManager manager = OfflineDownloadManager.get();
        if (manager.hasActive(clean)) {
            Notify.show(R.string.offline_download_exists);
            show(activity);
            return;
        }
        OfflineDownloadRecord record = OfflineDownloadRecord.create(title, clean, headers, System.currentTimeMillis());
        if (manager.enqueue(record)) Notify.show(R.string.offline_download_added);
        else Notify.show(R.string.offline_download_exists);
    }

    /**
     * 给设置页/其他入口用的数量摘要。
     */
    public static int count() {
        try {
            return OfflineDownloadManager.get().all().size();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 面板必须在打开时把高度定死，否则会被裁成一条（见类注释）。
     */
    private void expandSheet() {
        dialog.setOnShowListener(ignored -> {
            FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet == null) return;
            int height = (int) (ResUtil.getScreenHeight(context) * 0.72f);
            ViewGroup.LayoutParams params = sheet.getLayoutParams();
            params.height = height;
            sheet.setLayoutParams(params);
            BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
            behavior.setPeekHeight(height);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            behavior.setSkipCollapsed(true);
        });
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /**
     * 读下载索引 + 记录表。索引里有进度与状态，记录表里有标题（Media3 不存标题）。
     */
    private void reload() {
        OfflineDownloadManager manager = OfflineDownloadManager.get();
        List<Download> all = manager.all();
        List<Row> rows = new ArrayList<>();
        for (Download download : all) {
            if (download.request == null) continue;
            OfflineDownloadRecord record = manager.registry().get(download.request.id);
            String title = manager.registry().titleOf(download.request.id, download.request.uri.toString());
            OfflineDownloadKind kind = record == null ? OfflineDownloadKind.ofUrl(download.request.uri.toString()) : record.kind();
            rows.add(new Row(download, title, kind));
        }
        adapter.submit(rows);
        emptyView.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        titleView.setText(context.getString(R.string.offline_download_title) + " · " + context.getString(R.string.offline_download_used, OfflineDownloadStatus.sizeText(manager.usedBytes())));
        reloadActions(rows);
    }

    private void reloadActions(List<Row> rows) {
        actionRow.removeAllViews();
        if (rows.isEmpty()) return;
        boolean running = false;
        for (Row row : rows) if (OfflineDownloadStatus.showsProgress(row.phase())) running = true;
        boolean pause = running;
        action(pause ? R.string.offline_download_pause : R.string.offline_download_resume, view -> {
            if (pause) OfflineDownloadService.pauseAll(context);
            else OfflineDownloadService.resumeAll(context);
            reload();
        });
        action(R.string.offline_download_delete_all, view -> confirmDeleteAll());
    }

    private void action(int textRes, View.OnClickListener listener) {
        TextView view = new TextView(context);
        view.setText(textRes);
        view.setTextSize(14f);
        view.setTextColor(ThemeColors.action(context));
        view.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(8), ResUtil.dp2px(12), ResUtil.dp2px(8));
        view.setOnClickListener(listener);
        actionRow.addView(view);
    }

    private void confirmDeleteAll() {
        new MaterialAlertDialogBuilder(context)
                .setMessage(R.string.offline_download_delete_all_confirm)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (d, which) -> {
                    for (Download download : OfflineDownloadManager.get().all()) {
                        if (download.request != null) OfflineDownloadManager.get().remove(download.request.id);
                    }
                    reload();
                })
                .show();
    }

    private void confirmDelete(Row row) {
        new MaterialAlertDialogBuilder(context)
                .setMessage(R.string.offline_download_delete_confirm)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (d, which) -> {
                    OfflineDownloadManager.get().remove(row.id());
                    reload();
                })
                .show();
    }

    private void play(Row row) {
        if (row.cacheKey().isEmpty()) return;
        dialog.dismiss();
        // 播放时 MediaItem 会带上这个 cacheKey，ExoUtil/MediaSourceFactory 据此把数据源切到下载缓存。
        VideoActivity.start(activity(), row.url());
        Notify.show(R.string.offline_download_play);
    }

    /**
     * 播放页入口需要 Activity。本面板可能由 Activity（设置页/播放页）或 Fragment 拉起，
     * 两种都拿到 Activity 再交给 VideoActivity.start。
     */
    private Activity activity() {
        if (context instanceof Activity) return (Activity) context;
        throw new IllegalStateException("offline download panel needs an activity context");
    }

    private final class Row {

        private final Download download;
        private final String title;
        private final OfflineDownloadKind kind;

        private Row(Download download, String title, OfflineDownloadKind kind) {
            this.download = download;
            this.title = title;
            this.kind = kind;
        }

        private String id() {
            return download.request.id;
        }

        private String url() {
            return download.request.uri.toString();
        }

        private String cacheKey() {
            return download.request.customCacheKey;
        }

        private OfflineDownloadStatus.Phase phase() {
            return OfflineDownloadStatus.phase(download.state, download.stopReason, download.failureReason);
        }

        private String stateText() {
            int res;
            switch (phase()) {
                case DOWNLOADING:
                    res = R.string.offline_download_state_downloading;
                    break;
                case QUEUED:
                    res = R.string.offline_download_state_queued;
                    break;
                case PAUSED:
                    res = R.string.offline_download_state_paused;
                    break;
                case COMPLETED:
                    res = R.string.offline_download_state_completed;
                    break;
                case FAILED:
                    res = R.string.offline_download_state_failed;
                    break;
                case REMOVING:
                    res = R.string.offline_download_state_removing;
                    break;
                case RESTARTING:
                    res = R.string.offline_download_state_restarting;
                    break;
                default:
                    res = R.string.offline_download_state_unknown;
                    break;
            }
            return context.getString(res);
        }

        /**
         * 右下角那一行：下载中显示「百分比 · 已下/总长」，下完显示大小，失败显示状态。
         */
        private String detail() {
            OfflineDownloadStatus.Phase phase = phase();
            if (phase == OfflineDownloadStatus.Phase.DOWNLOADING) {
                int percent = OfflineDownloadStatus.percent(download.getBytesDownloaded(), download.contentLength);
                if (percent < 0) return OfflineDownloadStatus.sizeText(download.getBytesDownloaded());
                return context.getString(R.string.offline_download_progress, percent) + " · " + OfflineDownloadStatus.sizeText(download.contentLength);
            }
            if (phase == OfflineDownloadStatus.Phase.COMPLETED) return OfflineDownloadStatus.sizeText(download.contentLength);
            return stateText();
        }
    }

    private final class ItemAdapter extends RecyclerView.Adapter<ItemHolder> {

        private final List<Row> rows = new ArrayList<>();

        private void submit(List<Row> items) {
            rows.clear();
            rows.addAll(items);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ItemHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(12), ResUtil.dp2px(12), ResUtil.dp2px(12));
            row.setBackgroundColor(ThemeColors.rowBackground(context));
            TextView label = new TextView(context);
            label.setTextSize(14f);
            label.setTextColor(ThemeColors.onSurface(context));
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            TextView detail = new TextView(context);
            detail.setTextSize(11f);
            detail.setTextColor(ThemeColors.detail(context));
            detail.setSingleLine(true);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(detail, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new ItemHolder(row, label, detail);
        }

        @Override
        public void onBindViewHolder(@NonNull ItemHolder holder, int position) {
            Row row = rows.get(position);
            holder.label.setText(row.title);
            holder.detail.setText(row.detail());
            // 点一下：下完的直接播；没下完的先暂停/继续，避免误触把队列弄乱。
            holder.itemView.setOnClickListener(view -> {
                if (OfflineDownloadStatus.playable(row.phase())) play(row);
                else Notify.show(row.stateText());
            });
            holder.itemView.setOnLongClickListener(view -> {
                confirmDelete(row);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private static final class ItemHolder extends RecyclerView.ViewHolder {

        private final TextView label;
        private final TextView detail;

        private ItemHolder(@NonNull View itemView, TextView label, TextView detail) {
            super(itemView);
            this.label = label;
            this.detail = detail;
        }
    }
}
