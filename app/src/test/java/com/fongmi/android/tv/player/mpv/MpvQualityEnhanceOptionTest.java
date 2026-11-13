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
    public void lightModeUsesTheSofterEwaKernel() {
        Map<String, String> options = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_LIGHT,
                MpvQualityEnhanceOption.DENOISE_OFF,
                "gpu-next");
        assertEquals("ewa_lanczos", options.get("scale"));
        assertEquals("ewa_lanczos", options.get("cscale"));
        // gpu-next 不读 --sharpen，发它只会让人以为「开了却没变」。
        assertFalse(options.containsKey("sharpen"));
        // 内核模糊系数只给强化档，轻度档不该带上。
        assertFalse(options.containsKey("scale-blur"));
    }

    @Test
    public void theTwoTiersPickDifferentKernelsOfTheSameFamily() {
        // 这一条钉住「轻度」与「强化」真的不一样。曾经轻度用 spline36、强化用
        // ewa_lanczossharp，两族核的画面差别小到用户看不出——「开着没效果」的直接来源。
        Map<String, String> light = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_LIGHT, MpvQualityEnhanceOption.DENOISE_OFF, "gpu-next");
        Map<String, String> strong = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_STRONG, MpvQualityEnhanceOption.DENOISE_OFF, "gpu-next");
        assertFalse(light.get("scale").equals(strong.get("scale")));
    }

    @Test
    public void strongModeUsesTheSharpenedEwaKernel() {
        Map<String, String> options = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_STRONG,
                MpvQualityEnhanceOption.DENOISE_OFF,
                "gpu-next");
        assertEquals("ewa_lanczossharp", options.get("scale"));
        assertEquals("ewa_lanczossharp", options.get("cscale"));
        assertFalse(options.containsKey("sharpen"));
        // 放大核的模糊系数是强化档唯一不依赖 vo 的锐化手段，必须真的发出去。
        assertEquals("0.9", options.get("scale-blur"));
        assertEquals("0.9", options.get("cscale-blur"));
    }

    @Test
    public void sharpenIsSentOnPlainGpuButNotOnGpuNext() {
        // vo_gpu_next.c 全文检索 unsharp/sharpen 零命中：这个选项只在旧 vo=gpu 上存在。
        // 同一个档位在两个管线上的选项集不同，是这组断言要保住的事实。
        Map<String, String> gpu = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_LIGHT, MpvQualityEnhanceOption.DENOISE_OFF, "gpu");
        Map<String, String> next = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_LIGHT, MpvQualityEnhanceOption.DENOISE_OFF, "gpu-next");
        assertEquals("0.2", gpu.get("sharpen"));
        assertFalse(next.containsKey("sharpen"));
    }

    @Test
    public void isUnsharpSupportedOnlyAcceptsPlainGpu() {
        assertTrue(MpvQualityEnhanceOption.isUnsharpSupported("gpu"));
        assertTrue(MpvQualityEnhanceOption.isUnsharpSupported(" GPU "));
        assertFalse(MpvQualityEnhanceOption.isUnsharpSupported("gpu-next"));
        assertFalse(MpvQualityEnhanceOption.isUnsharpSupported("mediacodec_embed"));
        assertFalse(MpvQualityEnhanceOption.isUnsharpSupported(null));
        assertFalse(MpvQualityEnhanceOption.isUnsharpSupported("  "));
    }

    @Test
    public void strongModeNeverExceedsSharpenCap() {
        // 锐化超过 0.5 在小屏上会出现明显白边，这里把上限钉住。
        // 用 gpu 而不是 gpu-next：后者根本不发这个选项。
        Map<String, String> options = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_STRONG,
                MpvQualityEnhanceOption.DENOISE_OFF,
                "gpu");
        assertTrue(Double.parseDouble(options.get("sharpen")) <= 0.5);
    }

    @Test
    public void defaultsAreNeverRestated() {
        // 这三条在 gl_video_opts_def 里的默认值本来就是 true/true/true。
        // 写出来只是重抄默认值；其中 scaler-resizes-only=no 还把默认改掉了，
        // 让 1:1 显示时也走重型核——收益只在缩放场景，代价付在所有场景。
        Map<String, String> options = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_STRONG,
                MpvQualityEnhanceOption.DENOISE_OFF,
                "gpu-next");
        assertFalse(options.containsKey("scaler-resizes-only"));
        assertFalse(options.containsKey("correct-downscaling"));
        assertFalse(options.containsKey("sigmoid-upscaling"));
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
    public void anime4kActiveDropsScalerOptionsButKeepsDenoise() {
        // 真机日志同时打出过 shader chain applied anime4k=7 与
        // mpv quality enhance runtime written=5 scale=ewa_lanczos，两套在抢同一条管线。
        // 着色器已经在做放大与修线，再叠内置缩放核只会互相抵消；但降噪（deband/dither）
        // 是着色器完全不碰的环节，必须留着，否则「开超分就把降噪弄没了」。
        Map<String, String> withoutShaders = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_STRONG,
                MpvQualityEnhanceOption.DENOISE_STRONG,
                "gpu-next",
                false);
        Map<String, String> withShaders = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_STRONG,
                MpvQualityEnhanceOption.DENOISE_STRONG,
                "gpu-next",
                true);
        assertFalse("默认重载应当等同于 anime4kActive=false", withoutShaders.isEmpty());
        for (String dropped : new String[]{
                "scale", "cscale", "dscale", "scale-blur", "cscale-blur",
                "scale-antiring", "cscale-antiring", "sharpen"}) {
            assertFalse("超分在跑时不应再发缩放/锐化选项: " + dropped,
                    withShaders.containsKey(dropped));
        }
        assertEquals("yes", withShaders.get("deband"));
        assertEquals("yes", withShaders.get("temporal-dither"));
        assertEquals("8", withShaders.get("dither-size-fruit"));
    }

    @Test
    public void anime4kActiveAtOffModeChangesNothing() {
        // 超分关闭时第 4 个参数必须完全不起作用，否则「关了超分档位却还留着半套设置」。
        assertTrue(MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_OFF,
                MpvQualityEnhanceOption.DENOISE_OFF,
                "gpu-next",
                true).isEmpty());
        assertEquals(MpvQualityEnhanceOption.build(
                        MpvQualityEnhanceOption.MODE_OFF,
                        MpvQualityEnhanceOption.DENOISE_LIGHT,
                        "gpu-next",
                        false),
                MpvQualityEnhanceOption.build(
                        MpvQualityEnhanceOption.MODE_OFF,
                        MpvQualityEnhanceOption.DENOISE_LIGHT,
                        "gpu-next",
                        true));
        // 硬解直出依旧是整套不发（着色器管线根本不存在）。
        assertTrue(MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_STRONG,
                MpvQualityEnhanceOption.DENOISE_STRONG,
                "mediacodec_embed",
                true).isEmpty());
    }

    @Test
    public void neverEmitsTheseAbsentOptionNames() {
        // 这些名字在随包 libmpv.so（双 ABI）的选项名表里确认不存在。
        // mpv 对未知选项不报错、不提示，只是静默忽略，所以必须由测试拦住。
        //
        // 注意 deband 的四个子选项早已从这份清单里拿掉：它们是 mpv 配置系统运行时
        // 用「父名 + '-' + 子字段名」拼出来的，字面串本来就不会出现在二进制里
        // （video.c 的 OPT_SUBSTRUCT(deband_opts, deband_conf)；options.rst 正式文档化了
        // --deband-iterations / --deband-threshold / --deband-range / --deband-grain）。
        // 仅有「名字在二进制里找不到」不足以判定选项不存在。
        Map<String, String> options = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_STRONG,
                MpvQualityEnhanceOption.DENOISE_STRONG,
                "gpu-next");
        for (String forbidden : new String[]{
                "user-shaders", "unsharp", "hqdn3d", "atadenoise", "scaler-lut-size"}) {
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
            "scale", "cscale", "scale-blur", "cscale-blur", "dscale",
            "scale-antiring", "cscale-antiring", "sharpen",
            "deband", "temporal-dither", "dither-size-fruit");

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

    /**
     * 回归：档位必须能循环回到「关闭」。
     *
     * <p>实测缺陷：原先点一下是 {@code clampMode(getMode() + 1)}，而 clamp 是钳制语义，
     * 到强化（2）后 2+1=3 被钳回 2，于是永远卡在「强化」，用户点多少次都退不回去，
     * 且没有任何报错。「重置」也不清这两条键，用户完全被困住。
     */
    @Test
    public void modeCyclesBackToOff() {
        int mode = MpvQualityEnhanceOption.MODE_OFF;
        mode = MpvQualityEnhanceOption.nextMode(mode);
        assertEquals(MpvQualityEnhanceOption.MODE_LIGHT, mode);
        mode = MpvQualityEnhanceOption.nextMode(mode);
        assertEquals(MpvQualityEnhanceOption.MODE_STRONG, mode);
        // 关键断言：从强化必须回到关闭，而不是被钳制在强化。
        mode = MpvQualityEnhanceOption.nextMode(mode);
        assertEquals(MpvQualityEnhanceOption.MODE_OFF, mode);
    }

    @Test
    public void denoiseCyclesBackToOff() {
        int denoise = MpvQualityEnhanceOption.DENOISE_OFF;
        denoise = MpvQualityEnhanceOption.nextDenoise(denoise);
        assertEquals(MpvQualityEnhanceOption.DENOISE_LIGHT, denoise);
        denoise = MpvQualityEnhanceOption.nextDenoise(denoise);
        assertEquals(MpvQualityEnhanceOption.DENOISE_STRONG, denoise);
        denoise = MpvQualityEnhanceOption.nextDenoise(denoise);
        assertEquals(MpvQualityEnhanceOption.DENOISE_OFF, denoise);
    }

    /** 被旧值或手工改坏的超大档位也必须能循环回合法值，不能抛出或卡住。 */
    @Test
    public void cyclingFromOutOfRangeValueStaysLegal() {
        assertEquals(MpvQualityEnhanceOption.MODE_OFF,
                MpvQualityEnhanceOption.nextMode(MpvQualityEnhanceOption.MODE_STRONG + 50));
        assertEquals(MpvQualityEnhanceOption.DENOISE_OFF,
                MpvQualityEnhanceOption.nextDenoise(MpvQualityEnhanceOption.DENOISE_STRONG + 50));
        assertEquals(MpvQualityEnhanceOption.MODE_LIGHT,
                MpvQualityEnhanceOption.nextMode(-99));
    }

    /**
     * 回归：每一行的「高负载」提示只看本行档位。
     *
     * <p>实测缺陷：原先两行都用「两个档位的或」判断，于是超分关闭而降噪强化时，
     * 超分行也被标成高负载；降噪行则显示成「关闭（高负载）」这种自相矛盾的文案。
     */
    @Test
    public void warningIsPerRowNotShared() {
        assertFalse(MpvQualityEnhanceOption.modeNeedsWarning(MpvQualityEnhanceOption.MODE_OFF));
        assertFalse(MpvQualityEnhanceOption.modeNeedsWarning(MpvQualityEnhanceOption.MODE_LIGHT));
        assertTrue(MpvQualityEnhanceOption.modeNeedsWarning(MpvQualityEnhanceOption.MODE_STRONG));
        assertFalse(MpvQualityEnhanceOption.denoiseNeedsWarning(MpvQualityEnhanceOption.DENOISE_OFF));
        assertFalse(MpvQualityEnhanceOption.denoiseNeedsWarning(MpvQualityEnhanceOption.DENOISE_LIGHT));
        assertTrue(MpvQualityEnhanceOption.denoiseNeedsWarning(MpvQualityEnhanceOption.DENOISE_STRONG));
    }

    @Test
    public void modesAndDenoiseComposeIndependently() {
        Map<String, String> both = MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_LIGHT,
                MpvQualityEnhanceOption.DENOISE_LIGHT,
                "gpu-next");
        assertEquals("ewa_lanczos", both.get("scale"));
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
