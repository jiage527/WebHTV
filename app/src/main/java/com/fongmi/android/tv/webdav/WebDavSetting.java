package com.fongmi.android.tv.webdav;

import com.github.catvod.utils.Prefers;

/**
 * 局域网 WebDAV 服务端的开关。
 *
 * <p><b>默认关闭，且写入默认关闭。</b>这个端点会把手机/电视上的文件开放给同一网络里的
 * 任何设备，是可被直接察觉的隐私暴露。默认开启等于替用户做了一个他不知情的决定，
 * 所以必须由用户主动打开。
 *
 * <p><b>只读与可写分成两个开关。</b>多数使用场景是「让别的设备来播我这里的片子」，
 * 只需要读。写入（上传、建目录、删除）风险高得多——对方一旦连上就能改我们的文件。
 * 因此写入必须单独再确认一次，而不是跟着只读一起放行。
 */
public final class WebDavSetting {

    private static final String KEY_ENABLED = "webdav_server_enabled";
    private static final String KEY_WRITABLE = "webdav_server_writable";

    private WebDavSetting() {
    }

    public static boolean isEnabled() {
        return Prefers.getBoolean(KEY_ENABLED, false);
    }

    public static void putEnabled(boolean enabled) {
        Prefers.put(KEY_ENABLED, enabled);
        // 关掉服务时同时收回写入授权：否则下次再打开时用户会以为还是「只读」，
        // 而写入权限其实被静默留着了。
        if (!enabled) putWritable(false);
    }

    /** 是否允许对方上传/建目录/删除。仅在服务已开启时有意义。 */
    public static boolean isWritable() {
        return isEnabled() && Prefers.getBoolean(KEY_WRITABLE, false);
    }

    public static void putWritable(boolean writable) {
        Prefers.put(KEY_WRITABLE, writable);
    }
}
