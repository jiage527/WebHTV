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
     * <p>工单要求「TV 端算力有限需给性能提示」。但<b>「高负载」后缀不写在这里</b>：
     * 这里只知道本行档位，看不到另一行，早期版本用「或」判断导致出现
     * 「超分关闭但降噪开了强化」时把超分行标成高负载、以及降噪行显示成
     * 「关闭（高负载）」这种矛盾文案。后缀改由对话框按<b>本行</b>档位追加
     * （见 {@link com.fongmi.android.tv.player.mpv.MpvQualityEnhanceOption#modeNeedsWarning}）。
     */
    public static String getModeText() {
        return switch (getMode()) {
            case MpvQualityEnhanceOption.MODE_LIGHT -> "效率档";
            case MpvQualityEnhanceOption.MODE_STRONG -> "质量档";
            default -> "关闭";
        };
    }

    public static String getDenoiseText() {
        return switch (getDenoise()) {
            case MpvQualityEnhanceOption.DENOISE_LIGHT -> "去色带";
            case MpvQualityEnhanceOption.DENOISE_STRONG -> "去色带+高精度";
            default -> "关闭";
        };
    }

    /**
     * 恢复默认（两个档位都关闭）。
     *
     * <p><b>为什么需要这个方法。</b>对话框的「重置」走的是
     * {@code PlaybackPerformanceSetting.applyAuto()}，而它只清 override 标记、
     * 重放内核预设，<b>不会碰这两条键</b>——所以用户把档位调到「强化」后点「重置」
     * 毫无反应，看起来像按钮坏了。画质增强的档位是用户显式选择的，
     * 不能在预设里隐式开启，但「重置」必须能把它收回去。
     */
    public static void reset() {
        putMode(MpvQualityEnhanceOption.MODE_OFF);
        putDenoise(MpvQualityEnhanceOption.DENOISE_OFF);
    }
}
