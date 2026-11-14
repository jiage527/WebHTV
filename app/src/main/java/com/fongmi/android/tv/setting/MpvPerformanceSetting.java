package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.player.mpv.MpvHlsQualityPolicy;
import com.github.catvod.utils.Prefers;

public final class MpvPerformanceSetting {

    public static final int OUTPUT_AUTO = 0;
    public static final int OUTPUT_GPU = 1;
    public static final int OUTPUT_SURFACE_DIRECT = 2;
    public static final int HWDEC_AUTO = 0;
    public static final int HWDEC_DIRECT = 1;
    public static final int HWDEC_COPY = 2;
    /** MPV 自己的默认解码方式：与播放器里那个临时按钮不同，这里选完会一直生效。 */
    public static final int DECODE_HARD = 0;
    public static final int DECODE_SOFT = 1;
    public static final int SYNC_AUDIO = 0;
    public static final int SYNC_DISPLAY_RESAMPLE = 1;
    public static final int FRAME_DROP_OUTPUT = 0;
    public static final int FRAME_DROP_OFF = 1;
    public static final int FRAME_DROP_DECODER = 2;
    public static final int SOFT_TUNE_OFF = 0;
    public static final int SOFT_TUNE_MILD = 1;
    public static final int SOFT_TUNE_AGGRESSIVE = 2;
    public static final int FRAME_RATE_OFF = 0;
    public static final int FRAME_RATE_SEAMLESS = 1;
    public static final int HLS_HIGHEST = 0;
    public static final int HLS_15_MBPS = 1;
    public static final int HLS_8_MBPS = 2;
    public static final int HLS_LOWEST = 3;
    public static final int PRIORITY_PERFORMANCE = 0;
    public static final int PRIORITY_CONFIG = 1;
    public static final int MULTICHANNEL_STEREO_COMPAT = 0;
    public static final int MULTICHANNEL_PCM = 1;
    // Kept only to migrate the temporary four-choice test build. Formal UI
    // exposes direct / legacy / stable, and old "auto" values become legacy.
    public static final int VULKAN_BACKEND_AUTO = 0;
    public static final int VULKAN_BACKEND_DIRECT = 1;
    public static final int VULKAN_BACKEND_LEGACY = 2;
    public static final int VULKAN_BACKEND_STABLE = 3;

    private static final String KEY_OUTPUT_MODE = "perf_mpv_output_mode";
    private static final String KEY_HWDEC = "perf_mpv_hwdec";
    private static final String KEY_DECODE_DEFAULT = "perf_mpv_decode_default";
    private static final String KEY_SYNC = "perf_mpv_sync";
    private static final String KEY_FRAME_DROP = "perf_mpv_frame_drop";
    private static final String KEY_INTERPOLATION = "perf_mpv_interpolation";
    private static final String KEY_SOFT_TUNE = "perf_mpv_soft_tune";
    private static final String KEY_FRAME_RATE = "perf_mpv_frame_rate";
    private static final String KEY_HLS_BITRATE = "perf_mpv_hls_bitrate";
    private static final String KEY_HLS_BITRATE_MANUAL = "perf_mpv_hls_bitrate_manual";
    private static final String KEY_REBUFFER_MS = "perf_mpv_rebuffer_ms";
    private static final String KEY_OPTION_PRIORITY = "perf_mpv_option_priority";
    private static final String KEY_VULKAN_BACKEND = "perf_mpv_vulkan_backend";
    private static final String KEY_MULTICHANNEL_AUDIO = "perf_mpv_multichannel_audio";

    private MpvPerformanceSetting() {
    }

    public static int getOutputMode() {
        return clamp(Prefers.getInt(KEY_OUTPUT_MODE, OUTPUT_AUTO), OUTPUT_AUTO, OUTPUT_SURFACE_DIRECT);
    }

    public static void putOutputMode(int value) {
        int mode = clamp(value, OUTPUT_AUTO, OUTPUT_SURFACE_DIRECT);
        Prefers.put(KEY_OUTPUT_MODE, mode);
        PlaybackPerformanceSetting.setOverride(
                PlaybackPerformanceCatalog.MPV_OUTPUT,
                mode != OUTPUT_AUTO);
    }

