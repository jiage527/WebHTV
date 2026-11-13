package com.fongmi.android.tv.player.mpv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行时下发 / 还原账本的回归测试。
 *
 * <p>这组断言要守住的是三件在真机上很难发现的事：
 *
 * <ol>
 *   <li><b>用户自己的 mpv.conf 不能被永久改掉。</b>第一次写某个键之前必须记下它当时的
 *       原值，关掉增强时写回去。测不出来的话，用户会发现关掉开关后画面仍是增强后的样子，
 *       而他没有任何办法查出是谁改的。</li>
 *   <li><b>记不到原值的键宁可不写。</b>写下去却无法还原，等于单方面替用户做了决定。</li>
 *   <li><b>账本要跨次沿用。</b>第二次下发时必须复用第一次记下的原值，而不是把「已经
 *       被我们改过的当前值」当成原值记下来——那样还原出来的是增强值，用户永远退不回去。</li>
 * </ol>
 */
public class MpvQualityEnhanceRuntimeTest {

    @Test
    public void firstApplyCapturesTheValueThatWasActuallyThere() {
        Map<String, String> native_ = new LinkedHashMap<>();
        native_.put("scale", "ewa_lanczossoft");

        MpvQualityEnhanceRuntime.Plan plan = MpvQualityEnhanceRuntime.plan(
                Map.of(), Map.of("scale", "ewa_lanczossharp"), native_::get);

        assertEquals(1, plan.writes().size());
        assertEquals("ewa_lanczossharp", plan.writes().get(0).value());
        assertEquals("ewa_lanczossoft", plan.writes().get(0).original());
        assertTrue(plan.writes().get(0).captured());
        assertEquals("ewa_lanczossoft", plan.bookkeeping().get("scale"));
        assertTrue(plan.restore().isEmpty());
    }

    @Test
    public void unreadableValueFallsBackToTheMpvDefault() {
        // 读不到（比如属性名在我们的 mpv 构建里不可读）时用 gl_video_opts_def 的默认值兜底，
        // 这样关掉增强后画面仍能回到「没开增强」的样子。
        MpvQualityEnhanceRuntime.Plan plan = MpvQualityEnhanceRuntime.plan(
                Map.of(), Map.of("deband", "yes"), name -> null);

        assertEquals("no", plan.bookkeeping().get("deband"));
        assertEquals("no", plan.writes().get(0).original());
    }

    @Test
    public void unknownOptionIsSkippedRatherThanWrittenUnrestorably() {
        // 既读不到、也没有默认值的键：写下去就没法还原，所以直接跳过。
        MpvQualityEnhanceRuntime.Plan plan = MpvQualityEnhanceRuntime.plan(
                Map.of(), Map.of("some-future-option", "1"), name -> null);

        assertTrue(plan.writes().isEmpty());
        assertTrue(plan.bookkeeping().isEmpty());
    }

    @Test
    public void secondApplyReusesTheFirstOriginalInsteadOfRecordingOurOwnValue() {
        // 第一次：原值 spline16 → 写 ewa_lanczossharp。
        Map<String, String> previous = new LinkedHashMap<>();
        previous.put("scale", "spline16");
        // 此刻 mpv 里的当前值是**我们上次写进去的**值。若不看账本就会把它当成原值。
        Map<String, String> native_ = new LinkedHashMap<>();
        native_.put("scale", "ewa_lanczossharp");

        MpvQualityEnhanceRuntime.Plan plan = MpvQualityEnhanceRuntime.plan(
                previous, Map.of("scale", "ewa_lanczos"), native_::get);

        assertEquals("spline16", plan.writes().get(0).original());
        assertEquals("spline16", plan.bookkeeping().get("scale"));
    }

    @Test
    public void turningEveryTierOffRestoresTheCapturedOriginals() {
        Map<String, String> previous = new LinkedHashMap<>();
        previous.put("scale", "spline16");
        previous.put("deband", "no");

        // target 为空 = 用户把增强全部关掉。
        MpvQualityEnhanceRuntime.Plan plan = MpvQualityEnhanceRuntime.plan(
                previous, Map.of(), name -> "should-not-be-read");

        assertTrue(plan.writes().isEmpty());
        assertEquals(2, plan.restore().size());
        Map<String, String> restored = new LinkedHashMap<>();
        for (MpvQualityEnhanceRuntime.Entry entry : plan.restore()) {
            restored.put(entry.name(), entry.value());
        }
        assertEquals("spline16", restored.get("scale"));
        assertEquals("no", restored.get("deband"));
        assertTrue(plan.bookkeeping().isEmpty());
    }

