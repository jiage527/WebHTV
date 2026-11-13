package com.fongmi.android.tv.ui.dialog;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.PlayQueue;
import com.fongmi.android.tv.setting.PlayQueueStore;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.ThemeColors;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放队列面板：查看、拖拽排序、删除、切循环模式，并可点击任意项直接播放。
 *
 * <p>用 {@link BottomSheetDialog} 而不是普通弹窗，因为队列可能很长，需要在播放画面上
 * 滚动浏览；这与项目里其它播放相关的面板保持一致。拖拽排序复用 {@link ItemTouchHelper}，
 * 与自定义采集站、播放按钮配置的做法相同。
 *
 * <p>队列的顺位规则全部交给 {@link PlayQueue}，这里只负责把它的结果画出来，
 * 避免界面层再维护一份下标。
 *
 * <p><b>添加入口放在这里，而不是只挂在播放页按钮的长按上。</b>之前面板只有「清空队列」，
 * 而「加入队列」全靠长按播放页的队列/剧集按钮——没有任何视觉提示，用户找不到，
 * 只会觉得这个功能没做完。面板是用户唯一会主动打开来看队列的地方，所以「加当前」
 * 与「加整季」两个按钮就放在这里；长按那两条路径保留，两者共用同一份实现。
 */
public final class PlayQueueDialog {

    public interface Listener {

        /** 用户在队列里点选了某一项，应当立即播放它。 */
        void onPlayQueueEntry(PlayQueue.Entry entry);

        /**
         * 把当前正在播的这一条加入队列。
         *
         * <p>「当前在播什么」只有播放页知道，所以这件事由调用方做，面板只负责给出入口。
         * 加完返回 true 表示确实进去了，面板据此提示并刷新。
         */
        boolean onAddCurrentToQueue();

        /** 把当前源的全部剧集加入队列；返回真正加入的数量，0 表示没得加。 */
        int onAddSeasonToQueue();
    }

    private final Context context;
    private final Listener listener;
    private final PlayQueue queue;
    private final List<PlayQueue.Entry> items = new ArrayList<>();

    @Nullable
    private BottomSheetDialog dialog;
    private RecyclerView recyclerView;
    private TextView titleView;
    private TextView modeView;
    private TextView emptyView;

    private PlayQueueDialog(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.queue = PlayQueueStore.get();
    }

    public static void show(Context context, Listener listener) {
        new PlayQueueDialog(context, listener).show();
    }

    private void show() {
        dialog = new BottomSheetDialog(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = ResUtil.dp2px(16);
        root.setPadding(padding, padding, padding, ResUtil.dp2px(8));

        root.addView(buildHeader());
        root.addView(buildEmpty());
        recyclerView = new RecyclerView(context);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        recyclerView.setAdapter(new QueueAdapter());
        root.addView(recyclerView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(buildFooter());

        dialog.setContentView(root);
        dialog.show();
        refresh();
    }

    private View buildHeader() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        titleView = new TextView(context);
        titleView.setTextSize(16);
        titleView.setTextColor(ThemeColors.onSurface(context));
        row.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        // 循环模式做成一个可点的标签，点一下在「顺序 / 列表 / 单集」之间轮换。
        modeView = new TextView(context);
        modeView.setTextSize(13);
        modeView.setTextColor(ThemeColors.action(context));
        modeView.setPadding(ResUtil.dp2px(10), ResUtil.dp2px(4), ResUtil.dp2px(10), ResUtil.dp2px(4));
        modeView.setOnClickListener(view -> {
            queue.setMode(PlayQueue.cycleMode(queue.mode()));
            PlayQueueStore.save();
            refresh();
        });
        row.addView(modeView);
        return row;
    }

    /**
     * 空队列时的提示。
     *
     * <p>以前队列空着打开面板，只有标题和「清空队列」——看起来像坏了，用户也找不到
     * 添加的地方（添加原本只挂在播放页按钮的长按上，没有任何视觉提示）。这里既解释
     * 现状，也直接把添加入口放在眼前，而不是让用户去猜长按。
     */
    private TextView buildEmpty() {
        emptyView = new TextView(context);
        emptyView.setText(R.string.play_queue_empty_hint);
        emptyView.setTextSize(13);
        emptyView.setTextColor(ThemeColors.detail(context));
        emptyView.setPadding(0, ResUtil.dp2px(8), 0, ResUtil.dp2px(8));
        return emptyView;
    }
    private View buildFooter() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        // 添加类动作用主色、放在左边；「清空」是破坏性操作，靠右并降到次要色，
        // 避免和添加挨在一起被误点。
        row.addView(action(R.string.play_queue_add_current, ThemeColors.accent(context), view -> addCurrent()));
        row.addView(action(R.string.play_queue_add_season, ThemeColors.accent(context), view -> addSeason()));
        View spacer = new View(context);
        row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        row.addView(action(R.string.play_queue_clear, ThemeColors.detail(context), view -> confirmClear()));
        return row;
    }