    public static String getOutputModeText() {
        return switch (getOutputMode()) {
            case OUTPUT_GPU -> "GPU渲染";
            case OUTPUT_SURFACE_DIRECT -> "电视直出";
            default -> "自动";
        };
    }

    public static boolean shouldUseSurfaceDirect(boolean autoEligible, boolean leanback, boolean hardDecode) {
        return resolveSurfaceDirect(getOutputMode(), autoEligible, leanback, hardDecode);
    }

    static boolean resolveSurfaceDirect(int outputMode, boolean autoEligible, boolean leanback, boolean hardDecode) {
        if (!hardDecode) return false;
        return switch (clamp(outputMode, OUTPUT_AUTO, OUTPUT_SURFACE_DIRECT)) {
            case OUTPUT_SURFACE_DIRECT -> true;
            case OUTPUT_GPU -> false;
            default -> leanback && autoEligible;
        };
    }

    public static int getHwdecMode() {
        return clamp(Prefers.getInt(KEY_HWDEC, HWDEC_AUTO), HWDEC_AUTO, HWDEC_COPY);
    }

    public static void putHwdecMode(int value) {
        int mode = clamp(value, HWDEC_AUTO, HWDEC_COPY);
        Prefers.put(KEY_HWDEC, mode);
        PlaybackPerformanceSetting.setOverride(
                PlaybackPerformanceCatalog.MPV_HWDEC,
                mode != HWDEC_AUTO);
    }

    public static String getHwdecOption() {
        return switch (getHwdecMode()) {
            case HWDEC_DIRECT -> "mediacodec";
            case HWDEC_COPY -> "mediacodec-copy";
            default -> "mediacodec,mediacodec-copy";
        };
    }

    public static String getHwdecText() {
        return switch (getHwdecMode()) {
            case HWDEC_DIRECT -> "零拷贝优先";
            case HWDEC_COPY -> "兼容复制";
            default -> "自动回退";
        };
    }

    /**
     * MPV 的默认解码方式。
     *
     * <p>播放器里那个「解码」按钮只在本次播放生效（{@code PlayerManager.switchDecode}），
     * 退出重进又回到硬解。这里选的是每次起播时用的默认值，跟着设置一直保存。
     */
    public static int getDecodeDefault() {
        return clamp(Prefers.getInt(KEY_DECODE_DEFAULT, DECODE_HARD), DECODE_HARD, DECODE_SOFT);
    }

    public static void putDecodeDefault(int value) {
        int mode = clamp(value, DECODE_HARD, DECODE_SOFT);
        Prefers.put(KEY_DECODE_DEFAULT, mode);
        // 选“硬解”等于回到出厂默认，不该记为“用户改过”。
        PlaybackPerformanceSetting.setOverride(
                PlaybackPerformanceCatalog.MPV_DECODE,
                mode != DECODE_HARD);
    }

    public static String getDecodeDefaultText() {
        return getDecodeDefault() == DECODE_SOFT ? "软解" : "硬解";
    }

    /**
     * 起播时该用哪种解码。
     *
     * <p>只有 MPV 内核吃这个偏好；EXO / IJK 仍旧按调用方给的硬解起播，
     * 免得一个内核的设置影响到别的内核。
     */
    public static int initialDecode(int kernel, int hard, int soft) {
        if (kernel != PlayerSetting.MPV) return hard;
        return getDecodeDefault() == DECODE_SOFT ? soft : hard;
    }

    public static int getSyncMode() {
        return clamp(Prefers.getInt(KEY_SYNC, SYNC_AUDIO), SYNC_AUDIO, SYNC_DISPLAY_RESAMPLE);
    }

    public static void putSyncMode(int value) {
        Prefers.put(KEY_SYNC, clamp(value, SYNC_AUDIO, SYNC_DISPLAY_RESAMPLE));
        PlaybackPerformanceSetting.markOverride(PlaybackPerformanceCatalog.MPV_SYNC);
    }

