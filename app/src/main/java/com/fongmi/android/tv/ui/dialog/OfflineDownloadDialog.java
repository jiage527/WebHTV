package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.exoplayer.offline.Download;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.download.OfflineDownloadCache;
import com.fongmi.android.tv.download.OfflineDownloadKind;
import com.fongmi.android.tv.download.OfflineDownloadManager;
import com.fongmi.android.tv.download.OfflineDownloadPath;
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
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 离线下载面板：分「下载中 / 已完成」两页看队列，每条带进度条、百分比、文件路径，
 * 以及单独可用的暂停/继续与删除按钮；下好的可以直接离线播。
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
 * <p><b>为什么每秒刷一次。</b>Media3 的 {@code DownloadManager} 只有
 * {@code addListener(Listener)} 这一种通知方式，而它给出的是「哪一条变了」，进度百分比仍要
 * 自己回头读。这里选了最简单的做法：面板活着的时候每秒读一遍索引，面板关掉立刻停。
 * 只读内存里的索引，不碰磁盘，开销可以忽略。
 *
 * <p>所有耗时动作（读索引、删文件）都放后台线程，只把结果 {@code App.post} 回主线程。
 */
public final class OfflineDownloadDialog {

    /** 进度刷新间隔；与通知的 {@code UPDATE_INTERVAL_MS} 保持一致，肉眼看着是同步的。 */
    private static final long REFRESH_INTERVAL_MS = 1000L;

    private final Context context;
    private final BottomSheetDialog dialog;
    /** 面板根布局；展开时按它的实际子视图高度反推列表该占多少。 */
    private final LinearLayout rootView;
    private final TextView titleView;
    private final TextView emptyView;
    private final TabLayout tabs;
    private final RecyclerView listView;
    private final LinearLayout actionRow;
    private final ItemAdapter adapter = new ItemAdapter();

    /** 当前选中的分页；0 = 下载中，1 = 已完成。 */
    private int tab = 0;
    /** 每秒刷新的任务；面板关掉时置空，避免持有已 dismiss 的视图。 */
    private Runnable refresh;
    /** 缓存里读出来的路径，按下载 id 缓存，避免每秒重算。 */
    private final Map<String, OfflineDownloadPath.Location> paths = new java.util.HashMap<>();

