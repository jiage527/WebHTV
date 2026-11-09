package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.player.mpv.MpvQualityEnhanceOption;
import com.github.catvod.utils.Prefers;

/**
 * 画质增强（超分 / 锐化 / 降噪）设置的持久化与文案。
 *
 * <p>无 DB 迁移：只用 {@link Prefers} 存整数档位，新增设置不影响既有数据。
 *
 * <p>默认全部<b>关闭</b>。这类渲染相关的选项对电视盒子的 GPU 负载影响很大，
 * 静默开启会让老设备「升级后突然变卡」；默认关、由用户显式开启才符合可预期原则。
 */
public final class MpvQualityEnhanceSetting {

    private static final String KEY_MODE = "perf_mpv_quality_enhance";
    private static final String KEY_DENOISE = "perf_mpv_quality_denoise";

    private MpvQualityEnhanceSetting() {
    }

    public static int getMode() {
        return MpvQualityEnhanceOption.clampMode(Prefers.getInt(KEY_MODE, MpvQualityEnhanceOption.MODE_OFF));
    }

    public static void putMode(int value) {
        Prefers.put(KEY_MODE, MpvQualityEnhanceOption.clampMode(value));
    }

    public static int getDenoise() {
        return MpvQualityEnhanceOption.clampDenoise(Prefers.getInt(KEY_DENOISE, MpvQualityEnhanceOption.DENOISE_OFF));
    }

    public static void putDenoise(int value) {
        Prefers.put(KEY_DENOISE, MpvQualityEnhanceOption.clampDenoise(value));
    }

    /**
     * 档位名。关闭时明确写「关闭」，避免用户看到空白以为没加载出来。
     *
     * <p>工单要求「TV 端算力有限需给性能提示」。这里在强化档后面直接附上「高负载」：
     * 提示紧贴在选择的那一行上，比事后弹窗更直接，也不会出现「轻度（高负载）」
     * 这种把别的开关的代价算到本行的错位。
     */
    public static String getModeText() {
        return switch (getMode()) {
            case MpvQualityEnhanceOption.MODE_LIGHT -> "轻度";
            case MpvQualityEnhanceOption.MODE_STRONG -> "强化（高负载）";
            default -> "关闭";
        };
    }

    public static String getDenoiseText() {
        return switch (getDenoise()) {
            case MpvQualityEnhanceOption.DENOISE_LIGHT -> "去色带";
            case MpvQualityEnhanceOption.DENOISE_STRONG -> "去色带+高精度（高负载）";
            default -> "关闭";
        };
    }
}