    private TextView action(int label, int color, View.OnClickListener listener) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(14);
        view.setTextColor(color);
        view.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(8), ResUtil.dp2px(12), ResUtil.dp2px(8));
        view.setOnClickListener(listener);
        return view;
    }

    /** 加入当前这一条。面板不自己判断「当前在播什么」，交给播放页回答。 */
    private void addCurrent() {
        if (listener.onAddCurrentToQueue()) {
            refresh();
            Notify.show(R.string.play_queue_added_current);
        } else {
            Notify.show(R.string.play_queue_unsupported);
        }
    }

    /** 加入当前源的全部剧集。 */
    private void addSeason() {
        int added = listener.onAddSeasonToQueue();
        if (added > 0) {
            refresh();
            Notify.show(context.getString(R.string.play_queue_add_season_done, added));
        } else {
            Notify.show(R.string.play_queue_unsupported);
        }
    }

    private void confirmClear() {
        if (queue.isEmpty()) {
            Notify.show(R.string.play_queue_empty);
            return;
        }
        new MaterialAlertDialogBuilder(context)
                .setMessage(R.string.play_queue_clear_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    queue.clear();
                    PlayQueueStore.save();
                    Notify.show(R.string.play_queue_cleared);
                    refresh();
                })
                .show();
    }

    /** 把模型里的队列同步进列表并刷新文字。 */
    private void refresh() {
        items.clear();
        items.addAll(queue.entries());
        if (recyclerView != null && recyclerView.getAdapter() != null) recyclerView.getAdapter().notifyDataSetChanged();
        if (titleView != null) titleView.setText(context.getString(R.string.play_queue_title));
        if (modeView != null) modeView.setText(context.getString(modeLabel(queue.mode())));
        // 空队列时列表没什么可看，换成一段带指引的说明；非空时把列表让出来。
        if (emptyView != null) emptyView.setVisibility(queue.isEmpty() ? View.VISIBLE : View.GONE);
        if (recyclerView != null) recyclerView.setVisibility(queue.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static int modeLabel(String mode) {
        return switch (PlayQueue.normalizeMode(mode)) {
            case PlayQueue.MODE_ALL -> R.string.play_queue_mode_all;
            case PlayQueue.MODE_ONE -> R.string.play_queue_mode_one;
            default -> R.string.play_queue_mode_off;
        };
    }

    private void dismiss() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    /** 队列列表；每行显示名称与「正在播放」标记，长按进入拖拽排序。 */
    private final class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.Holder> {

        QueueAdapter() {
            ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
                @Override
                public boolean onMove(@NonNull RecyclerView parent, @NonNull RecyclerView.ViewHolder source, @NonNull RecyclerView.ViewHolder target) {
                    int from = source.getBindingAdapterPosition();
                    int to = target.getBindingAdapterPosition();
                    if (from < 0 || to < 0) return false;
                    if (!queue.move(from, to)) return false;
                    // 先把界面挪到新顺序，再让模型结果覆盖它，拖动过程才不会闪。
                    items.add(to, items.remove(from));
                    notifyItemMoved(from, to);
                    return true;
                }

                @Override
                public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
                }

                @Override
                public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                    super.clearView(recyclerView, viewHolder);
                    // 拖完后「当前项」的下标可能已经变了，要重画一次高亮，
                    // 否则「正在播放」会标在错的条目上。
                    PlayQueueStore.save();
                    refresh();
                }

                @Override
                public boolean isLongPressDragEnabled() {
                    return true;
                }
            });
            helper.attachToRecyclerView(recyclerView);
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int padding = ResUtil.dp2px(12);
            row.setPadding(padding, padding, padding, padding);

            TextView name = new TextView(context);
            name.setTextSize(15);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setTextColor(ThemeColors.onSurface(context));
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            TextView remove = new TextView(context);
            remove.setText(R.string.play_queue_remove);
            remove.setTextSize(13);
            remove.setTextColor(ThemeColors.detail(context));
            row.addView(remove);

            return new Holder(row, name, remove);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            PlayQueue.Entry entry = items.get(position);
            boolean current = position == queue.currentIndex();
            // 正在播放的那一项加前缀并高亮，否则用户分不清当前进度。
            holder.name.setText(current ? context.getString(R.string.play_queue_current) + " · " + entry.displayName() : entry.displayName());
            // 正在播放的那一项：用主题的高亮色，而不是写死的浅蓝——写死色只在深色背景下好看。
            holder.name.setTextColor(current ? ThemeColors.accent(context) : ThemeColors.onSurface(context));
            holder.itemView.setOnClickListener(view -> {
                int index = holder.getBindingAdapterPosition();
                if (index < 0 || index >= items.size()) return;
                // 队列里点选 = 直接播放该项，并把它记成当前项。
                PlayQueue.Entry target = items.get(index);
                queue.setCurrent(target.url);
                PlayQueueStore.save();
                dismiss();
                listener.onPlayQueueEntry(target);
            });
            holder.remove.setOnClickListener(view -> {
                int index = holder.getBindingAdapterPosition();
                if (index < 0 || index >= items.size()) return;
                queue.remove(index);
                PlayQueueStore.save();
                Notify.show(R.string.play_queue_removed);
                refresh();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class Holder extends RecyclerView.ViewHolder {

            final TextView name;
            final TextView remove;

            Holder(@NonNull View itemView, TextView name, TextView remove) {
                super(itemView);
                this.name = name;
                this.remove = remove;
            }
        }
    }
}
