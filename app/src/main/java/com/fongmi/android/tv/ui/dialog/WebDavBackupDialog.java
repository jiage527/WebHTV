package com.fongmi.android.tv.ui.dialog;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.netdisk.NetDiskAccount;
import com.fongmi.android.tv.netdisk.NetDiskCredentialStore;
import com.fongmi.android.tv.netdisk.NetDiskEntry;
import com.fongmi.android.tv.netdisk.NetDiskType;
import com.fongmi.android.tv.netdisk.WebDavBackupClient;
import com.fongmi.android.tv.netdisk.WebDavBackupError;
import com.fongmi.android.tv.netdisk.WebDavBackupStore;
import com.fongmi.android.tv.utils.AppBackup;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.ThemeColors;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Path;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WebDAV 云备份面板：把应用数据备份到用户自己的 WebDAV 网盘，并从云端列表恢复。
 *
 * <p><b>与既有入口的关系。</b>本地备份走设置页的「备份/恢复」，Git 云盘走
 * {@link GitCloudDialog}（需要建私有仓库与 token）。这里补上第三种去处：用户已有的
 * 坚果云 / Nextcloud / 群晖 / Alist，只需填地址与账号密码，不必学 Git。
 *
 * <p><b>复用既有引擎。</b>包的生成与还原一律用 {@link AppBackup}，与本地备份、Git 云备份
 * 是同一个格式，所以三处产生的备份可以互相恢复，也不新增版本字段、不做数据迁移。
 *
 * <p><b>为什么手动搭界面。</b>与 {@link NetDiskDialog} 同理：手机与电视两套布局目录各自
 * 维护 XML，本面板控件全是标准件（文本行 + 列表），代码搭一次即可两端共用。
 */
public final class WebDavBackupDialog {

    private final Context context;
    private final FragmentActivity activity;
    private final Fragment fragment;
    private final Runnable onChanged;
    private final List<NetDiskEntry> entries = new ArrayList<>();
    private final Map<String, Long> sizes = new HashMap<>();

    private BottomSheetDialog dialog;
    private RecyclerView recyclerView;
    private TextView titleView;
    private TextView statusView;
    private LinearLayout actionRow;

    /** 当前选中的账号；为 null 时显示账号列表。 */
    private NetDiskAccount account;
    private boolean loading;

    private WebDavBackupDialog(Context context, FragmentActivity activity, Fragment fragment, Runnable onChanged) {
        this.context = context;
        this.activity = activity;
        this.fragment = fragment;
        this.onChanged = onChanged;
    }

    public static void show(Fragment fragment, Runnable onChanged) {
        new WebDavBackupDialog(fragment.requireContext(), null, fragment, onChanged).show();
    }

    public static void show(FragmentActivity activity, Runnable onChanged) {
        new WebDavBackupDialog(activity, activity, null, onChanged).show();
    }

    /** 设置页右侧状态文字要用：可用的 WebDAV 存储数量。 */
    public static int storageCount() {
        int count = 0;
        for (NetDiskAccount item : NetDiskCredentialStore.get().getAccounts()) {
            if (item.getType() == NetDiskType.WEBDAV && item.isUsable()) count++;
        }
        return count;
    }

    private void show() {
        dialog = new BottomSheetDialog(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = ResUtil.dp2px(16);
        root.setPadding(padding, padding, padding, ResUtil.dp2px(8));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        titleView = new TextView(context);
        titleView.setTextSize(16);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(ThemeColors.onSurface(context));
        header.addView(titleView);
        statusView = new TextView(context);
        statusView.setTextSize(12);
        statusView.setTextColor(ThemeColors.secondary(context));
        statusView.setSingleLine(true);
        statusView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        header.addView(statusView);
        root.addView(header);

        recyclerView = new RecyclerView(context);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        recyclerView.setAdapter(new EntryAdapter());
        root.addView(recyclerView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ResUtil.dp2px(300)));

        actionRow = new LinearLayout(context);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.END);
        root.addView(actionRow);

