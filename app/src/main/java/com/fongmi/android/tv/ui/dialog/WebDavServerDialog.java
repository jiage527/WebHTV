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
 * <p><b>为什么用多选列表而不是一个开关。</b>这里其实是两个独立意图：
 * 「把文件开放出去」与「允许对方改我的文件」。用一个开关表达不了，用一个开关
 * 加一个二级确认也容易让人误以为「开启 = 全部放开」。多选列表把两件事平铺出来，
 * 勾哪个就是哪个。
 *
 * <p><b>写入默认不勾。</b>多数人只想要「让对方来播我这儿的片子」，这只需只读；
 * 写入意味着对方能上传、覆盖与删除。默认勾上等于替用户做了一个他不知情的决定。
 */
public final class WebDavServerDialog {

    private WebDavServerDialog() {
    }

    public static void show(Context context, Runnable onChanged) {
        boolean previousWritable = WebDavSetting.isWritable();
        boolean[] checked = {WebDavSetting.isEnabled(), previousWritable};
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.setting_webdav_server)
                .setMessage(R.string.setting_webdav_server_summary)
                .setMultiChoiceItems(new CharSequence[]{
                        context.getString(R.string.setting_webdav_server_readonly),
                        context.getString(R.string.setting_webdav_server_writable)
                }, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    apply(context, checked[0], checked[1], previousWritable);
                    if (onChanged != null) onChanged.run();
                })
                .show();
    }

    /**
     * 落盘并提示结果。
     *
     * <p>勾选顺序上「只读」是主开关、「可读写」是叠加权限，所以：
     * 只读没勾就是关闭服务；只读勾了而可写没勾就是仅可读。
     */
    private static void apply(Context context, boolean readOnly, boolean writable, boolean previousWritable) {
        if (!readOnly) {
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
        boolean writeJustEnabled = writable && !previousWritable;
        // 必须先落写入再落开启：putEnabled(false) 会顺带收回写入授权，顺序反了会把
        // 刚给的可写权限立刻清掉（表现为「勾了可读写却还是只读」）。
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
