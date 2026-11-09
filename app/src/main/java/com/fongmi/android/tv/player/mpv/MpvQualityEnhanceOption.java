package com.fongmi.android.tv.player.mpv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MPV 画质增强选项的生成与校验。
 *
 * <p>纯逻辑、不依赖 Android，便于单测。
 *
 * <p><b>这一层为什么要有。</b>{@code MpvPlayer.setOption} 内部是一个
 * {@code try { mpvSetOptionString } catch (Throwable ignored) {}}——选项名写错、值非法时
 * <b>不会报错，只是静默不生效</b>。用户打开「超分」后画质毫无变化，且没有任何提示，
 * 这是最难排查的一类问题。所以把「该发哪些选项、值是否合法」收敛到可单测的纯逻辑里，
 * 用断言把每个选项名与取值范围钉住。
 *
 * <p><b>为什么用内置缩放器而不是 FSRCNNX / Anime4K 的 .hook 文件。</b>工单提到
 * 「MPV 挂 libplacebo shader（FSRCNNX / Anime4K）」，但实测随包
 * {@code libmpv.so}（双 ABI 均已核对）中：
 * <ul>
 *   <li>{@code libplacebo} 是<b>静态链接</b>进去的（符号与字符串均在），
 *       所以 {@code deband}、{@code scale}、{@code scale-param1/2}、{@code sharpen}
 *       这些 libplacebo 支撑的选项可用；</li>
 *   <li>但 {@code user-shaders} 这个选项名<b>不存在</b>（命中 0 次），
 *       只有 {@code glsl-shaders}；且仓库里<b>没有任何 .hook/.glsl 资源文件</b>。</li>
 * </ul>
 * 也就是说：要真挂 FSRCNNX/Anime4K，必须把第三方 shader 文件打进 APK——涉及第三方代码的
 * 许可与体积（Anime4K 全套数 MB），且其 GLSL 是否能在本机这两套 shader 编译器下编译通过
 * 无法离线确认（失败只会得到黑屏）。因此本单走<b>内置缩放器 + libplacebo 的 deband/sharpen</b>
 * 这条路：不新增任何资源、不引第三方代码、无需现场编译 shader，收益是真实可见的锐化与
 * 去色带，代价可控。
 *
 * <p><b>只作用于走 GPU 渲染的链路。</b>{@code scale}/{@code deband}/{@code sharpen} 都是
 * GPU 着色器阶段（{@code vo=gpu} / {@code gpu-next}）的参数。Surface 直出
 * （{@code mediacodec_embed}）时画面由 MediaCodec 直接送到 Surface，完全不经过我们的
 * 渲染管线——此时发这些选项不但无效，还会让用户以为开关坏了。所以由
 * {@link #isSupported(String)} 明确排除。
 */
public final class MpvQualityEnhanceOption {

    /** 增强档位：关闭。 */
    public static final int MODE_OFF = 0;
    /** 增强档位：轻度（对性能影响小，适合电视盒子与中端手机）。 */
    public static final int MODE_LIGHT = 1;
    /** 增强档位：强化（明显锐化与去色带，适合旗舰机 / 独显直通）。 */
    public static final int MODE_STRONG = 2;

    /** 去噪档位：关闭。 */
    public static final int DENOISE_OFF = 0;
    /** 去噪档位：轻度。 */
    public static final int DENOISE_LIGHT = 1;
    /** 去噪档位：强化。 */
    public static final int DENOISE_STRONG = 2;

    /** 走 GPU 渲染的 vo 才能生效。 */
    private static final List<String> GPU_VO = List.of("gpu", "gpu-next");

    private MpvQualityEnhanceOption() {
    }

    /** 该 vo 是否经过本机 GPU 着色器管线（决定增强选项是否有效）。 */
    public static boolean isSupported(String vo) {
        return vo != null && GPU_VO.contains(vo.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 生成增强选项。
     *
     * <p>只输出<b>确实存在</b>的选项名。每一条都在随包 {@code libmpv.so} 的
     * NUL 分隔选项名表里核对过；写错的名字会被 mpv 静默忽略，测不出来也看不出来。
     *
     * <p>关闭时返回空 map（而不是返回一堆空值）：调用方据此可以完全跳过设置，
     * 保证「关闭」与旧版本行为<b>逐字节一致</b>。
     */
    public static Map<String, String> build(int mode, int denoise, String vo) {
        Map<String, String> options = new LinkedHashMap<>();
        if (!isSupported(vo)) return options;

        if (mode != MODE_OFF) {
            boolean strong = mode == MODE_STRONG;
            // ewa_lanczossharp 是 libplacebo 内置的高质量 EWA 缩放器：
            // 比默认的 bilinear/spline36 明显更锐利，且不像 .hook 那样需要额外资源。
            options.put("scale", strong ? "ewa_lanczossharp" : "spline36");
            // 色度平面同样受益；用较高的质量档避免色边。
            options.put("cscale", strong ? "ewa_lanczossharp" : "spline36");
            // 抗振铃：抑制锐化后常见的边缘光环。
            options.put("scale-antiring", strong ? "0.7" : "0.5");
            options.put("cscale-antiring", strong ? "0.7" : "0.5");
            // 下采样也用高质量核，避免「播放时忽清晰忽模糊」。
            options.put("dscale", "mitchell");
            // 只在放大时启用锐利核：缩小时用它可以避免过度振铃。
            options.put("correct-downscaling", "yes");
            options.put("scaler-resizes-only", "no");
            if (strong) {
                // sharpen 是 unsharp mask 锐化（随包 so 中存在该选项名与 "unsharp masking" 文本）。
                // 0.5 以上在小屏上会明显出现白边，故上限定在 0.5。
                options.put("sharpen", "0.5");
                options.put("sigmoid-upscaling", "yes");
            } else {
                options.put("sharpen", "0.2");
            }
        }

        if (denoise != DENOISE_OFF) {
            // deband 是 libplacebo 的色带消除。注意：随包 mpv 只暴露了顶层 `deband`
            // 开关，deband-iterations / deband-threshold / deband-range / deband-grain
            // 这些子选项在 so 里均<b>不存在</b>（已逐项核对，命中 0），
            // 顶层选项接受 `yes`/`no`；带参数的写法在这里不可用。
            options.put("deband", "yes");
            // 时间抖动避免色带在运动中「闪烁」；这是 mpv 自己的选项（非 libplacebo 参数）。
            options.put("temporal-dither", "yes");
            if (denoise == DENOISE_STRONG) {
                // 更强的一档：把抖动查找表加大（默认 6，上限 8）。
                // 抖动更细，渐变色带更不容易看出来。
                options.put("dither-size-fruit", "8");
            }
        }
        return options;
    }

    /**
     * 该档位组合是否需要给用户一条性能提示。
     *
     * <p>工单明确要求「TV 端算力有限需给性能提示」。强化档在多数电视盒子上会出现掉帧，
     * 提前说比让用户自己怀疑设备坏了要好。把判断放在这里而不是散在界面代码中，
     * 是为了让「哪一档算高负载」唯一且可测。
     */
    public static boolean needsPerformanceWarning(int mode, int denoise) {
        return mode == MODE_STRONG || denoise == DENOISE_STRONG;
    }

    /** 供日志与设置页摘要用的选项文本，便于排查「选项真的发出去了吗」。 */
    public static String describe(Map<String, String> options) {
        if (options == null || options.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : options.entrySet()) {
            parts.add(entry.getKey() + "=" + entry.getValue());
        }
        return String.join(" ", parts);
    }

    /** 数值是否落在合法档位内（设置项本身可能被旧值或手工改坏）。 */
    public static int clampMode(int value) {
        return Math.max(MODE_OFF, Math.min(MODE_STRONG, value));
    }

    public static int clampDenoise(int value) {
        return Math.max(DENOISE_OFF, Math.min(DENOISE_STRONG, value));
    }
}