    public static String getSyncOption() {
        return getSyncMode() == SYNC_DISPLAY_RESAMPLE ? "display-resample" : "audio";
    }

    public static String getSyncText() {
        return getSyncMode() == SYNC_DISPLAY_RESAMPLE ? "显示重采样" : "音频同步";
    }

    public static int getFrameDropMode() {
        return clamp(Prefers.getInt(KEY_FRAME_DROP, FRAME_DROP_OUTPUT), FRAME_DROP_OUTPUT, FRAME_DROP_DECODER);
    }

    public static void putFrameDropMode(int value) {
        Prefers.put(KEY_FRAME_DROP, clamp(value, FRAME_DROP_OUTPUT, FRAME_DROP_DECODER));
        PlaybackPerformanceSetting.markOverride(PlaybackPerformanceCatalog.MPV_FRAME_DROP);
    }

    public static String getFrameDropOption() {
        return switch (getFrameDropMode()) {
            case FRAME_DROP_OFF -> "no";
            case FRAME_DROP_DECODER -> "decoder";
            default -> "vo";
        };
    }

    public static String getFrameDropText() {
        return switch (getFrameDropMode()) {
            case FRAME_DROP_OFF -> "关闭";
            case FRAME_DROP_DECODER -> "解码丢帧";
            default -> "输出丢帧";
        };
    }

    public static boolean isInterpolation() {
        return Prefers.getBoolean(KEY_INTERPOLATION);
    }

    public static void putInterpolation(boolean value) {
        Prefers.put(KEY_INTERPOLATION, value);
        PlaybackPerformanceSetting.markOverride(PlaybackPerformanceCatalog.MPV_INTERPOLATION);
    }

    public static int getSoftTuneMode() {
        return clamp(Prefers.getInt(KEY_SOFT_TUNE, SOFT_TUNE_MILD), SOFT_TUNE_OFF, SOFT_TUNE_AGGRESSIVE);
    }

    public static void putSoftTuneMode(int value) {
        Prefers.put(KEY_SOFT_TUNE, clamp(value, SOFT_TUNE_OFF, SOFT_TUNE_AGGRESSIVE));
        PlaybackPerformanceSetting.markOverride(PlaybackPerformanceCatalog.MPV_SOFT_TUNE);
    }

    public static String getSoftTuneText() {
        return switch (getSoftTuneMode()) {
            case SOFT_TUNE_OFF -> "关闭";
            case SOFT_TUNE_AGGRESSIVE -> "积极";
            default -> "温和";
        };
    }

    public static int getFrameRateMode() {
        return clamp(Prefers.getInt(KEY_FRAME_RATE, FRAME_RATE_SEAMLESS), FRAME_RATE_OFF, FRAME_RATE_SEAMLESS);
    }

    public static void putFrameRateMode(int value) {
        Prefers.put(KEY_FRAME_RATE, clamp(value, FRAME_RATE_OFF, FRAME_RATE_SEAMLESS));
        PlaybackPerformanceSetting.markOverride(PlaybackPerformanceCatalog.MPV_FRAME_RATE);
    }

    public static String getFrameRateText() {
        return getFrameRateMode() == FRAME_RATE_OFF ? "关闭" : "仅无缝";
    }

    public static int getHlsBitrateMode() {
        return clamp(Prefers.getInt(KEY_HLS_BITRATE, HLS_HIGHEST), HLS_HIGHEST, HLS_LOWEST);
    }

    public static void putHlsBitrateMode(int value) {
        Prefers.put(KEY_HLS_BITRATE, clamp(value, HLS_HIGHEST, HLS_LOWEST));
        // Picking a preset replaces any per-stream choice, so the settings menu cannot
        // show a value that the running player would ignore.
        clearHlsBitrateManual();
        PlaybackPerformanceSetting.markOverride(PlaybackPerformanceCatalog.MPV_HLS_BITRATE);
    }

