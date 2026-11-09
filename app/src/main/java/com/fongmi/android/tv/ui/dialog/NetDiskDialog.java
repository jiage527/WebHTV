package com.fongmi.android.tv.ui.dialog;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.netdisk.NetDiskAccount;
import com.fongmi.android.tv.netdisk.NetDiskCodec;
import com.fongmi.android.tv.netdisk.NetDiskCredentialStore;
import com.fongmi.android.tv.netdisk.NetDiskEntry;
import com.fongmi.android.tv.netdisk.NetDiskSession;
import com.fongmi.android.tv.netdisk.NetDiskType;
import com.fongmi.android.tv.netdisk.NetDiskUrl;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 网络存储面板：管理账号、浏览目录、把文件送去播放。
 *
 * <p><b>与既有入口的关系。</b>本地文件走 {@code FileActivity}，GitCloud 走
 * {@code GitCloudDialog}，这里补上第三种来源（SMB / WebDAV），并沿用同一套
 * {@link BottomSheetDialog} + {@code RecyclerView} 的观感，避免用户在两处看到不同交互。
 *
 * <p><b>为什么手动搭界面。</b>项目里手机与电视两套布局目录各自维护 XML，
 * 加一个面板就要改两处；本面板的控件全是标准件（文本行 + 列表），用代码搭一次
 * 即可两端共用，和 {@code PlayQueueDialog}、{@code KeepOrganizeDialog} 的做法一致。
 *
 * <p><b>为什么要先换成 http 地址。</b>播放器读不了 {@code smb://}：随包
 * {@code libmpv.so} 既无 libsmbclient 也无 {@code smb://} 字样（双 ABI 核对过），
 * Exo 也没有 SMB 数据源。所以点文件时交给播放器的是指向本机
 * {@code /netdisk} 端点的 http 地址，由 {@code NetDiskProxy} 去真的读字节。
 * 这样播放器那一侧一行都不用改。
 */
public final class NetDiskDialog {

    public interface Listener {

        /**
         * 用户选中了一个可播放文件。
         *
         * @param url  指向本机代理端点的真实可播地址
         * @param name 显示名
         */
        void onPlay(String url, String name);
    }

    private final Context context;
    private final Listener listener;
    private final List<NetDiskEntry> entries = new ArrayList<>();

    private BottomSheetDialog dialog;
    private RecyclerView recyclerView;
    private TextView titleView;
    private TextView pathView;
    private LinearLayout actionRow;

    /** 当前浏览的账号；为 null 时显示账号列表。 */
    private NetDiskAccount browsing;
    private String currentPath = "";
    private boolean loading;

    private NetDiskDialog(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public static void show(Context context, Listener listener) {
        new NetDiskDialog(context, listener).show();
    }

    private void show() {
        dialog = new BottomSheetDialog(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = ResUtil.dp2px(16);
        root.setPadding(padding, padding, padding, ResUtil.dp2px(8));

        root.addView(buildHeader());
        recyclerView = new RecyclerView(context);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        recyclerView.setAdapter(new EntryAdapter());
        root.addView(recyclerView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ResUtil.dp2px(320)));
        actionRow = new LinearLayout(context);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.END);
        root.addView(actionRow);

        dialog.setContentView(root);
        dialog.show();
        showAccounts();
    }

    private View buildHeader() {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        titleView = new TextView(context);
        titleView.setTextSize(16);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(Color.WHITE);
        column.addView(titleView);

        pathView = new TextView(context);
        pathView.setTextSize(12);
        pathView.setTextColor(0x99FFFFFF);
        pathView.setSingleLine(true);
        pathView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        column.addView(pathView);
        return column;
    }

    // ------------------------------------------------------------------ 账号列表

    private void showAccounts() {
        browsing = null;
        currentPath = "";
        entries.clear();
        for (NetDiskAccount account : NetDiskCredentialStore.get().getAccounts()) {
            entries.add(new NetDiskEntry(account.getDisplayName(), account.getId(), true, 0, 0));
        }
        titleView.setText(R.string.net_disk_title);
        pathView.setText(context.getString(R.string.net_disk_account_count, entries.size()));
        reloadActions();
        notifyList();
    }

    private void reloadActions() {
        actionRow.removeAllViews();
        if (browsing == null) {
            actionRow.addView(action(R.string.net_disk_add, view -> showEditForm(null)));
        } else {
            actionRow.addView(action(R.string.net_disk_up, view -> {
                if (NetDiskCodec.isRoot(currentPath)) showAccounts();
                else load(currentPath.contains(String.valueOf(browsing.getType().separator()))
                        ? NetDiskCodec.parent(currentPath, browsing.getType().separator()) : "");
            }));
        }
    }

    private TextView action(int textRes, View.OnClickListener click) {
        TextView view = new TextView(context);
        view.setText(textRes);
        view.setTextSize(14);
        view.setTextColor(0xCCFFFFFF);
        view.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(8), ResUtil.dp2px(12), ResUtil.dp2px(8));
        view.setOnClickListener(click);
        return view;
    }

    // ------------------------------------------------------------------ 目录浏览

    private void load(String path) {
        if (browsing == null || loading) return;
        currentPath = path == null ? "" : path;
        loading = true;
        pathView.setText(context.getString(R.string.net_disk_loading));
        Task.execute(() -> {
            try {
                List<NetDiskEntry> list = NetDiskSession.get().list(browsing, currentPath);
                com.fongmi.android.tv.App.post(() -> {
                    loading = false;
                    entries.clear();
                    entries.addAll(list);
                    pathView.setText(browsing.getDisplayName() + " / " + currentPath);
                    titleView.setText(R.string.net_disk_browse);
                    reloadActions();
                    notifyList();
                });
            } catch (Exception e) {
                com.fongmi.android.tv.App.post(() -> {
                    loading = false;
                    // 列目录失败要顺手把连接丢掉，否则下次请求会复用坏会话，
                    // 用户会看到「改完密码还是连不上」。
                    NetDiskSession.get().invalidate(browsing.getId());
                    pathView.setText("");
                    Notify.show(context.getString(R.string.net_disk_connect_failed, describe(e)));
                });
            }
        });
    }

    private void notifyList() {
        if (recyclerView != null && recyclerView.getAdapter() != null) recyclerView.getAdapter().notifyDataSetChanged();
    }

    // ------------------------------------------------------------------ 账号表单

    private void showEditForm(NetDiskAccount existing) {
        boolean editing = existing != null;
        NetDiskAccount draft = editing ? existing : NetDiskAccount.create(NetDiskType.SMB);
        if (editing) draft.setPassword(NetDiskCredentialStore.getPassword(draft.getId()));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = ResUtil.dp2px(20);
        column.setPadding(padding, padding, padding, 0);

        // 先建好共享名这一行，类型切换的回调要用它来决定显示与否。
        LinearLayout shareRow = new LinearLayout(context);
        shareRow.setOrientation(LinearLayout.VERTICAL);

        EditText name = field(column, R.string.net_disk_field_name, draft.getName(), InputType.TYPE_CLASS_TEXT);
        // 主机与端口要在类型行之前建好：类型切换的回调要按新协议修正端口。
        EditText host = field(column, R.string.net_disk_field_host, draft.getHost(), InputType.TYPE_CLASS_TEXT);
        EditText port = field(column, R.string.net_disk_field_port, draft.getPort() > 0 ? String.valueOf(draft.getPort()) : "", InputType.TYPE_CLASS_NUMBER);
        // lambda 里要改这个值，只能用数组当容器（局部变量必须 effectively final）。
        int[] lastPort = {draft.getPort() > 0 ? draft.getPort() : draft.getType().defaultPort(false)};
        TextView type = fieldText(column, R.string.net_disk_field_type, typeLabel(draft.getType()));
        type.setOnClickListener(view -> new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.net_disk_field_type)
                .setItems(typeLabels(), (d, which) -> {
                    NetDiskType picked = pickableTypes()[which];
                    draft.setType(picked);
                    type.setText(typeLabel(picked));
                    // 三种协议默认端口不同，切换类型后把没被手改过的端口跟着换掉，
                    // 否则用户从 SMB 切到 FTP 会留着 445，连上去必然超时。
                    int next = picked.defaultPort(picked == NetDiskType.WEBDAV && draft.isSecure());
                    if (isDefaultOrEmpty(port.getText().toString(), lastPort[0])) {
                        port.setText(String.valueOf(next));
                    }
                    lastPort[0] = next;
                    // SMB 才有「共享名」；WebDAV 与 FTP 的路径已包含在地址里。
                    shareRow.setVisibility(picked == NetDiskType.SMB ? View.VISIBLE : View.GONE);
                })
                .show());
        column.addView(shareRow);
        EditText share = field(shareRow, R.string.net_disk_field_share, draft.getShare(), InputType.TYPE_CLASS_TEXT);
        EditText root = field(column, R.string.net_disk_field_root, draft.getRoot(), InputType.TYPE_CLASS_TEXT);
        EditText user = field(column, R.string.net_disk_field_user, draft.getUsername(), InputType.TYPE_CLASS_TEXT);
        EditText password = field(column, R.string.net_disk_field_password, draft.getPassword(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (draft.getType() != NetDiskType.SMB) shareRow.setVisibility(View.GONE);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(column);

        androidx.appcompat.app.AlertDialog built = new MaterialAlertDialogBuilder(context)
                .setTitle(editing ? R.string.net_disk_edit : R.string.net_disk_add)
                .setView(scroll)
                .setNeutralButton(R.string.net_disk_test, null)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    apply(draft, name, host, port, share, root, user, password);
                    if (!draft.isUsable()) {
                        Notify.show(R.string.net_disk_incomplete);
                        return;
                    }
                    NetDiskCredentialStore.get().put(draft);
                    NetDiskCredentialStore.putPassword(draft.getId(), draft.getPassword());
                    NetDiskCredentialStore.save();
                    NetDiskSession.get().invalidate(draft.getId());
                    showAccounts();
                })
                .create();
        built.show();
        // neutral 按钮默认会关掉弹窗，这里改成只测连接、保留用户已填的内容。
        wireTestButton(built, draft, host, port, share, root, user, password);
    }

    /** 覆盖 neutral 按钮的默认关闭行为，改成「测一下再关」。 */
    private void wireTestButton(androidx.appcompat.app.AlertDialog dialog, NetDiskAccount draft,
                                EditText host, EditText port, EditText share, EditText root,
                                EditText user, EditText password) {
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            apply(draft, null, host, port, share, root, user, password);
            if (!draft.isUsable()) {
                Notify.show(R.string.net_disk_incomplete);
                return;
            }
            NetDiskCredentialStore.putPassword(draft.getId(), draft.getPassword());
            Task.execute(() -> {
                String message;
                try {
                    // 列一次目录比单纯建连更能反映「真的能用」：建连成功但路径/共享
                    // 写错时，只有列举会暴露出来。
                    NetDiskSession.get().list(draft, draft.getRoot());
                    message = context.getString(R.string.net_disk_test_ok);
                } catch (Exception e) {
                    message = context.getString(R.string.net_disk_connect_failed, describe(e));
                }
                String text = message;
                com.fongmi.android.tv.App.post(() -> Notify.show(text));
            });
        });
    }

    private static void apply(NetDiskAccount account, EditText name, EditText host, EditText port,
                              EditText share, EditText root, EditText user, EditText password) {
        if (name != null) account.setName(name.getText().toString());
        account.setHost(host.getText().toString());
        String portText = port.getText().toString().trim();
        account.setPort(portText.isEmpty() ? 0 : safePort(portText));
        account.setShare(share.getText().toString());
        account.setRoot(root.getText().toString());
        account.setUsername(user.getText().toString());
        account.setPassword(password.getText().toString());
    }

    private static int safePort(String text) {
        try {
            int value = Integer.parseInt(text);
            return value > 0 && value <= 65535 ? value : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String typeLabel(NetDiskType type) {
        if (type == NetDiskType.WEBDAV) return "WebDAV";
        if (type == NetDiskType.FTP) return "FTP";
        return "SMB";
    }

    /** 表单里可选的协议，顺序也是下拉列表的顺序。 */
    private static NetDiskType[] pickableTypes() {
        return new NetDiskType[]{NetDiskType.SMB, NetDiskType.WEBDAV, NetDiskType.FTP};
    }

    /** 与 {@link #pickableTypes()} 一一对应的显示名。 */
    private static CharSequence[] typeLabels() {
        NetDiskType[] types = pickableTypes();
        CharSequence[] labels = new CharSequence[types.length];
        for (int i = 0; i < types.length; i++) labels[i] = typeLabel(types[i]);
        return labels;
    }

    /**
     * 判断端口输入框里是否还是「上一个协议的默认端口」。
     *
     * <p>只有用户没自己填过端口时才跟着类型换：否则会把用户手填的端口改掉，
     * 那是明确的用户意图，不应被覆盖。
     */
    private static boolean isDefaultOrEmpty(String current, int previous) {
        if (current == null || current.trim().isEmpty()) return true;
        try {
            return Integer.parseInt(current.trim()) == previous;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 只读的类型行：不用 EditText，避免弹出软键盘挡住所填内容。 */
    private TextView fieldText(ViewGroup parent, int labelRes, String value) {
        TextView label = new TextView(context);
        label.setText(labelRes);
        label.setTextSize(12);
        label.setTextColor(0x99FFFFFF);
        label.setPadding(0, ResUtil.dp2px(10), 0, ResUtil.dp2px(2));
        parent.addView(label);

        TextView input = new TextView(context);
        input.setText(value == null ? "" : value);
        input.setTextSize(14);
        input.setTextColor(Color.WHITE);
        input.setSingleLine(true);
        parent.addView(input);
        return input;
    }

    private EditText field(ViewGroup parent, int labelRes, String value, int inputType) {
        TextView label = new TextView(context);
        label.setText(labelRes);
        label.setTextSize(12);
        label.setTextColor(0x99FFFFFF);
        label.setPadding(0, ResUtil.dp2px(10), 0, ResUtil.dp2px(2));
        parent.addView(label);

        EditText input = new EditText(context);
        input.setText(value == null ? "" : value);
        input.setTextSize(14);
        input.setTextColor(Color.WHITE);
        input.setInputType(inputType);
        input.setSingleLine(true);
        parent.addView(input);
        return input;
    }

    private static String describe(Throwable error) {
        if (error == null) return "";
        String message = error.getMessage();
        if (message == null || message.isEmpty()) return error.getClass().getSimpleName();
        return message.length() > 120 ? message.substring(0, 120) : message;
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
            row.setBackgroundColor(0x11FFFFFF);

            TextView label = new TextView(context);
            label.setTextSize(14);
            label.setTextColor(Color.WHITE);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            TextView size = new TextView(context);
            size.setTextSize(11);
            size.setTextColor(0x88FFFFFF);
            row.addView(size);
            return new Holder(row, label, size);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            NetDiskEntry entry = entries.get(position);
            holder.label.setText(entry.isDirectory() ? "\uD83D\uDCC1 " + entry.getName() : entry.getName());
            if (browsing == null) {
                NetDiskAccount account = NetDiskCredentialStore.get().get(entry.getPath());
                holder.size.setText(account == null ? "" : account.getType().id().toUpperCase(java.util.Locale.ROOT));
            } else {
                holder.size.setText(entry.isDirectory() ? "" : formatSize(entry.getSize()));
            }
            holder.row.setOnClickListener(view -> onEntry(entry));
            holder.row.setOnLongClickListener(view -> {
                if (browsing == null) showAccountMenu(entry.getPath());
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

    private void onEntry(NetDiskEntry entry) {
        if (browsing == null) {
            NetDiskAccount account = NetDiskCredentialStore.get().get(entry.getPath());
            if (account == null) return;
            browsing = account;
            load(account.getRoot());
            return;
        }
        if (entry.isDirectory()) {
            load(entry.getPath());
            return;
        }
        if (!entry.isPlayable()) {
            Notify.show(R.string.net_disk_not_playable);
            return;
        }
        String url = NetDiskUrl.toProxyUrl(Server.get().getAddress(true), browsing.getId(), entry.getPath());
        if (url.isEmpty()) {
            Notify.show(R.string.net_disk_incomplete);
            return;
        }
        dismiss();
        listener.onPlay(url, entry.getName());
    }

    private void showAccountMenu(String accountId) {
        new MaterialAlertDialogBuilder(context)
                .setItems(new CharSequence[]{context.getString(R.string.net_disk_edit), context.getString(R.string.net_disk_delete)}, (d, which) -> {
                    if (which == 0) showEditForm(NetDiskCredentialStore.get().get(accountId));
                    else confirmDelete(accountId);
                })
                .show();
    }

    private void confirmDelete(String accountId) {
        new MaterialAlertDialogBuilder(context)
                .setMessage(R.string.net_disk_delete_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    NetDiskCredentialStore.get().remove(accountId);
                    NetDiskCredentialStore.removePassword(accountId);
                    NetDiskCredentialStore.save();
                    NetDiskSession.get().invalidate(accountId);
                    showAccounts();
                })
                .show();
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return (bytes / 1024 / 1024) + " MB";
        return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1024.0 / 1024 / 1024);
    }

    private void dismiss() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }
}
