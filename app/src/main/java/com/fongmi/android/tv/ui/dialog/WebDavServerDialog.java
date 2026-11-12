package com.fongmi.android.tv.ui.dialog;

import android.content.Context;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.webdav.WebDavSetting;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 「共享文件（WebDAV）」设置对话框。
 *
 * <p><b>为什么是三选一而不是一个开关。</b>这里其实是两个独立意图：
 * 「把文件开放出去」与「允许对方改我的文件」。用一个开关表达不了，用一个开关
 * 加一个二级确认也容易让人误以为「开启 = 全部放开」。三选一把状态平铺出来，
 * 选哪个就是哪个。
 *
 * <p><b>写入默认可选但不预选。</b>多数人只想要「让对方来播我这儿的片子」，这只需只读；
 * 写入意味着对方能上传、覆盖与删除。默认替用户选上写入，等于做了一个他不知情的决定。
 *
 * <p><b>为什么从多选改成单选。</b>原先用 {@code setMultiChoiceItems}，两个选项默认都不勾，
 * 而「只读」那个勾同时充当主开关——用户第一次点开、什么都没勾就点确定，程序判定为
 * 「关闭共享」，于是弹出一句「已关闭文件共享」。用户的操作意图明明是「我要打开它」，
 * 得到的却是一个否定结果，而且对话框正文（只说开启后能干什么）从没告诉他要先勾选。
 * 用户实测反馈的正是这个现象。单选项没有「都不选」这个中立状态，点确定必然对应一个
 * 明确的状态，歧义消失了。
 */
public final class WebDavServerDialog {

    /** 与单选项列表的下标一一对应。 */
    private static final int OFF = 0;
    private static final int READ_ONLY = 1;
    private static final int WRITABLE = 2;

    private WebDavServerDialog() {
    }

    public static void show(Context context, Runnable onChanged) {
        boolean previousWritable = WebDavSetting.isWritable();
        int[] picked = {current()};
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.setting_webdav_server)
                .setMessage(R.string.setting_webdav_server_summary)
                .setSingleChoiceItems(new CharSequence[]{
                        context.getString(R.string.setting_webdav_server_off),
                        context.getString(R.string.setting_webdav_server_readonly),
                        context.getString(R.string.setting_webdav_server_writable)
                }, picked[0], (dialog, which) -> picked[0] = which)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    apply(context, picked[0], previousWritable);
                    if (onChanged != null) onChanged.run();
                })
                .show();
    }

    /** 当前状态对应的单选项下标。 */
    private static int current() {
        if (!WebDavSetting.isEnabled()) return OFF;
        return WebDavSetting.isWritable() ? WRITABLE : READ_ONLY;
    }

    /**
     * 落盘并提示结果。
     *
     * <p>三选一里「仅可读」与「可读写」都表示开启服务，区别只在写入授权，
     * 所以判断的是「选中项是否为关闭」，而不是「是否勾了某个框」。
     */
    private static void apply(Context context, int picked, boolean previousWritable) {
        if (picked == OFF) {
            WebDavSetting.putEnabled(false);
            Notify.show(R.string.setting_webdav_server_off_toast);
            return;
        }
        if (!Setting.hasFileAccess()) {
            // 没有存储权限时共享根建不出来，开了也是个空目录——明确告诉用户原因，
            // 而不是提示「已开启」让他去别的设备上白找一趟。
            Notify.show(R.string.setting_webdav_server_permission);
            return;
        }
        boolean writable = picked == WRITABLE;
        boolean writeJustEnabled = writable && !previousWritable;
        // 必须先落写入再落开启：putEnabled(false) 会顺带收回写入授权，顺序反了会把
        // 刚给的可写权限立刻清掉（表现为「选了可读写却还是只读」）。
        WebDavSetting.putWritable(writable);
        WebDavSetting.putEnabled(true);
        if (writeJustEnabled) {
            // 刚开了写入是风险最高的那次变更，单独确认一次。
            Notify.show(R.string.setting_webdav_server_writable_on);
        } else if (previousWritable && !writable) {
            // 从可读写改回仅可读，也要说一声：用户可能正以为对方还能传文件。
            Notify.show(R.string.setting_webdav_server_writable_off);
        } else {
            // 其余情况（新开启、或只读状态下反复确认）把挂载地址再给一次——
            // 用户接下来就要把这串地址敲进别的设备，只有提示没有地址等于没说。
            Notify.show(context.getString(R.string.setting_webdav_server_address, address()));
        }
    }

    /**
     * 本机对局域网提供的挂载地址。
     *
     * <p>必须用 {@code Server.getAddress(false)}（局域网地址）而不是回环地址：
     * 用户要把这串地址敲进别的设备里，回环地址在对方机器上指向它自己。
     */
    public static String address() {
        return Server.get().getAddress(false) + "/dav";
    }
}