    public static String getHlsBitrateOption() {
        // A stream-specific choice wins over the preset: it was made while looking at
        // that stream's real ladder, so it is the more informed of the two.
        String manual = getHlsBitrateManual();
        if (!manual.isEmpty()) return manual;
        return switch (getHlsBitrateMode()) {
            case HLS_15_MBPS -> "15000000";
            case HLS_8_MBPS -> "8000000";
            case HLS_LOWEST -> "min";
            default -> "max";
        };
    }

    public static String getHlsBitrateText() {
        return getHlsBitrateText(
                PlaybackPerformanceSetting.isAuto(
                        PlayerSetting.MPV,
                        PlaybackPerformanceCatalog.MPV_HLS_BITRATE));
    }

    public static String getHlsBitrateText(boolean automatic) {
        if (automatic) return "自动 · ≤15Mbps起步";
        String manual = getHlsBitrateManual();
        if (!manual.isEmpty()) return describeHlsBitrateOption(manual);
        return switch (getHlsBitrateMode()) {
            case HLS_15_MBPS -> "不超过15Mbps";
            case HLS_8_MBPS -> "不超过8Mbps";
            case HLS_LOWEST -> "最低码率";
            default -> "最高码率";
        };
    }

    /**
     * A quality chosen for the stream currently playing, overriding the coarse
     * preset. Held as the mpv option value itself (a number, or {@code min}/{@code max})
     * so the stored value is exactly what mpv was given and can be compared with the
     * live option without a second representation.
     */
    public static String getHlsBitrateManual() {
        String value = Prefers.getString(KEY_HLS_BITRATE_MANUAL, "").trim();
        return isValidHlsBitrateOption(value) ? value : "";
    }

    public static void putHlsBitrateManual(String value) {
        String option = value == null ? "" : value.trim();
        if (option.isEmpty()) {
            Prefers.remove(KEY_HLS_BITRATE_MANUAL);
            return;
        }
        if (!isValidHlsBitrateOption(option)) return;
        Prefers.put(KEY_HLS_BITRATE_MANUAL, option);
        // A hand-picked quality is a manual override in the same sense as the preset
        // menu, so it must also retire the automatic path for this option.
        PlaybackPerformanceSetting.markOverride(PlaybackPerformanceCatalog.MPV_HLS_BITRATE);
    }

    public static void clearHlsBitrateManual() {
        Prefers.remove(KEY_HLS_BITRATE_MANUAL);
    }

    public static boolean hasHlsBitrateManual() {
        return !getHlsBitrateManual().isEmpty();
    }

    /**
     * True for the values mpv accepts for {@code hls-bitrate}. Delegates to the policy so
     * the settings layer, the menu and the native staging check cannot drift apart.
     */
    public static boolean isValidHlsBitrateOption(String value) {
        return MpvHlsQualityPolicy.isValidOption(value);
    }

    /** A readable description of a stored option value for the settings row. */
    public static String describeHlsBitrateOption(String option) {
        String value = option == null ? "" : option.trim();
        if (MpvHlsQualityPolicy.OPTION_LOWEST.equals(value)) return "最低码率";
        if (MpvHlsQualityPolicy.OPTION_HIGHEST.equals(value)) return "最高码率";
        long bits = 0;
        try {
            bits = Long.parseLong(value);
        } catch (NumberFormatException ignored) {
        }
        if (bits <= 0) return "最高码率";
        return "手动 · " + MpvHlsQualityPolicy.formatBitsPerSecond(bits);
    }

    public static int getRebufferMs() {
        PlaybackPerformanceSetting.ensureInitialized();
        return normalizeRebuffer(Prefers.getInt(KEY_REBUFFER_MS, rebufferForPreset(PlaybackPerformanceSetting.PROFILE_RECOMMENDED)));
    }

    public static void putRebufferMs(int value) {
        Prefers.put(KEY_REBUFFER_MS, normalizeRebuffer(value));
        PlaybackPerformanceSetting.markOverride(PlaybackPerformanceCatalog.MPV_REBUFFER);
    }

    public static int nextRebufferMs() {
        return switch (getRebufferMs()) {
            case 1_000 -> 2_000;
            case 2_000 -> 3_000;
            case 3_000 -> 5_000;
            case 5_000 -> 8_000;
            case 8_000 -> 10_000;
            case 10_000 -> 15_000;
            case 15_000 -> 1_000;
            default -> 1_000;
        };
    }

