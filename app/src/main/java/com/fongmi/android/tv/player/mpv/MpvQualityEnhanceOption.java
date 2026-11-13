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

    /** {@code --sharpen} 唯一有效的 vo。 */
    private static final String UNSHARP_VO = "gpu";

    private MpvQualityEnhanceOption() {
    }

    /** 该 vo 是否经过本机 GPU 着色器管线（决定增强选项是否有效）。 */
    public static boolean isSupported(String vo) {
        return vo != null && GPU_VO.contains(vo.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 该 vo 是否支持 {@code --sharpen}（unsharp mask）。
     *
     * <p><b>为什么必须区分。</b>mpv 文档对 {@code --sharpen} 明确标注「(Only for {@code --vo=gpu})」；
     * 对 {@code vo_gpu_next.c} 全文检索 {@code unsharp}/{@code sharpen} <b>零命中</b>——
     * gpu-next 从不读这个选项。它只在旧 {@code vo=gpu} 路径上被挂成渲染 hook
     * （{@code video/out/gpu/video.c} 的 {@code unsharp_hook}）。给 gpu-next 发它，
     * mpv 不会报错、不会提示，只是什么也不做——正是「开关开了、画面没变」的来源之一。
     */
    public static boolean isUnsharpSupported(String vo) {
        return vo != null && UNSHARP_VO.equals(vo.trim().toLowerCase(Locale.ROOT));
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
        return build(mode, denoise, vo, false);
    }

    /**
     * 生成增强选项，并指明超分着色器链是否正在同一个 vo 上运行。
     *
     * <p><b>为什么要这个开关（实测得出，不是推测）。</b>在本机挂上 Anime4K 后读回的日志是：
     * <pre>
     * shader chain applied anime4k=7
     * mpv quality enhance runtime vo=gpu-next written=5 scale=ewa_lanczos scale-antiring=0.5 ...
     * </pre>
     * 也就是<b>两套处理同时在跑</b>：Anime4K 先用神经网络把画面重建到 2 倍，紧接着本类的
     * {@code scale}/{@code scale-blur}/{@code sharpen} 又把结果重采样一遍。后者把前者刚补出来的
     * 边缘细节抹平了一部分，于是「效率档 vs 质量档」在同一帧上的像素差只有 <b>0.028%</b>
     * （同一档位连拍的噪声底是 0）。这既是「两档看不出差别」的直接原因，也让档位差异变得
     * 无法归因。
     *
     * <p>所以超分开启时本类只保留与着色器无关的部分（降噪/去色带/抖动），把缩放交给着色器
     * 链自己决定——这与 Kazumi 的做法一致：它挂上 Anime4K 后不再改 {@code scale}。
     *
     * @param anime4kActive 超分着色器链当前是否已挂载生效
     */
    public static Map<String, String> build(int mode, int denoise, String vo, boolean anime4kActive) {
        Map<String, String> options = new LinkedHashMap<>();
        if (!isSupported(vo)) return options;

        // 超分着色器在跑时，缩放/锐化必须让位：它自己会做重建与放大，再叠一层内置核等于
        // 把结果又模糊一遍。见方法 javadoc 里那次 0.028% 的实测。
        if (mode != MODE_OFF && !anime4kActive) {
            boolean strong = mode == MODE_STRONG;
            // 真正的梯度在这两行。轻度与强化必须落在**同一族、不同锐度**的核上，
            // 差别才稳定可见：曾经用过的 spline36 与强化档的 ewa_lanczossharp 分属两族，
            // 轻度档的画面其实和 mpv 默认的 lanczos **看不出区别**——这正是「开着没效果」。
            // 现在轻度用 ewa_lanczos（EWA 族基础档，边缘比 lanczos 干净），
            // 强化用 ewa_lanczossharp（同族锐化档，mpv 的 high-quality profile 用它）。
            // 两者在 gpu 与 gpu-next 下都有效。
            //
            // 为什么不再写 scaler-resizes-only=no / correct-downscaling=yes /
            // sigmoid-upscaling=yes：这三条在 gl_video_opts_def 里的默认值**本来就是**
            // true/true/true。写出来只是把默认值重抄一遍——其中 scaler-resizes-only=no
            // 还把默认改掉了，于是 1:1 显示时也走重型核：**收益只出现在需要缩放的场景，
            // 代价却付在所有场景**，这正是「开着没效果、设备却更吃力」的来源。
            options.put("scale", strong ? "ewa_lanczossharp" : "ewa_lanczos");
            // 色度平面同样受益；用同一档核避免色边。
            options.put("cscale", strong ? "ewa_lanczossharp" : "ewa_lanczos");
            // 抗振铃：抑制锐化后常见的边缘光环。mpv 默认是 0（不抑制），
            // 所以这两条是真实生效的改动，不是重抄默认值。
            options.put("scale-antiring", strong ? "0.7" : "0.5");
            options.put("cscale-antiring", strong ? "0.7" : "0.5");
            // 下采样核：默认是 hermite，这里换成 mitchell（更平滑、振铃更少）。
            options.put("dscale", "mitchell");
            if (isUnsharpSupported(vo)) {
                // unsharp mask 锐化只在 vo=gpu 存在。给 gpu-next 发它是纯粹的假动作，
                // 见 isUnsharpSupported 的说明。
                // 0.5 以上在小屏上会明显出现白边，故上限定在 0.5。
                options.put("sharpen", strong ? "0.5" : "0.2");
            }
            if (strong) {
                // 放大核的模糊系数（kernel blur）。<1 更锐、>1 更柔，mpv 默认 0
                // （= 用该核自己推荐的系数）。这一条是<b>放大时</b>唯一不依赖 vo 的
                // 锐化手段：它属于卷积核参数，gpu 与 gpu-next 都会读
                // （gpu 见 video/out/gpu/video.c 的 kernel.blur 赋值，gpu-next 见
                // vo_gpu_next.c 的 map_scaler）。
                // 文档明确提醒「别低于 0.5，建议落在 0.8–1.2」，故取 0.9 这个保守值。
                options.put("scale-blur", "0.9");
                options.put("cscale-blur", "0.9");
            }
        }

        if (denoise != DENOISE_OFF) {
            // deband 是 libplacebo 的色带消除。顶层选项接受 `yes`/`no`；
            // 带参数的写法（deband-iterations 等）由 mpv 配置系统另行拼接，
            // 本类只发顶层开关。
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

    /**
     * 超分/锐化<b>本行</b>是否需要高负载提示。
     *
     * <p>为什么不能直接用 {@link #needsPerformanceWarning}：那个函数看的是两个档位的
     * 「或」，用它来标超分行会出现「超分关闭、但降噪开了强化」时把超分行也标成高负载，
     * 用户会以为超分开着。同样降噪行也不能用——它会显示成「关闭（高负载）」。
     * 每行只看自己的档位。
     */
    public static boolean modeNeedsWarning(int mode) {
        return clampMode(mode) == MODE_STRONG;
    }

    /** 降噪/去色带<b>本行</b>是否需要高负载提示，理由同 {@link #modeNeedsWarning}。 */
    public static boolean denoiseNeedsWarning(int denoise) {
        return clampDenoise(denoise) == DENOISE_STRONG;
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

    /**
     * 按「关闭 → 轻度 → 强化 → 关闭」循环取下一档。
     *
     * <p><b>为什么不能用 {@code clampMode(getMode() + 1)}。</b>{@link #clampMode}
     * 是<b>钳制</b>语义：强化档（{@code MODE_STRONG} = 2）再 +1 得 3，被钳回 2，
     * 于是档位永远停在「强化」——用户点多少次都退不回「关闭」，且没有任何报错。
     * 循环必须取模回绕，与对话框里其它 cycle* 选项（如
     * {@code putSoftTuneMode((getSoftTuneMode() + 1) % 3)}）保持一致。
     */
    public static int nextMode(int value) {
        return (clampMode(value) + 1) % (MODE_STRONG + 1);
    }

    /** 降噪档位的循环，语义与 {@link #nextMode} 相同。 */
    public static int nextDenoise(int value) {
        return (clampDenoise(value) + 1) % (DENOISE_STRONG + 1);
    }
}
