package com.fongmi.android.tv.player.mpv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

/**
 * 画质增强选项的回归测试。
 *
 * <p>这组断言的价值集中在两件在真机上极难发现的事：
 *
 * <ol>
 *   <li><b>关闭时不产生任何选项。</b>{@code MpvPlayer.setOption} 会静默吞掉错误，
 *       所以「关了但还是发了选项」不会被报错暴露，只会表现为画质/性能与预期不符。</li>
 *   <li><b>只发确有其名的选项。</b>随包 {@code libmpv.so} 里不存在的选项名同样被静默忽略，
 *       写错了既没日志也没异常。这些名字都已对双 ABI 的选项名表核对过，
 *       用测试钉住可以防止以后有人顺手把 {@code deband-iterations} 这类名字加回来。</li>
 * </ol>
 */
public class MpvQualityEnhanceOptionTest {

    @Test
    public void offProducesNoOptions() {
        assertTrue(MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_OFF,
                MpvQualityEnhanceOption.DENOISE_OFF,
                "gpu-next").isEmpty());
    }

    @Test
    public void offProducesNoOptionsOnPlainGpuToo() {
        assertTrue(MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_OFF,
                MpvQualityEnhanceOption.DENOISE_OFF,
                "gpu").isEmpty());
    }

    @Test
    public void surfaceDirectIsNotSupported() {
        assertFalse(MpvQualityEnhanceOption.isSupported("mediacodec_embed"));
    }

    @Test
    public void surfaceDirectProducesNoOptionsEvenWhenEverythingIsOn() {
        // 电视直出时画面由 MediaCodec 直接送到 Surface，不经过着色器管线。
        // 此时发选项不但无效，还会让人误判开关失效。
        Map<String, String> options = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_STRONG,
                MpvQualityEnhanceOption.DENOISE_STRONG,
                "mediacodec_embed");
        assertTrue(options.isEmpty());
    }

    @Test
    public void nullAndBlankVoAreNotSupported() {
        assertFalse(MpvQualityEnhanceOption.isSupported(null));
        assertFalse(MpvQualityEnhanceOption.isSupported("  "));
    }

    @Test
    public void voMatchIgnoresCaseAndPadding() {
        assertTrue(MpvQualityEnhanceOption.isSupported(" GPU-NEXT "));
        assertTrue(MpvQualityEnhanceOption.isSupported("Gpu"));
    }

    @Test
    public void lightModeUsesModerateScalerAndMildSharpen() {
        Map<String, String> options = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_LIGHT,
                MpvQualityEnhanceOption.DENOISE_OFF,
                "gpu-next");
        assertEquals("spline36", options.get("scale"));
        assertEquals("spline36", options.get("cscale"));
        assertEquals("0.2", options.get("sharpen"));
        assertFalse(options.containsKey("sigmoid-upscaling"));
    }

    @Test
    public void strongModeUsesSharpScalerAndStrongerSharpen() {
        Map<String, String> options = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_STRONG,
                MpvQualityEnhanceOption.DENOISE_OFF,
                "gpu-next");
        assertEquals("ewa_lanczossharp", options.get("scale"));
        assertEquals("ewa_lanczossharp", options.get("cscale"));
        assertEquals("0.5", options.get("sharpen"));
        assertEquals("yes", options.get("sigmoid-upscaling"));
    }

    @Test
    public void strongModeNeverExceedsSharpenCap() {
        // 锐化超过 0.5 在小屏上会出现明显白边，这里把上限钉住。
        Map<String, String> options = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_STRONG,
                MpvQualityEnhanceOption.DENOISE_OFF,
                "gpu-next");
        assertTrue(Double.parseDouble(options.get("sharpen")) <= 0.5);
    }

    @Test
    public void antiringingGrowsWithStrength() {
        Map<String, String> light = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_LIGHT, MpvQualityEnhanceOption.DENOISE_OFF, "gpu");
        Map<String, String> strong = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_STRONG, MpvQualityEnhanceOption.DENOISE_OFF, "gpu");
        assertTrue(Double.parseDouble(strong.get("scale-antiring"))
                > Double.parseDouble(light.get("scale-antiring")));
        assertEquals(light.get("scale-antiring"), light.get("cscale-antiring"));
        assertEquals(strong.get("scale-antiring"), strong.get("cscale-antiring"));
    }

    @Test
    public void denoiseEnablesDebandAndTemporalDither() {
        Map<String, String> options = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_OFF,
                MpvQualityEnhanceOption.DENOISE_LIGHT,
                "gpu-next");
        assertEquals("yes", options.get("deband"));
        assertEquals("yes", options.get("temporal-dither"));
        assertFalse(options.containsKey("dither-size-fruit"));
    }

    @Test
    public void strongDenoiseAlsoRaisesDitherLut() {
        Map<String, String> options = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_OFF,
                MpvQualityEnhanceOption.DENOISE_STRONG,
                "gpu-next");
        assertEquals("8", options.get("dither-size-fruit"));
    }

    @Test
    public void neverEmitsOptionNamesMissingFromBundledMpv() {
        // 这些名字在随包 libmpv.so（双 ABI）的选项名表里确认不存在。
        // mpv 对未知选项不报错、不提示，只是静默忽略，所以必须由测试拦住。
        Map<String, String> options = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_STRONG,
                MpvQualityEnhanceOption.DENOISE_STRONG,
                "gpu-next");
        for (String forbidden : new String[]{
                "deband-iterations", "deband-threshold", "deband-range", "deband-grain",
                "user-shaders", "unsharp", "hqdn3d", "atadenoise"}) {
            assertFalse("不应发出随包 mpv 不存在的选项: " + forbidden,
                    options.containsKey(forbidden));
        }
    }

    @Test
    public void everyEmittedOptionNameExistsInBundledMpv() {
        Map<String, String> options = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_STRONG,
                MpvQualityEnhanceOption.DENOISE_STRONG,
                "gpu-next");
        for (String name : options.keySet()) {
            assertTrue("意外选项名: " + name, ALLOWED.contains(name));
        }
    }

    private static final java.util.Set<String> ALLOWED = java.util.Set.of(
            "scale", "cscale", "dscale", "scale-antiring", "cscale-antiring",
            "correct-downscaling", "scaler-resizes-only", "sharpen",
            "sigmoid-upscaling", "deband", "temporal-dither", "dither-size-fruit");

    @Test
    public void noEmptyKeysOrValues() {
        Map<String, String> options = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_STRONG,
                MpvQualityEnhanceOption.DENOISE_STRONG,
                "gpu-next");
        for (Map.Entry<String, String> entry : options.entrySet()) {
            assertNotNull(entry.getKey());
            assertNotNull(entry.getValue());
            assertFalse(entry.getKey().isEmpty());
            assertFalse(entry.getValue().isEmpty());
        }
    }

    @Test
    public void performanceWarningOnlyForStrongTiers() {
        assertFalse(MpvQualityEnhanceOption.needsPerformanceWarning(
                MpvQualityEnhanceOption.MODE_OFF, MpvQualityEnhanceOption.DENOISE_OFF));
        assertFalse(MpvQualityEnhanceOption.needsPerformanceWarning(
                MpvQualityEnhanceOption.MODE_LIGHT, MpvQualityEnhanceOption.DENOISE_LIGHT));
        assertTrue(MpvQualityEnhanceOption.needsPerformanceWarning(
                MpvQualityEnhanceOption.MODE_STRONG, MpvQualityEnhanceOption.DENOISE_OFF));
        assertTrue(MpvQualityEnhanceOption.needsPerformanceWarning(
                MpvQualityEnhanceOption.MODE_OFF, MpvQualityEnhanceOption.DENOISE_STRONG));
    }

    @Test
    public void modesAndDenoiseComposeIndependently() {
        Map<String, String> both = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_LIGHT,
                MpvQualityEnhanceOption.DENOISE_LIGHT,
                "gpu-next");
        assertEquals("spline36", both.get("scale"));
        assertEquals("yes", both.get("deband"));
    }

    @Test
    public void describeIsEmptyForNoOptions() {
        assertEquals("", MpvQualityEnhanceOption.describe(null));
        assertEquals("", MpvQualityEnhanceOption.describe(Map.of()));
    }

    @Test
    public void describeListsEveryOption() {
        Map<String, String> options = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_LIGHT,
                MpvQualityEnhanceOption.DENOISE_OFF,
                "gpu-next");
        String text = MpvQualityEnhanceOption.describe(options);
        for (String name : options.keySet()) {
            assertTrue(text.contains(name + "=" + options.get(name)));
        }
    }

    @Test
    public void clampRejectsOutOfRangeValues() {
        assertEquals(MpvQualityEnhanceOption.MODE_OFF, MpvQualityEnhanceOption.clampMode(-3));
        assertEquals(MpvQualityEnhanceOption.MODE_STRONG, MpvQualityEnhanceOption.clampMode(99));
        assertEquals(MpvQualityEnhanceOption.DENOISE_OFF, MpvQualityEnhanceOption.clampDenoise(-1));
        assertEquals(MpvQualityEnhanceOption.DENOISE_STRONG, MpvQualityEnhanceOption.clampDenoise(7));
    }

    @Test
    public void clampedValueBehavesLikeItsBoundary() {
        // 设置被外部改坏成越界值时，不能因此发出发疯的选项组合。
        Map<String, String> clamped = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.clampMode(500),
                MpvQualityEnhanceOption.clampDenoise(-9),
                "gpu-next");
        Map<String, String> strongOff = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_STRONG,
                MpvQualityEnhanceOption.DENOISE_OFF,
                "gpu-next");
        assertEquals(strongOff, clamped);
    }

    @Test
    public void optionsAreOrderedDeterministically() {
        // 相同输入两次调用必须给出同样的顺序，否则日志与选项对比会没有意义。
        Map<String, String> first = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_STRONG,
                MpvQualityEnhanceOption.DENOISE_STRONG,
                "gpu-next");
        Map<String, String> second = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_STRONG,
                MpvQualityEnhanceOption.DENOISE_STRONG,
                "gpu-next");
        assertEquals(java.util.List.copyOf(first.keySet()), java.util.List.copyOf(second.keySet()));
    }

    @Test
    public void dscaleIsAlwaysSetWhenEnhancing() {
        // 下采样核与放大核必须同时指定，否则缩小时会退回默认核、出现清晰度跳变。
        for (String vo : new String[]{"gpu", "gpu-next"}) {
            Map<String, String> options = MpvQualityEnhanceOption.build(
                    MpvQualityEnhanceOption.MODE_LIGHT,
                    MpvQualityEnhanceOption.DENOISE_OFF,
                    vo);
            assertEquals("mitchell", options.get("dscale"));
        }
    }
}
