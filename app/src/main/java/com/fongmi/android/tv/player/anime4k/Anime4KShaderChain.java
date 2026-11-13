package com.fongmi.android.tv.player.anime4k;

import com.fongmi.android.tv.player.mpv.MpvQualityEnhanceOption;

import java.util.ArrayList;
import java.util.List;

/**
 * 超分档位对应的 Anime4K 着色器链。
 *
 * <p><b>为什么是「真超分」而不是调缩放核。</b>之前那版超分只改 mpv 内置的
 * {@code scale}/{@code cscale}/{@code sharpen}：这些全是重采样核与锐化，1080p 片源在 1080p
 * 屏上根本看不出差别（{@code --sharpen} 更是在 gpu-next 下被静默忽略）。
 * Anime4K 是卷积神经网络着色器，靠 {@code glsl-shaders} 挂在渲染管线上做边缘重建，
 * 才是用户能看见的「超分」。
 *
 * <p><b>链的顺序有意义。</b>这些 pass 靠 {@code //!HOOK}/{@code //!BIND} 彼此衔接，
 * 换档时必须整条重挂，不能只补差集——只补差集会让共用的 pass 留在旧位置，
 * 顺序一乱画面就会明显劣化。{@link #names(int)} 的顺序照搬上游 Anime4K 的推荐链。
 *
 * <p>纯逻辑类，不依赖 Android，可在 JVM 单测里直接跑。
 */
public final class Anime4KShaderChain {

    /** 目录名（也是 assets 下的目录名）。 */
    public static final String DIRECTORY = "anime4k";

    private static final String CLAMP = "Anime4K_Clamp_Highlights.glsl";
    private static final String RESTORE_VL = "Anime4K_Restore_CNN_VL.glsl";
    private static final String RESTORE_M = "Anime4K_Restore_CNN_M.glsl";
    private static final String RESTORE_S = "Anime4K_Restore_CNN_S.glsl";
    private static final String UPSCALE_VL = "Anime4K_Upscale_CNN_x2_VL.glsl";
    private static final String UPSCALE_M = "Anime4K_Upscale_CNN_x2_M.glsl";
    private static final String UPSCALE_S = "Anime4K_Upscale_CNN_x2_S.glsl";
    private static final String PRE_X2 = "Anime4K_AutoDownscalePre_x2.glsl";
    private static final String PRE_X4 = "Anime4K_AutoDownscalePre_x4.glsl";

    /**
     * 效率档：只用 S/M 这两档轻量网络，先恢复再放大。
     * 顺序与上游一致，末尾的 S 网络负责把 x2 之后的残留缩放补掉。
     */
    private static final List<String> EFFICIENCY = List.of(
            CLAMP, RESTORE_M, RESTORE_S, UPSCALE_M, PRE_X2, PRE_X4, UPSCALE_S);

    /**
     * 质量档：Restore 与 Upscale 都换成 VL（超大网络），最后再多一次 M 网络收尾。
     * 明显更吃 GPU，仅此一档需要给用户看性能提示。
     */
    private static final List<String> QUALITY = List.of(
            CLAMP, RESTORE_VL, UPSCALE_VL, PRE_X2, PRE_X4, UPSCALE_M);

    private Anime4KShaderChain() {
    }

    /**
     * 档位对应的文件名单。
     *
     * @param mode {@link MpvQualityEnhanceOption#MODE_OFF} / {@code MODE_LIGHT}（效率档）/
     *             {@code MODE_STRONG}（质量档），越界按关闭处理
     * @return 文件名（不是路径），关闭时返回空表；永远不返回 {@code null}
     */
    public static List<String> names(int mode) {
        return switch (mode) {
            case MpvQualityEnhanceOption.MODE_LIGHT -> EFFICIENCY;
            case MpvQualityEnhanceOption.MODE_STRONG -> QUALITY;
            default -> List.of();
        };
    }

    /** 该档位是否需要挂 shader（关闭档返回 false）。 */
    public static boolean needsShaders(int mode) {
        return !names(mode).isEmpty();
    }

    /** 日志用的档位名（与用户看到的文案无关，只为排查时能对上号）。 */
    public static String tierName(int mode) {
        return switch (mode) {
            case MpvQualityEnhanceOption.MODE_LIGHT -> "efficiency";
            case MpvQualityEnhanceOption.MODE_STRONG -> "quality";
            default -> "off";
        };
    }

    /** 把文件名前缀上目录，得到 assets 相对路径。 */
    public static List<String> assetPaths(int mode) {
        List<String> names = names(mode);
        if (names.isEmpty()) return List.of();
        List<String> paths = new ArrayList<>(names.size());
        for (String name : names) paths.add(DIRECTORY + "/" + name);
        return paths;
    }

    /** 所有会用到、因此必须打进包里的文件名（两档的并集，按名称排序保证稳定）。 */
    public static List<String> allNames() {
        List<String> all = new ArrayList<>(EFFICIENCY);
        for (String name : QUALITY) if (!all.contains(name)) all.add(name);
        return all;
    }
}