    @Test
    public void aKeyThatIsStillWantedIsNotAlsoRestored() {
        Map<String, String> previous = new LinkedHashMap<>();
        previous.put("scale", "spline16");
        previous.put("deband", "no");

        MpvQualityEnhanceRuntime.Plan plan = MpvQualityEnhanceRuntime.plan(
                previous, Map.of("scale", "ewa_lanczossharp"), name -> null);

        // 只还原本次不再生效的那个键。
        assertEquals(1, plan.restore().size());
        assertEquals("deband", plan.restore().get(0).name());
        assertEquals(1, plan.writes().size());
        assertEquals("scale", plan.writes().get(0).name());
    }

    @Test
    public void namesAreCaseInsensitiveSoBookkeepingCannotDrift() {
        Map<String, String> previous = new LinkedHashMap<>();
        previous.put("Scale", "spline16");

        MpvQualityEnhanceRuntime.Plan plan = MpvQualityEnhanceRuntime.plan(
                previous, Map.of("SCALE", "ewa_lanczossharp"), name -> "unused");

        assertEquals("spline16", plan.bookkeeping().get("scale"));
        assertTrue(plan.restore().isEmpty());
    }

    @Test
    public void blankNamesAndValuesAreIgnored() {
        Map<String, String> target = new LinkedHashMap<>();
        target.put("", "yes");
        target.put("scale", "");
        target.put("  ", "  ");

        MpvQualityEnhanceRuntime.Plan plan = MpvQualityEnhanceRuntime.plan(
                Map.of(), target, name -> "spline16");

        assertTrue(plan.isEmpty());
    }

    @Test
    public void nothingToDoPlanIsEmptySoNoNativeCallHappens() {
        MpvQualityEnhanceRuntime.Plan plan = MpvQualityEnhanceRuntime.plan(
                Map.of(), Map.of(), name -> "spline16");
        assertTrue(plan.isEmpty());
        assertTrue(plan.bookkeeping().isEmpty());
    }

    @Test
    public void nullArgumentsDoNotThrow() {
        assertTrue(MpvQualityEnhanceRuntime.plan(null, null, null).isEmpty());
        assertTrue(MpvQualityEnhanceRuntime.plan(null, Map.of("scale", "ewa_lanczos"), null)
                .bookkeeping().containsKey("scale"));
    }

    /**
     * 交叉校验：{@code build()} 发出的每个选项名都必须有可还原的默认值。
     *
     * <p>这两张表分属两个类，很容易一边加了选项另一边忘了补默认值。真到那时，还原会因为
     * 找不到默认值而静默跳过——用户关掉增强后配置却被永久改掉，且没有任何日志。
     */
    @Test
    public void everyEmittedOptionCanBeRestored() {
        for (String vo : new String[]{"gpu", "gpu-next"}) {
            for (int mode = MpvQualityEnhanceOption.MODE_OFF;
                    mode <= MpvQualityEnhanceOption.MODE_STRONG; mode++) {
                for (int denoise = MpvQualityEnhanceOption.DENOISE_OFF;
                        denoise <= MpvQualityEnhanceOption.DENOISE_STRONG; denoise++) {
                    for (String name : MpvQualityEnhanceOption.build(mode, denoise, vo).keySet()) {
                        assertTrue("没有可还原的默认值: " + name,
                                MpvQualityEnhanceRuntime.hasDefault(name));
                    }
                }
            }
        }
    }

    @Test
    public void managedNamesCoverEveryTierOption() {
        for (String name : MpvQualityEnhanceOption.build(
                MpvQualityEnhanceOption.MODE_STRONG,
                MpvQualityEnhanceOption.DENOISE_STRONG,
                "gpu").keySet()) {
            assertTrue("账本未管理: " + name, MpvQualityEnhanceRuntime.managedNames().contains(name));
        }
        assertFalse(MpvQualityEnhanceRuntime.hasDefault(null));
        assertFalse(MpvQualityEnhanceRuntime.hasDefault("definitely-not-an-option"));
    }
}