        dialog.setContentView(root);
        expandSheet();
        dialog.show();
        showAccounts();
    }

    /**
     * 让面板展开到内容高度。
     *
     * <p><b>不设限定高度时，这个面板会缩成一条。</b>BottomSheet 按内容测量，
     * 而这里的内容是一列标准控件，在横屏平板上量出来的高度远小于容器，于是
     * 动作行（「立即备份」/「添加存储」）会被裁到屏幕外，用户看到的就是「点了没反应」。
     * 与 {@code OneKeySyncDialog#setBehavior}、{@code DanmakuSettingDialog#setBehavior}
     * 同样的处理：明确给出高度并直接置为展开态。
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

    // ------------------------------------------------------------------ 账号

    private List<NetDiskAccount> accounts() {
        List<NetDiskAccount> result = new ArrayList<>();
        for (NetDiskAccount item : NetDiskCredentialStore.get().getAccounts()) {
            if (item.getType() == NetDiskType.WEBDAV && item.isUsable()) result.add(item);
        }
        return result;
    }

    private void showAccounts() {
        account = null;
        entries.clear();
        sizes.clear();
        for (NetDiskAccount item : accounts()) {
            entries.add(new NetDiskEntry(item.getDisplayName(), item.getId(), true, 0, 0));
        }
        titleView.setText(R.string.webdav_backup_title);
        statusView.setText(entries.isEmpty()
                ? context.getString(R.string.webdav_backup_no_account)
                : context.getString(R.string.webdav_backup_account_count, entries.size()));
        reloadActions();
        notifyList();
    }

    private void reloadActions() {
        if (actionRow == null) return;
        actionRow.removeAllViews();
        if (account == null) {
            actionRow.addView(action(R.string.webdav_backup_add_storage, view -> {
                dismiss();
                NetDiskDialog.show(context, (url, name) -> {
                });
            }));
            return;
        }
        actionRow.addView(action(R.string.webdav_backup_now, view -> startBackup()));
        actionRow.addView(action(R.string.webdav_backup_back, view -> showAccounts()));
    }

    private TextView action(int textRes, View.OnClickListener click) {
        TextView view = new TextView(context);
        view.setText(textRes);
        view.setTextSize(14);
        view.setTextColor(ThemeColors.action(context));
        view.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(8), ResUtil.dp2px(12), ResUtil.dp2px(8));
        view.setOnClickListener(click);
        return view;
    }

    // ------------------------------------------------------------------ 云端列表

    private void load() {
        if (account == null || loading) return;
        loading = true;
        statusView.setText(R.string.webdav_backup_loading);
        Task.execute(() -> {
            WebDavBackupClient client = null;
            try {
                client = new WebDavBackupClient(account);
                client.connect();
                // 先把目录建好再列：首次使用时目录还不存在，直接列会得到 404，
                // 用户看到的是「找不到目录」，而实际只是还没备份过。
                client.mkdirs(WebDavBackupStore.effectiveDir(account.getRoot()));
                List<String> names = client.names(WebDavBackupStore.effectiveDir(account.getRoot()));
                List<String> sorted = WebDavBackupStore.backups(names);
                App.post(() -> onLoaded(sorted));
            } catch (Throwable e) {
                SpiderDebug.log("webdav-backup", "list failed error=%s", e.toString());
                String message = describe(e);
                App.post(() -> {
                    loading = false;
                    statusView.setText("");
                    Notify.show(message);
                });
            } finally {
                if (client != null) client.close();
            }
        });
    }

    private void onLoaded(List<String> sorted) {
        loading = false;
        entries.clear();
        sizes.clear();
        for (String name : sorted) {
            entries.add(new NetDiskEntry(WebDavBackupStore.displayName(name), name, false, 0, 0));
        }
        titleView.setText(R.string.webdav_backup_restore);
        statusView.setText(entries.isEmpty()
                ? context.getString(R.string.webdav_backup_none)
                : context.getString(R.string.webdav_backup_count, entries.size()));
        reloadActions();
        notifyList();
        loadSizes();
    }

    /** 大小单独再列一次：列表本身只需要名字，体积是给用户判断用的附加信息。 */
    private void loadSizes() {
        final NetDiskAccount target = account;
        if (target == null) return;
        Task.execute(() -> {
            WebDavBackupClient client = null;
            try {
                client = new WebDavBackupClient(target);
                Map<String, Long> found = client.sizes(WebDavBackupStore.effectiveDir(target.getRoot()));
                App.post(() -> {
                    if (account != target) return;
                    sizes.clear();
                    sizes.putAll(found);
                    notifyList();
                });
            } catch (Throwable ignored) {
            } finally {
                if (client != null) client.close();
            }
        });
    }

    private void notifyList() {
        if (recyclerView != null && recyclerView.getAdapter() != null) recyclerView.getAdapter().notifyDataSetChanged();
    }

    // ------------------------------------------------------------------ 备份

    private void startBackup() {
        final NetDiskAccount target = account;
        if (target == null) return;
        withFileAccess(granted -> {
            if (!granted) {
                Notify.show(R.string.webdav_backup_need_permission);
                return;
            }
            BackupProgressDialog progress = BackupProgressDialog.open(manager(), ResUtil.getString(R.string.webdav_backup_now));
            Task.execute(() -> runBackup(target, progress));
        });
    }

    private void runBackup(NetDiskAccount target, BackupProgressDialog progress) {
        WebDavBackupClient client = null;
        File temp = null;
        try {
            progress.update(ResUtil.getString(R.string.webdav_backup_stage_pack), 0, 0, 0);
            temp = AppBackup.createTemp(progress::update);
            long size = temp.length();
            progress.update(ResUtil.getString(R.string.webdav_backup_stage_connect), 0, 0, 0);
            client = new WebDavBackupClient(target);
            client.connect();
            String dir = WebDavBackupStore.effectiveDir(target.getRoot());
            long free = client.freeBytes(dir);
            if (!WebDavBackupStore.fitsQuota(free, size)) {
                String need = FileUtil.byteCountToDisplaySize(size + size / 10);
                throw new QuotaException(ResUtil.getString(R.string.webdav_backup_quota_low, need));
            }
            client.mkdirs(dir);
            // 远端名字必须用 AppBackup.fileName()（bak-yyyyMMdd-HHmm.zip），不能用临时文件名：
            // createTemp 是 File.createTempFile，名字里带一串随机数，用它会同时坏掉两件事 ——
            // 云端列表没法按时间排序，WebDavBackupStore.backups() 也会把它当成非备份过滤掉。
            String remote = WebDavBackupStore.remotePath(dir, AppBackup.fileName());
            progress.update(ResUtil.getString(R.string.webdav_backup_stage_upload), 0, 0, size);
            client.upload(temp, remote, (written, total) -> {
                int percent = total > 0 ? (int) Math.min(99, written * 100 / total) : 0;
                App.post(() -> progress.update(ResUtil.getString(R.string.webdav_backup_stage_upload), percent, written, total));
            });
            progress.update(ResUtil.getString(R.string.webdav_backup_stage_clean), 100, size, size);
            for (String stale : WebDavBackupStore.stale(WebDavBackupStore.backups(client.names(dir)), WebDavBackupStore.DEFAULT_KEEP)) {
                try {
                    client.delete(WebDavBackupStore.remotePath(dir, stale));
                } catch (Throwable e) {
                    // 裁剪失败不影响这次备份已经成功，只记日志。
                    SpiderDebug.log("webdav-backup", "trim failed name=%s error=%s", stale, e.toString());
                }
            }
            App.post(() -> {
                progress.finish();
                Notify.show(R.string.webdav_backup_done);
                load();
            });
        } catch (Throwable e) {
            SpiderDebug.log("webdav-backup", "backup failed error=%s", e.toString());
            String message = describe(e);
            App.post(() -> {
                progress.finish();
                Notify.show(message);
            });
        } finally {
            if (client != null) client.close();
            if (temp != null) Path.clear(temp);
        }
    }

    // ------------------------------------------------------------------ 恢复

    private void confirmRestore(String name) {
        new MaterialAlertDialogBuilder(context)
                .setMessage(R.string.webdav_backup_restore_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, which) -> startRestore(name))
                .show();
    }

    private void startRestore(String name) {
        final NetDiskAccount target = account;
        if (target == null) return;
        withFileAccess(granted -> {
            if (!granted) {
                Notify.show(R.string.webdav_backup_need_permission);
                return;
            }
            BackupProgressDialog progress = BackupProgressDialog.open(manager(), ResUtil.getString(R.string.webdav_backup_restore));
            Task.execute(() -> runRestore(target, name, progress));
        });
    }

    private void runRestore(NetDiskAccount target, String name, BackupProgressDialog progress) {
        WebDavBackupClient client = null;
        File archive = null;
        try {
            progress.update(ResUtil.getString(R.string.webdav_backup_stage_connect), 0, 0, 0);
            client = new WebDavBackupClient(target);
            client.connect();
            String dir = WebDavBackupStore.effectiveDir(target.getRoot());
            archive = new File(Path.cache(), name);
            String remote = WebDavBackupStore.remotePath(dir, name);
            progress.update(ResUtil.getString(R.string.webdav_backup_stage_download), 0, 0, 0);
            client.download(remote, archive, (written, total) -> {
                int percent = total > 0 ? (int) Math.min(99, written * 100 / total) : 0;
                App.post(() -> progress.update(ResUtil.getString(R.string.webdav_backup_stage_download), percent, written, total));
            });
            if (!AppBackup.isBackup(archive)) throw new IllegalStateException(ResUtil.getString(R.string.webdav_backup_invalid));
            // 恢复前先在本地留一份当前状态：还原失败或用户后悔时可以再吃回来。
            progress.update(ResUtil.getString(R.string.webdav_backup_stage_snapshot), 0, 0, 0);
            AppBackup.create(new File(Path.tv(), AppBackup.fileName()), null);
            progress.update(ResUtil.getString(R.string.webdav_backup_stage_restore), 0, 0, 0);
            AppBackup.restore(archive, progress::update);
            App.post(() -> {
                progress.finish();
                Notify.show(R.string.webdav_backup_restore_done);
                load();
            });
        } catch (Throwable e) {
            SpiderDebug.log("webdav-backup", "restore failed error=%s", e.toString());
            String message = describe(e);
            App.post(() -> {
                progress.finish();
                Notify.show(message);
            });
        } finally {
            if (client != null) client.close();
            if (archive != null) Path.clear(archive);
        }
    }

    // ------------------------------------------------------------------ 删除

    private void confirmDelete(String name) {
        new MaterialAlertDialogBuilder(context)
                .setMessage(R.string.webdav_backup_delete_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, which) -> startDelete(name))
                .show();
    }

    private void startDelete(String name) {
        final NetDiskAccount target = account;
        if (target == null) return;
        Task.execute(() -> {
            WebDavBackupClient client = null;
            try {
                client = new WebDavBackupClient(target);
                String dir = WebDavBackupStore.effectiveDir(target.getRoot());
                client.delete(WebDavBackupStore.remotePath(dir, name));
                App.post(this::load);
            } catch (Throwable e) {
                SpiderDebug.log("webdav-backup", "delete failed error=%s", e.toString());
                String message = describe(e);
                App.post(() -> Notify.show(message));
            } finally {
                if (client != null) client.close();
            }
        });
    }

    // ------------------------------------------------------------------ 工具

    private void withFileAccess(java.util.function.Consumer<Boolean> callback) {
        if (fragment != null) PermissionUtil.requestFile(fragment, callback);
        else if (activity != null) PermissionUtil.requestFile(activity, callback);
        else callback.accept(true);
    }

    private FragmentManager manager() {
        if (fragment != null) return fragment.getParentFragmentManager();
        return activity.getSupportFragmentManager();
    }

    private void dismiss() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
        if (onChanged != null) onChanged.run();
    }

    /** 把失败翻成用户看得懂的一句话：分区文案 + 原始信息，避免「点了没反应」。 */
    private String describe(Throwable error) {
        if (error instanceof QuotaException) return error.getMessage();
        WebDavBackupError kind = WebDavBackupError.of(error);
        switch (kind) {
            case NETWORK:
                return ResUtil.getString(R.string.webdav_backup_error_network);
            case AUTH:
                return ResUtil.getString(R.string.webdav_backup_error_auth);
            case FORBIDDEN:
                return ResUtil.getString(R.string.webdav_backup_error_forbidden);
            case NOT_FOUND:
                return ResUtil.getString(R.string.webdav_backup_error_not_found);
            case CONFLICT:
                return ResUtil.getString(R.string.webdav_backup_error_conflict);
            case UNSUPPORTED:
                return ResUtil.getString(R.string.webdav_backup_error_unsupported);
            case QUOTA:
                return ResUtil.getString(R.string.webdav_backup_error_quota);
            case SERVER:
                return ResUtil.getString(R.string.webdav_backup_error_server);
            default:
                return ResUtil.getString(R.string.webdav_backup_error_unknown, detail(error));
        }
    }

    private static String detail(Throwable error) {
        if (error == null) return "";
        String message = error.getMessage();
        if (message == null || message.isEmpty()) return error.getClass().getSimpleName();
        return message.length() > 120 ? message.substring(0, 120) : message;
    }

    /** 空间不足要单独成类：它得原样带出「需要多少」这句话，不能被通用文案盖掉。 */
    private static final class QuotaException extends Exception {

        QuotaException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ 列表

    private final class EntryAdapter extends RecyclerView.Adapter<EntryAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int padding = ResUtil.dp2px(12);
            row.setPadding(padding, padding, padding, padding);
            row.setBackgroundColor(ThemeColors.rowBackground(context));

            TextView label = new TextView(context);
            label.setTextSize(14);
            label.setTextColor(ThemeColors.onSurface(context));
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            TextView size = new TextView(context);
            size.setTextSize(11);
            size.setTextColor(ThemeColors.detail(context));
            row.addView(size);
            return new Holder(row, label, size);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            NetDiskEntry entry = entries.get(position);
            holder.label.setText(entry.getName());
            if (account == null) {
                holder.size.setText(NetDiskType.WEBDAV.id().toUpperCase(java.util.Locale.ROOT));
            } else {
                Long size = sizes.get(entry.getPath());
                holder.size.setText(size == null ? "" : FileUtil.byteCountToDisplaySize(size));
            }
            holder.row.setOnClickListener(view -> {
                if (account == null) {
                    NetDiskAccount picked = NetDiskCredentialStore.get().get(entry.getPath());
                    if (picked == null) return;
                    account = picked;
                    titleView.setText(R.string.webdav_backup_restore);
                    statusView.setText(R.string.webdav_backup_loading);
                    entries.clear();
                    notifyList();
                    load();
                } else {
                    confirmRestore(entry.getPath());
                }
            });
            holder.row.setOnLongClickListener(view -> {
                if (account != null) confirmDelete(entry.getPath());
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }

        final class Holder extends RecyclerView.ViewHolder {

            private final LinearLayout row;
            private final TextView label;
            private final TextView size;

            Holder(LinearLayout row, TextView label, TextView size) {
                super(row);
                this.row = row;
                this.label = label;
                this.size = size;
            }
        }
    }
}