    public static int getOptionPriority() {
        return clamp(Prefers.getInt(KEY_OPTION_PRIORITY, PRIORITY_PERFORMANCE), PRIORITY_PERFORMANCE, PRIORITY_CONFIG);
    }

    public static void putOptionPriority(int value) {
        Prefers.put(KEY_OPTION_PRIORITY, clamp(value, PRIORITY_PERFORMANCE, PRIORITY_CONFIG));
        PlaybackPerformanceSetting.markOverride(PlaybackPerformanceCatalog.MPV_OPTION_PRIORITY);
    }

    public static boolean isPerformancePriority() {
        return getOptionPriority() == PRIORITY_PERFORMANCE;
    }

    public static String getOptionPriorityText() {
        return isPerformancePriority() ? "播放性能优先" : "mpv.conf优先";
    }

    public static int getMultichannelAudioMode() {
        return clamp(Prefers.getInt(KEY_MULTICHANNEL_AUDIO,
                MULTICHANNEL_STEREO_COMPAT),
                MULTICHANNEL_STEREO_COMPAT, MULTICHANNEL_PCM);
    }

    public static void putMultichannelAudioMode(int value) {
        Prefers.put(KEY_MULTICHANNEL_AUDIO, clamp(value,
                MULTICHANNEL_STEREO_COMPAT, MULTICHANNEL_PCM));
        PlaybackPerformanceSetting.markOverride(
                PlaybackPerformanceCatalog.MPV_MULTICHANNEL_AUDIO);
    }

    public static boolean isMultichannelPcm() {
        return getMultichannelAudioMode() == MULTICHANNEL_PCM;
    }

    public static String getMultichannelAudioText() {
        return isMultichannelPcm() ? "多声道 PCM" : "立体声兼容";
    }

    public static int getVulkanBackend() {
        return normalizeVulkanBackend(Prefers.getInt(
                KEY_VULKAN_BACKEND, VULKAN_BACKEND_DIRECT));
    }

    public static void putVulkanBackend(int value) {
        Prefers.put(KEY_VULKAN_BACKEND, normalizeVulkanBackend(value));
        PlaybackPerformanceSetting.markOverride(PlaybackPerformanceCatalog.MPV_VULKAN_BACKEND);
    }

    public static int nextVulkanBackend() {
        return switch (getVulkanBackend()) {
            case VULKAN_BACKEND_DIRECT -> VULKAN_BACKEND_LEGACY;
            case VULKAN_BACKEND_LEGACY -> VULKAN_BACKEND_STABLE;
            default -> VULKAN_BACKEND_DIRECT;
        };
    }

    public static String getVulkanBackendOption() {
        return vulkanBackendOption(getVulkanBackend());
    }

    static String vulkanBackendOption(int value) {
        return switch (normalizeVulkanBackend(value)) {
            case VULKAN_BACKEND_DIRECT -> "direct";
            case VULKAN_BACKEND_STABLE -> "stable";
            default -> "legacy";
        };
    }

    public static String getVulkanBackendText() {
        return getVulkanBackendOption();
    }

    private static int normalizeVulkanBackend(int value) {
        return switch (value) {
            case VULKAN_BACKEND_DIRECT, VULKAN_BACKEND_STABLE -> value;
            default -> VULKAN_BACKEND_LEGACY;
        };
    }