    private OfflineDownloadDialog(Context context) {
        this.context = context;
        this.dialog = new BottomSheetDialog(context);
        LinearLayout root = new LinearLayout(context);
        this.rootView = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(8));
        titleView = new TextView(context);
        titleView.setTextSize(16f);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setTextColor(ThemeColors.onSurface(context));
        titleView.setText(R.string.offline_download_title);
        root.addView(titleView, matchWrap());
        tabs = new TabLayout(context);
        tabs.setTabMode(TabLayout.MODE_FIXED);
        tabs.setTabGravity(TabLayout.GRAVITY_FILL);
        // 文字颜色跟着主题走：写死的话日间模式下选中/未选中的对比会糊成一片。
        tabs.setTabTextColors(ThemeColors.secondary(context), ThemeColors.accent(context));
        tabs.setSelectedTabIndicatorColor(ThemeColors.accent(context));
        LinearLayout.LayoutParams tabParams = matchWrap();
        tabParams.topMargin = ResUtil.dp2px(8);
        root.addView(tabs, tabParams);
        tabs.addTab(tabs.newTab().setText(R.string.offline_download_tab_active));
        tabs.addTab(tabs.newTab().setText(R.string.offline_download_tab_finished));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab selected) {
                tab = selected == null ? 0 : selected.getPosition();
                reload();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab selected) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab selected) {
            }
        });
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
        listView.setItemAnimator(null);
        root.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ResUtil.dp2px(320)));
        actionRow = new LinearLayout(context);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.END);
        root.addView(actionRow, matchWrap());
        dialog.setContentView(root);
        dialog.setOnDismissListener(ignored -> stopRefresh());
        expandSheet();
    }

    public static void show(@Nullable FragmentActivity activity) {
        if (activity == null) return;
        OfflineDownloadDialog panel = new OfflineDownloadDialog(activity);
        panel.dialog.show();
        panel.reload();
        panel.startRefresh();
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
            resizeList(height);
        });
    }

    /**
     * 列表吃掉 sheet 剩下的高度。
     *
     * <p>构造时给的是固定 320dp，那是「还没测量过」时的占位；sheet 高度定下来之后应该把
     * 标题、分页、底部动作行占掉的量扣掉，其余全给列表——一张卡片有标题、进度条、两行说明和
     * 三个按钮，固定 320dp 只看得见两三条，用户要不停滚动。
     *
     * <p>扣多少不写死：那些控件的实际高度随字号、语言、是否有底部动作行而变，写死就会在某种
     * 组合下算多（列表被裁）或算少（动作行被推出屏外）。这里直接量它们当前的高度。
     */
    private void resizeList(int sheetHeight) {
        rootView.post(() -> {
            int used = rootView.getPaddingTop() + rootView.getPaddingBottom();
            for (int i = 0; i < rootView.getChildCount(); i++) {
                View child = rootView.getChildAt(i);
                if (child != listView && child.getVisibility() != View.GONE) used += child.getHeight();
            }
            int listHeight = Math.max(ResUtil.dp2px(180), sheetHeight - used);
            ViewGroup.LayoutParams params = listView.getLayoutParams();
            if (params == null) return;
            params.height = listHeight;
            listView.setLayoutParams(params);
        });
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void startRefresh() {
        if (refresh != null) return;
        refresh = new Runnable() {
            @Override
            public void run() {
                if (refresh != this) return;
                reload();
                App.post(this, REFRESH_INTERVAL_MS);
            }
        };
        App.post(refresh, REFRESH_INTERVAL_MS);
    }

    private void stopRefresh() {
        if (refresh == null) return;
        Runnable pending = refresh;
        refresh = null;
        App.removeCallbacks(pending);
    }

    /**
     * 读下载索引 + 记录表。索引里有进度与状态，记录表里有标题（Media3 不存标题）。
     *
     * <p>两个分页各自过滤：已完成页只放真的下完的，其余（排队、下载中、暂停、失败）都算
     * 「下载中」，用户在没下完的时候不会在已完成页里找不到东西。
     */
    private void reload() {
        OfflineDownloadManager manager = OfflineDownloadManager.get();
        List<Download> all = manager.all();
        List<Row> rows = new ArrayList<>();
        int active = 0;
        int finished = 0;
        for (Download download : all) {
            if (download.request == null) continue;
            OfflineDownloadRecord record = manager.registry().get(download.request.id);
            String title = manager.registry().titleOf(download.request.id, download.request.uri.toString());
            OfflineDownloadKind kind = record == null ? OfflineDownloadKind.ofUrl(download.request.uri.toString()) : record.kind();
            Row row = new Row(download, title, kind);
            OfflineDownloadStatus.Phase phase = row.phase();
            if (OfflineDownloadStatus.finished(phase)) finished++;
            else active++;
            if (tab == 0 ? OfflineDownloadStatus.active(phase) : OfflineDownloadStatus.finished(phase)) rows.add(row);
        }
        OfflineDownloadStatus.sort(rows);
        prunePaths(all);
        adapter.submit(rows);
        updateTabTitles(active, finished);
        emptyView.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        titleView.setText(context.getString(R.string.offline_download_title) + " · " + context.getString(R.string.offline_download_used, OfflineDownloadStatus.sizeText(manager.usedBytes())));
        reloadActions(rows);
    }

    /** 分页标题带条数，用户一眼知道另一页有没有东西。 */
    private void updateTabTitles(int active, int finished) {
        TabLayout.Tab first = tabs.getTabAt(0);
        TabLayout.Tab second = tabs.getTabAt(1);
        if (first != null) first.setText(context.getString(R.string.offline_download_tab_active) + " (" + active + ")");
        if (second != null) second.setText(context.getString(R.string.offline_download_tab_finished) + " (" + finished + ")");
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

    /**
     * 点「播放」：确认缓存里确实是整段下好的，再交给播放页。
     *
     * <p><b>为什么不再看 {@code customCacheKey}。</b>播放侧的离线命中是靠
     * {@code OfflinePlaybackDataSource.proxyUrl(url)} 去缓存里查「这个地址整段是否都在」，
     * 与 cacheKey 无关；而且 HLS 在入队时是<em>故意不设</em> cacheKey 的（设了会被 media3
     * 直接拒绝，见 {@code OfflineDownloadCacheKey}）。拿 cacheKey 当门槛，HLS 这一档会读到
     * null 而崩在 {@code isEmpty()} 上，也说不清「到底能不能播」。
     */
    private void play(Row row) {
        if (!OfflineDownloadCache.complete(OfflineDownloadManager.get().cache(), row.url())) {
            Notify.show(row.stateText());
            return;
        }
        dialog.dismiss();
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

    /**
     * 丢掉已经被删掉的那些缓存的路径。
     *
     * <p>路径按 id 缓存是为了每秒刷新时不去反复问缓存层；但下载会被删掉，缓存只进不出会一直
     * 涨着——面板开着删几条、再下几条、再删，一路堆下去。传进来的必须是<b>全量</b>下载列表
     * （不是当前分页的那一份），否则另一页的条目会被误删缓存、下一秒重算，白做一遍。
     */
    private void prunePaths(List<Download> all) {
        if (paths.isEmpty()) return;
        List<String> alive = new ArrayList<>();
        for (Download download : all) {
            if (download.request != null) alive.add(download.request.id);
        }
        paths.keySet().retainAll(alive);
    }

    /** 一次只算一次路径：每秒刷新时直接用缓存，不再去问缓存层。 */
    private OfflineDownloadPath.Location locationOf(Row row) {
        OfflineDownloadPath.Location cached = paths.get(row.id());
        if (cached != null) return cached;
        OfflineDownloadPath.Location location;
        boolean done = true;
        try {
            List<String> files = OfflineDownloadCache.filesFor(OfflineDownloadManager.get().cache(), row.url());
            location = OfflineDownloadPath.locate(files, OfflineDownloadManager.downloadDir().getAbsolutePath());
            // 还没落盘（刚入队/刚重启）时先别记下来，否则用户会一直看到下载根目录。
            done = !files.isEmpty();
        } catch (Throwable ignored) {
            location = new OfflineDownloadPath.Location("", 0);
        }
        if (done) paths.put(row.id(), location);
        return location;
    }

    private final class Row implements OfflineDownloadStatus.OfflineDownloadRow {

        private final Download download;
        private final String title;
        private final OfflineDownloadKind kind;

        private Row(Download download, String title, OfflineDownloadKind kind) {
            this.download = download;
            this.title = title;
            this.kind = kind;
        }

        @Override
        public String id() {
            return download.request.id;
        }

        @Override
        public long createdAt() {
            return download.startTimeMs;
        }

        private String url() {
            return download.request.uri.toString();
        }

        @Override
        public OfflineDownloadStatus.Phase phase() {
            return OfflineDownloadStatus.phase(download.state, download.stopReason, download.failureReason);
        }

        private int percent() {
            return OfflineDownloadStatus.percent(download.getBytesDownloaded(), download.contentLength);
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
         * 进度那一行：下载中显示「百分比 · 已下/总长」，下完显示大小，失败显示状态。
         */
        private String detail() {
            OfflineDownloadStatus.Phase phase = phase();
            if (phase == OfflineDownloadStatus.Phase.DOWNLOADING) {
                int percent = percent();
                if (percent < 0) {
                    return OfflineDownloadStatus.sizeText(download.getBytesDownloaded()) + " / " + context.getString(R.string.offline_download_state_downloading);
                }
                return context.getString(R.string.offline_download_progress, percent) + " · "
                        + OfflineDownloadStatus.sizeText(download.getBytesDownloaded()) + " / "
                        + OfflineDownloadStatus.sizeText(download.contentLength);
            }
            if (phase == OfflineDownloadStatus.Phase.COMPLETED) return OfflineDownloadStatus.sizeText(download.contentLength);
            if (phase == OfflineDownloadStatus.Phase.PAUSED) {
                int percent = percent();
                return percent < 0 ? stateText()
                        : context.getString(R.string.offline_download_progress, percent) + " · " + stateText();
            }
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
            LinearLayout body = new LinearLayout(context);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(10), ResUtil.dp2px(12), ResUtil.dp2px(10));
            body.setBackgroundColor(ThemeColors.rowBackground(context));

            // 第一行：标题 + 右侧状态
            LinearLayout top = new LinearLayout(context);
            top.setOrientation(LinearLayout.HORIZONTAL);
            TextView label = new TextView(context);
            label.setTextSize(14f);
            label.setTextColor(ThemeColors.onSurface(context));
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            TextView state = new TextView(context);
            state.setTextSize(12f);
            state.setTextColor(ThemeColors.secondary(context));
            state.setSingleLine(true);
            top.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            top.addView(state, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            body.addView(top, matchWrap());

            // 进度条：高度很薄，未下完时才有意义
            ProgressBar progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(100);
            LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ResUtil.dp2px(4));
            progressParams.topMargin = ResUtil.dp2px(6);
            body.addView(progress, progressParams);

            // 第二行：百分比 · 已下/总长
            TextView detail = new TextView(context);
            detail.setTextSize(11f);
            detail.setTextColor(ThemeColors.detail(context));
            detail.setSingleLine(true);
            LinearLayout.LayoutParams detailParams = matchWrap();
            detailParams.topMargin = ResUtil.dp2px(4);
            body.addView(detail, detailParams);

            // 第三行：文件路径
            TextView path = new TextView(context);
            path.setTextSize(11f);
            path.setTextColor(ThemeColors.detail(context));
            path.setSingleLine(true);
            path.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams pathParams = matchWrap();
            pathParams.topMargin = ResUtil.dp2px(2);
            body.addView(path, pathParams);

            // 第四行：这一条自己的按钮
            LinearLayout buttons = new LinearLayout(context);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setGravity(Gravity.END);
            TextView toggle = button();
            TextView play = button();
            TextView delete = button();
            buttons.addView(toggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            buttons.addView(play, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            buttons.addView(delete, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams buttonsParams = matchWrap();
            buttonsParams.topMargin = ResUtil.dp2px(6);
            body.addView(buttons, buttonsParams);

            return new ItemHolder(body, label, state, progress, detail, path, toggle, play, delete);
        }

        private TextView button() {
            TextView view = new TextView(context);
            view.setTextSize(13f);
            view.setTextColor(ThemeColors.action(context));
            view.setPadding(ResUtil.dp2px(10), ResUtil.dp2px(4), ResUtil.dp2px(10), ResUtil.dp2px(4));
            return view;
        }

        @Override
        public void onBindViewHolder(@NonNull ItemHolder holder, int position) {
            Row row = rows.get(position);
            OfflineDownloadStatus.Phase phase = row.phase();
            holder.label.setText(row.title);
            holder.state.setText(row.stateText());
            holder.detail.setText(row.detail());

            // 进度条：能动的档位才显示；百分比未知时用不确定态，避免一直卡在 0%
            boolean showProgress = OfflineDownloadStatus.showsProgress(phase);
            if (showProgress) {
                int percent = row.percent();
                holder.progress.setIndeterminate(percent < 0);
                if (percent >= 0) holder.progress.setProgress(percent);
                holder.progress.setVisibility(View.VISIBLE);
            } else {
                holder.progress.setVisibility(View.GONE);
            }

            // 文件路径：下好或正在下的都显示，让用户能去文件管理器找到它
            OfflineDownloadPath.Location location = locationOf(row);
            if (location.hasPath() && phase != OfflineDownloadStatus.Phase.REMOVING) {
                holder.path.setText(context.getString(R.string.offline_download_path) + "："
                        + location.path()
                        + (location.needsCount() ? "（" + context.getString(R.string.offline_download_file_count, location.fileCount()) + "）" : ""));
                holder.path.setVisibility(View.VISIBLE);
            } else {
                holder.path.setVisibility(View.GONE);
            }

            // 每一条自己的按钮：暂停/继续、播放（下完才有）、删除
            //
            // 「暂停」只给还在走的档位（下载中、排队中），「继续」只给用户自己停下的那一档。
            // FAILED 不给暂停/继续：Media3 的 setStopReason 改不动终态下载，点了继续也起不来，
            // 与其放一个按了没反应的按钮，不如不放（失败的那条仍可删除）。
            boolean canToggle = phase == OfflineDownloadStatus.Phase.DOWNLOADING
                    || phase == OfflineDownloadStatus.Phase.QUEUED
                    || phase == OfflineDownloadStatus.Phase.PAUSED;
            boolean resumable = phase == OfflineDownloadStatus.Phase.PAUSED;
            holder.toggle.setText(resumable ? R.string.offline_download_resume_one : R.string.pause);
            holder.toggle.setVisibility(canToggle ? View.VISIBLE : View.GONE);
            holder.toggle.setOnClickListener(view -> {
                OfflineDownloadService.setPaused(context, row.id(), !resumable);
                reload();
            });

            holder.play.setText(R.string.offline_download_play);
            holder.play.setVisibility(OfflineDownloadStatus.playable(phase) ? View.VISIBLE : View.GONE);
            holder.play.setOnClickListener(view -> play(row));

            holder.delete.setText(R.string.setting_delete);
            holder.delete.setVisibility(OfflineDownloadStatus.removable(phase) ? View.VISIBLE : View.GONE);
            holder.delete.setOnClickListener(view -> confirmDelete(row));

            // 点整行：下完的直接播；没下完的提示状态，避免误触把队列弄乱。
            holder.itemView.setOnClickListener(view -> {
                if (OfflineDownloadStatus.playable(row.phase())) play(row);
                else Notify.show(row.stateText());
            });
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private static final class ItemHolder extends RecyclerView.ViewHolder {

        private final TextView label;
        private final TextView state;
        private final ProgressBar progress;
        private final TextView detail;
        private final TextView path;
        private final TextView toggle;
        private final TextView play;
        private final TextView delete;

        private ItemHolder(@NonNull View itemView, TextView label, TextView state, ProgressBar progress,
                           TextView detail, TextView path, TextView toggle, TextView play, TextView delete) {
            super(itemView);
            this.label = label;
            this.state = state;
            this.progress = progress;
            this.detail = detail;
            this.path = path;
            this.toggle = toggle;
            this.play = play;
            this.delete = delete;
        }
    }
}
