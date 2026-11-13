package com.fongmi.android.tv.player.anime4k;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.player.mpv.MpvQualityEnhanceOption;

import org.junit.Test;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 超分链的定义与资源完整性。
 *
 * <p>这里刻意只测两件能离线证明的事：链本身（顺序、档位映射、开关）和
 * 「链里点到的每个文件都真的打进包了」。至于 GLSL 能不能被 libmpv 编译，
 * 只有真机能回答——本地无 GPU，测不了就不假装测过。
 */
public class Anime4KShaderChainTest {

    private static final String ASSET_DIR = "src/main/assets/anime4k";

    @Test
    public void offHasNoChain() {
        assertTrue(Anime4KShaderChain.names(MpvQualityEnhanceOption.MODE_OFF).isEmpty());
        assertFalse(Anime4KShaderChain.needsShaders(MpvQualityEnhanceOption.MODE_OFF));
        assertTrue(Anime4KShaderChain.assetPaths(MpvQualityEnhanceOption.MODE_OFF).isEmpty());
    }

    @Test
    public void bothTiersCarryAChain() {
        assertTrue(Anime4KShaderChain.needsShaders(MpvQualityEnhanceOption.MODE_LIGHT));
        assertTrue(Anime4KShaderChain.needsShaders(MpvQualityEnhanceOption.MODE_STRONG));
        assertFalse(Anime4KShaderChain.names(MpvQualityEnhanceOption.MODE_LIGHT).isEmpty());
        assertFalse(Anime4KShaderChain.names(MpvQualityEnhanceOption.MODE_STRONG).isEmpty());
    }

    @Test
    public void anUnknownTierIsTreatedAsOff() {
        assertTrue(Anime4KShaderChain.names(99).isEmpty());
        assertTrue(Anime4KShaderChain.names(-1).isEmpty());
        assertEquals("off", Anime4KShaderChain.tierName(99));
    }

    /**
     * 链的顺序是结果的一部分，不是实现细节：这些 pass 靠 {@code //!HOOK}/{@code //!BIND}
     * 前后衔接，把 Upscale 放到 Restore 前面会先放大再修边缘，画面明显劣化。
     * 上游 Anime4K 给的推荐顺序就是「先去环/钳制高光 → 再恢复 → 再放大」。
     */
    @Test
    public void clampComesFirstAndUpscaleComesLast() {
        for (int mode : new int[]{MpvQualityEnhanceOption.MODE_LIGHT, MpvQualityEnhanceOption.MODE_STRONG}) {
            List<String> names = Anime4KShaderChain.names(mode);
            assertEquals("回合开始必须先钳制高光，否则后续 pass 会放大过曝",
                    "Anime4K_Clamp_Highlights.glsl", names.get(0));
            String last = names.get(names.size() - 1);
            assertTrue("末位必须是 Upscale，x2 之后的残留缩放靠它收尾", last.contains("Upscale"));
        }
    }

    @Test
    public void efficiencyStaysOnTheSmallerNetworks() {
        List<String> names = Anime4KShaderChain.names(MpvQualityEnhanceOption.MODE_LIGHT);
        assertTrue("效率档不得使用 VL 超大网络，那是质量档的代价", names.stream().noneMatch(n -> n.contains("_VL")));
        assertTrue(names.contains("Anime4K_Upscale_CNN_x2_M.glsl"));
        assertTrue(names.contains("Anime4K_Restore_CNN_S.glsl"));
    }

    @Test
    public void qualityTierUsesTheLargeNetworks() {
        List<String> names = Anime4KShaderChain.names(MpvQualityEnhanceOption.MODE_STRONG);
        assertTrue(names.contains("Anime4K_Restore_CNN_VL.glsl"));
        assertTrue(names.contains("Anime4K_Upscale_CNN_x2_VL.glsl"));
    }

    /** 两档必须真的有区别，否则「切了档看不出变化」又要复现。 */
    @Test
    public void theTwoTiersAreNotTheSameChain() {
        assertFalse(Anime4KShaderChain.names(MpvQualityEnhanceOption.MODE_LIGHT)
                .equals(Anime4KShaderChain.names(MpvQualityEnhanceOption.MODE_STRONG)));
    }

    @Test
    public void assetPathsArePrefixedAndKeepOrder() {
        for (int mode : new int[]{MpvQualityEnhanceOption.MODE_LIGHT, MpvQualityEnhanceOption.MODE_STRONG}) {
            List<String> names = Anime4KShaderChain.names(mode);
            List<String> paths = Anime4KShaderChain.assetPaths(mode);
            assertEquals(names.size(), paths.size());
            for (int i = 0; i < names.size(); i++) {
                assertEquals(Anime4KShaderChain.DIRECTORY + "/" + names.get(i), paths.get(i));
            }
        }
    }

    /** 两档共用若干 pass，但登记时必须去重——重复名字会让上游文件清单出现同一项两次。 */
    @Test
    public void allNamesIsTheDeduplicatedUnion() {
        Set<String> seen = new HashSet<>(Anime4KShaderChain.allNames());
        assertEquals(seen.size(), Anime4KShaderChain.allNames().size());
        for (String name : Anime4KShaderChain.names(MpvQualityEnhanceOption.MODE_LIGHT)) assertTrue(seen.contains(name));
        for (String name : Anime4KShaderChain.names(MpvQualityEnhanceOption.MODE_STRONG)) assertTrue(seen.contains(name));
    }

    /**
     * 每条链点到的文件都必须在 {@code app/src/main/assets/anime4k/} 下真实存在。
     *
     * <p>这是本单最容易犯又最难在真机上定位的错：文件少打一个，mpv 会静默跳过那条 pass，
     * 画面照样出、日志也不报错——只是效果弱了一截。单测的工作目录是模块目录，
     * 所以能直接按路径校验。
     */
    @Test
    public void everyShaderInBothChainsIsBundled() {
        for (String name : Anime4KShaderChain.allNames()) {
            File file = new File(ASSET_DIR, name);
            assertTrue("缺少着色器资源：" + file.getPath(), file.isFile());
            assertTrue("着色器资源是空的：" + file.getPath(), file.length() > 0);
        }
    }

    @Test
    public void theLicenceTravelsWithTheShaders() {
        File licence = new File(ASSET_DIR, "LICENSE-ANIME4K.txt");
        assertTrue("Anime4K 是 MIT 许可，分发必须随附许可原文", licence.isFile());
        assertTrue(licence.length() > 0);
    }
}