    public static void applyRecommended() {
        PlayerSetting.putMpvRender(PlayerSetting.MPV_RENDER_OPENGL);
        Prefers.put(KEY_OUTPUT_MODE, OUTPUT_AUTO);
        Prefers.put(KEY_HWDEC, HWDEC_AUTO);
        Prefers.put(KEY_SYNC, SYNC_AUDIO);
        Prefers.put(KEY_FRAME_DROP, FRAME_DROP_OUTPUT);
        Prefers.put(KEY_INTERPOLATION, false);
        Prefers.put(KEY_SOFT_TUNE, SOFT_TUNE_MILD);
        Prefers.put(KEY_FRAME_RATE, FRAME_RATE_SEAMLESS);
        Prefers.put(KEY_HLS_BITRATE, HLS_HIGHEST);
        // A profile switch replaces the coarse preset, so any per-stream choice made
        // against the previous settings must go too; otherwise the new profile would
        // appear not to apply.
        clearHlsBitrateManual();
        Prefers.put(KEY_VULKAN_BACKEND, VULKAN_BACKEND_DIRECT);
        Prefers.put(KEY_MULTICHANNEL_AUDIO, MULTICHANNEL_STEREO_COMPAT);
        applyRebufferPreset(PlaybackPerformanceSetting.PROFILE_RECOMMENDED);
    }

    public static void applyAuto() {
        PlayerSetting.putMpvRender(PlayerSetting.MPV_RENDER_OPENGL);
        Prefers.put(KEY_OUTPUT_MODE, OUTPUT_AUTO);
        Prefers.put(KEY_HWDEC, HWDEC_AUTO);
        Prefers.put(KEY_SYNC, SYNC_AUDIO);
        Prefers.put(KEY_FRAME_DROP, FRAME_DROP_OUTPUT);
        Prefers.put(KEY_INTERPOLATION, false);
        Prefers.put(KEY_SOFT_TUNE, SOFT_TUNE_MILD);
        Prefers.put(KEY_FRAME_RATE, FRAME_RATE_SEAMLESS);
        Prefers.put(KEY_HLS_BITRATE, HLS_HIGHEST);
        // A profile switch replaces the coarse preset, so any per-stream choice made
        // against the previous settings must go too; otherwise the new profile would
        // appear not to apply.
        clearHlsBitrateManual();
        Prefers.put(KEY_VULKAN_BACKEND, VULKAN_BACKEND_DIRECT);
        Prefers.put(KEY_MULTICHANNEL_AUDIO, MULTICHANNEL_STEREO_COMPAT);
        applyRebufferPreset(PlaybackPerformanceSetting.PROFILE_AUTO);
    }

    public static void applyCompatible() {
        applyLightweight();
    }

    public static void applyLightweight() {
        PlayerSetting.putMpvRender(PlayerSetting.MPV_RENDER_OPENGL);
        Prefers.put(KEY_OUTPUT_MODE, OUTPUT_AUTO);
        Prefers.put(KEY_HWDEC, HWDEC_AUTO);
        Prefers.put(KEY_SYNC, SYNC_AUDIO);
        Prefers.put(KEY_FRAME_DROP, FRAME_DROP_OUTPUT);
        Prefers.put(KEY_INTERPOLATION, false);
        Prefers.put(KEY_SOFT_TUNE, SOFT_TUNE_MILD);
        Prefers.put(KEY_FRAME_RATE, FRAME_RATE_OFF);
        Prefers.put(KEY_HLS_BITRATE, HLS_8_MBPS);
        clearHlsBitrateManual();
        Prefers.put(KEY_VULKAN_BACKEND, VULKAN_BACKEND_DIRECT);
        Prefers.put(KEY_MULTICHANNEL_AUDIO, MULTICHANNEL_STEREO_COMPAT);
        applyRebufferPreset(PlaybackPerformanceSetting.PROFILE_LIGHTWEIGHT);
    }

    static void applyRebufferPreset(int profile) {
        Prefers.put(KEY_REBUFFER_MS, rebufferForPreset(profile));
    }

    static int rebufferForPreset(int profile) {
        return switch (profile) {
            case PlaybackPerformanceSetting.PROFILE_COMPATIBLE,
                 PlaybackPerformanceSetting.PROFILE_LIGHTWEIGHT -> 3_000;
            default -> 2_000;
        };
    }

    static int normalizeRebuffer(int value) {
        if (value <= 1_000) return 1_000;
        if (value <= 2_000) return 2_000;
        if (value <= 3_000) return 3_000;
        if (value <= 5_000) return 5_000;
        if (value <= 8_000) return 8_000;
        if (value <= 10_000) return 10_000;
        return 15_000;
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }
}
