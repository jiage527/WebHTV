package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

/**
 * 进度条首帧预览的开关。
 *
 * <p>默认开启：这是纯本地行为，不需要联网也不需要额外来宾权限。但在低端设备或
 * 拖动卡顿的场景下，用户可以关掉它以避免抽帧抢占解码资源。
 */
public class SeekPreviewSetting {

    private static final String KEY_ENABLED = "seek_preview_enabled";

    private SeekPreviewSetting() {
    }

    public static boolean isEnabled() {
        return Prefers.getBoolean(KEY_ENABLED, true);
    }

    public static void putEnabled(boolean enabled) {
        Prefers.put(KEY_ENABLED, enabled);
    }
}
