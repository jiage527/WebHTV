package com.fongmi.android.tv.ui.dialog;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.ThemeColors;
import com.fongmi.android.tv.webdav.WebDavSetting;
import com.fongmi.android.tv.webdav.WebDavShareOption;
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
 * <p><b>为什么自己搭选项视图，而不是用 {@code setSingleChoiceItems}。</b>这条是本次
 * 故障的根。{@code AlertController.setupContent} 的字节码是：
 * <pre>
 *   59: getfield mMessage;  ifnull 76     // message == null 才去装那个列表
 *   62: mMessageView.setText(mMessage)
 *   73: goto 155                          // 设完正文就 return，列表根本没装
 * </pre>
 * 也就是说 {@code setMessage(...)} 与 {@code setSingleChoiceItems(...)} 同时用时，
 * 选项会被静默丢掉——对话框只剩标题、正文和两个按钮。用户点开只看到一段说明，
 * 没有可选项，于是每次「确定」都写回预选值，表现为「只能变成仅可读、点多少次都没变化」。
 * 自己用 {@code setView} 搭选项，渲染不再依赖 builder 的内部布局，正文也一并放进自建视图里，
 * 不再调用 {@code setMessage}。
 */
public final class WebDavServerDialog {

    private WebDavServerDialog() {
    }

    public static void show(Context context, Runnable onChanged) {
        boolean previousWritable = WebDavSetting.isWritable();
        WebDavShareOption[] options = WebDavShareOption.values();
        RadioGroup group = buildOptions(context, options,
                WebDavShareOption.preselect(WebDavSetting.isEnabled(), previousWritable));
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.setting_webdav_server)
                .setView(wrap(context, group))
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    apply(context, checked(group), previousWritable);
                    if (onChanged != null) onChanged.run();
                })
                .show();
    }

    /**
     * 把三个候选项搭成单选列表。
     *
     * <p>用 {@code RadioGroup} 而不是 {@code ListView}：这里没有滚动、没有复用需求，
     * 而 {@code RadioGroup} 自带「同组内只能选一个 + 显示选中态」，正是这份交互要的全部。
     */
    private static RadioGroup buildOptions(Context context, WebDavShareOption[] options, WebDavShareOption selected) {
        RadioGroup group = new RadioGroup(context);
        group.setOrientation(RadioGroup.VERTICAL);
        for (WebDavShareOption option : options) {
            RadioButton button = new RadioButton(context);
            button.setId(View.generateViewId());
            button.setText(label(context, option));
            button.setTextSize(15);
            button.setTextColor(ThemeColors.onSurface(context));
            button.setTag(option);
            button.setPadding(0, ResUtil.dp2px(6), 0, ResUtil.dp2px(6));
            group.addView(button);
            if (option == selected) group.check(button.getId());
        }
        return group;
    }

    /** 说明文字 + 选项，一起交给 {@code setView}；正文不再走 {@code setMessage}（原因见类注释）。 */
    private static View wrap(Context context, RadioGroup group) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = ResUtil.dp2px(20);
        root.setPadding(padding, ResUtil.dp2px(8), padding, ResUtil.dp2px(8));
        TextView summary = new TextView(context);
        summary.setText(R.string.setting_webdav_server_summary);
        summary.setTextSize(13);
        summary.setTextColor(ThemeColors.secondary(context));
        root.addView(summary, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ResUtil.dp2px(12);
        root.addView(group, params);
        return root;
    }

    private static CharSequence label(Context context, WebDavShareOption option) {
        switch (option) {
            case READ_ONLY:
                return context.getString(R.string.setting_webdav_server_readonly);
            case WRITABLE:
                return context.getString(R.string.setting_webdav_server_writable);
            default:
                return context.getString(R.string.setting_webdav_server_off);
        }
    }

    /**
     * 读出当前选中项。
     *
     * <p>靠按钮上挂的 {@code tag} 反查，而不是靠下标位置：下标一旦与
     * {@link WebDavShareOption} 的顺序脱钩就会静默写错状态，而 tag 是建的时候就绑死的。
     * 一个都没选中（理论上不该发生，因为构建时总会 {@code check} 一项）时按 {@link WebDavShareOption#OFF}
     * 处理——宁可当成没开，也不要凭空把服务打开。
     */
    private static WebDavShareOption checked(RadioGroup group) {
        int id = group.getCheckedRadioButtonId();
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child.getId() == id && child.getTag() instanceof WebDavShareOption) {
                return (WebDavShareOption) child.getTag();
            }
        }
        return WebDavShareOption.OFF;
    }

    /**
     * 落盘并提示结果。
     *
     * <p>三选一里「仅可读」与「可读写」都表示开启服务，区别只在写入授权，
     * 所以判断的是「选中项是否为关闭」，而不是「是否勾了某个框」。
     */
    private static void apply(Context context, WebDavShareOption picked, boolean previousWritable) {
        if (!picked.allowed()) {
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
        boolean writable = picked.writable();
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
