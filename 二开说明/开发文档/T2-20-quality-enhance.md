# T2-20 超分 / 锐化 / 降噪

工单来源：`02-WebHTV-优化与新增功能报表.html` 条目 **B-11**（B2 画质与 HDR 增强）
优先级 P2 · 5 人日 · 前置依赖 —

工单原文：

> 超分 / 锐化 / 降噪　P2
> MPV 侧可挂 vd-lavc-o 与 libplacebo shader（FSRCNNX / Anime4K）。TV 端算力有限，建议做成可选开关并给性能提示。

---

## 1. 现状核实（全部经随包二进制与源码实证）

### 1.1 随包 libmpv 的能力边界

对 `app/src/arm64_v8a/assets/mpv-libs/arm64-v8a/libmpv.so` 与
`app/src/armeabi_v7a/assets/mpv-libs/armeabi-v7a/libmpv.so` 两份二进制做 NUL 分隔
选项名表逐项核对，**双 ABI 结果一致**：

| 项 | 存在 | 说明 |
|---|---|---|
| `libplacebo` 符号与字符串 | **是** | 静态链接进 `libmpv.so`，非外部依赖 |
| `glsl-shaders` / `glsl-shader` / `glsl-shader-opts` | **是** | 支持挂载外部 shader |
| `user-shaders` | **否（命中 0）** | 工单提到的这个选项名在本包中不存在 |
| `deband` | **是** | 顶层开关（与 `glsl-shader` 同一选项名表中） |
| `deband-iterations` / `deband-threshold` / `deband-range` / `deband-grain` | **否（均命中 0）** | 这些子选项在本包中不存在 |
| `sharpen` | **是** | 配套字符串 `unsharp masking` |
| `scale` / `cscale` / `dscale` / `tscale` | **是** | 内置缩放核可调 |
| `scale-antiring` / `cscale-antiring` | **是** | 抗振铃 |
| `correct-downscaling` / `linear-downscaling` / `sigmoid-upscaling` | **是** | |
| `scaler-resizes-only` | **是** | |
| `temporal-dither` / `dither-size-fruit` / `dither-depth` | **是** | |
| `ewa_lanczossharp` / `ewa_lanczos4sharpest` / `spline36` / `mitchell` / `catmull_rom` / `oversample` | **是** | libplacebo 内置缩放核 |
| `gpu-next` / `gpu` | **是** | |
| 仓库内 `.hook` / `.glsl` / `FSRCNNX*` / `Anime4K*` 资源 | **否（`find app/src` 为空）** | 未随包提供任何 shader 文件 |

**关键结论**：libplacebo **是**静态链接可用的，但**没有任何 shader 资源文件**，
且工单提到的 `user-shaders` 选项名不存在。要真挂 FSRCNNX / Anime4K，必须把第三方
shader 打进 APK，并且其 GLSL 能否在本机这两套 shader 编译器下编译通过**无法离线确认**
（编译失败的表现是黑屏或退回默认，不会给出可用提示）。

### 1.2 选项写入的静默失败风险

`app/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java:5905`：

```java
private void setOption(String name, String value) {
    if (value == null) value = "";
    try {
        mpvSetOptionString(name, value);
    } catch (Throwable ignored) {
    }
}
```

**选项名写错、值非法时不会报错，只是静默不生效。** 用户打开「超分」后画质毫无变化，
且日志里也没有任何线索——这是本单最难排查的风险，也是把选项生成收敛为**可单测纯逻辑**
的直接原因（见 §2.1、§3）。

### 1.3 生效链路

增强选项全部属于 GPU 着色器阶段参数，只有 `vo=gpu` / `vo=gpu-next` 时才经过这条管线。
`MpvPlayerEngine.buildConfig()`（`MpvPlayerEngine.java:971`）中存在第三条链路：
`surfaceDirect` 时 `vo=mediacodec_embed`，画面由 MediaCodec 直接送到 Surface，
**完全不经过我们的渲染管线**。此时发这些选项不但无效，还会让用户误判开关失效。

---

## 2. 设计取舍

### 2.1 三个方案的比较

| 方案 | 收益 | 缺点与风险 | 决定 |
|---|---|---|---|
| **A. 不改** | 零风险 | 工单诉求未满足；大屏上低分辨率片源的糊、色带问题依旧 | 否 |
| **B. 照工单原样挂 FSRCNNX / Anime4K `.hook`** | 理论画质最好 | ① 仓库**无任何 shader 资源**，需引入第三方代码（Anime4K 全套数 MB）并承担其许可与体积成本；② 随包 `user-shaders` 选项名不存在；③ GLSL 是否能在本机双编译器下编译通过**无法离线验证**，失败即黑屏；④ 净值：为一个 P2 项引入不受控的第三方资源与体积 | 否 |
| **C. 窄适配：用 libplacebo 内置缩放核 + `sharpen` + `deband`，关闭时逐字节等同旧行为** | ① 零新增资源、零新增依赖、零第三方代码；② 选项名**全部经双 ABI 实证存在**；③ 锐化/去色带是真实可见的画质收益；④ 可单测钉住选项名与取值范围 | ① 画质上限低于真 FSRCNNX（无神经网络超分）；② 会增加 GPU 负载 | **采用** |

**为什么 C 的收益是真实的**：`scale=spline36`（默认）→ `ewa_lanczossharp` 是
从「中等质量核」换到「EWA 锐利核」，在大屏上边缘锐度差异肉眼可辨；
`deband=yes` 针对的是天空/暗部渐变色带——这恰是大屏上最容易被看出的画质缺陷。
两者都不需要任何外部资源。

### 2.2 默认关闭

增强选项显著增加 GPU 负载。**默认全关**：老设备不能因为一次升级就突然变卡。
由用户显式开启，符合可预期原则。

### 2.3 关闭时必须「一个选项都不发」

`build()` 在 `MODE_OFF` + `DENOISE_OFF` 时返回**空 map**，调用方直接跳过设置循环。
这保证「关闭」与旧版本行为**逐字节一致**——不是发一堆空值再让 mpv 忽略。

### 2.4 性能提示（工单明确要求）

工单要求「TV 端算力有限需给性能提示」。实现为三层，均以
`MpvQualityEnhanceOption.needsPerformanceWarning(mode, denoise)` 为唯一判断依据
（「哪一档算高负载」只定义一处、可单测）：

1. **档位行值文本**：「强化（高负载）」「去色带+高精度（高负载）」。
2. **选择瞬间就地提示**：切到高负载档位时弹一次
   「已开启高负载画质增强，设备卡顿时请改回轻度或关闭」。提示出现在做出选择的瞬间，
   而不是藏在帮助页里让用户先去读说明。
3. **条目说明（帮助页）**：说明会增加 GPU 负载、可能掉帧，并给出处置建议。

### 2.5 与既有设置体系的关系

复用既有 `PlaybackPerformanceCatalog` / `PlaybackPerformanceDialog`：
该对话框已被 **mobile `SettingPlayerFragment` 与 leanback `SettingPlayerActivity` 共同使用**，
因此新增两个 catalog 条目即**双端自动获得 UI**，无需新增布局文件、无需新增菜单基础设施
（leanback 本身无 menu 基础设施，这是硬约束）。

新增设置只用 `Prefers` 存两个整数档位，**不做 DB 迁移**（项目硬约束）。

---

## 3. 实现清单

### 3.1 新增

| 文件 | 职责 |
|---|---|
| `app/src/main/java/com/fongmi/android/tv/player/mpv/MpvQualityEnhanceOption.java` | 纯逻辑：档位常量、`isSupported(vo)`、`build(mode,denoise,vo)`、`needsPerformanceWarning`、`describe`、`clamp*`。**不依赖 Android，可单测** |
| `app/src/main/java/com/fongmi/android/tv/setting/MpvQualityEnhanceSetting.java` | `Prefers` 持久化 + 双端共用文案 + `describeActive()` |
| `app/src/test/java/com/fongmi/android/tv/player/mpv/MpvQualityEnhanceOptionTest.java` | **23 项**回归测试 |

### 3.2 修改

| 文件 | 改动 |
|---|---|
| `player/engine/MpvPlayerEngine.java` | 新增 `applyQualityEnhanceOptions(builder, vo)`，在 `applySoftDecodeOptions` 之后调用；传入**实际解析出的 vo**（`mediacodec_embed` / `gpu-next` / `gpu`），非 GPU 链路直接 return |
| `setting/PlaybackPerformanceCatalog.java` | 新增 `MPV_QUALITY_ENHANCE` / `MPV_QUALITY_DENOISE` 两个常量与两条目（均归入「解码与渲染」段） |
| `ui/dialog/PlaybackPerformanceDialog.java` | 两个 `optionValue` 分支 + 两个 `optionAction` 分支（循环递增档位）+ `enhanceText(...)` 高负载后缀 + `warnQualityEnhance()` 选择时提示 |

### 3.3 各档位实际发出的选项

轻度（`scale` 等）：

```
scale=spline36  cscale=spline36  scale-antiring=0.5  cscale-antiring=0.5
dscale=mitchell  correct-downscaling=yes  scaler-resizes-only=no  sharpen=0.2
```

强化：`scale`/`cscale` → `ewa_lanczossharp`，antiring → `0.7`，
额外 `sharpen=0.5`、`sigmoid-upscaling=yes`。

去色带：`deband=yes`、`temporal-dither=yes`。
高精度：额外 `dither-size-fruit=8`。

**所有选项名与取值均已对双 ABI 的 `libmpv.so` 核对存在**（脚本化断言见测试）。

---

## 4. 验证

| 项 | 结果 |
|---|---|
| 双端编译 `:app:compileMobileArm64_v8aDebugJavaWithJavac :app:compileLeanbackArm64_v8aDebugJavaWithJavac` | **BUILD SUCCESSFUL** |
| 定点测试 `MpvQualityEnhanceOptionTest` | **23/23 通过**（tests=23 skipped=0 failures=0 errors=0） |
| 全量单测 | **2151** 项，失败 **41** 项 —— 与干净 HEAD **完全一致**的既有环境失败 |

既有 41 项失败（与本单无关，均为 `android.os.SystemClock not mocked` 环境问题）：
`player.exo.ExoCompressedAudioDirectPolicyTest` 35、`androidx.media3.extractor.Avs3ExtractionTest` 3、
`player.mpv.MpvPreloadControllerTest` 2、`player.mpv.MpvConfigStoreTest` 1。

测试数变化：T2-18 后 **2128** → T2-20 后 **2151**（+23）。

### 4.1 测试覆盖的关键风险点

1. **关闭不产生任何选项**（含 `gpu` 与 `gpu-next` 两种 vo）。
2. **`mediacodec_embed` 即使全开也不产生任何选项**——电视直出不经过着色器管线。
3. **绝不发出随包 mpv 不存在的选项名**：显式断言不含
   `deband-iterations` / `deband-threshold` / `deband-range` / `deband-grain` /
   `user-shaders` / `unsharp` / `hqdn3d` / `atadenoise`。
   这些名字被 mpv 静默忽略，只能靠测试拦住。
4. **每个发出的选项名都在白名单内**，防止以后有人顺手加回无效选项。
5. **锐化上限 0.5**：更高在小屏上会出现明显白边。
6. **越界档位被 clamp**，且 clamp 后的行为等同边界档位——设置被外部改坏时不会产生发疯的组合。
7. **顺序确定性**：相同输入两次调用 key 顺序一致，保证日志与选项对比有意义。
8. vo 匹配忽略大小写与首尾空白。
9. **高负载提示判定**：轻度/轻度不提示；任一强化档都提示（`needsPerformanceWarning`）。

---

## 5. 已知限制与残留风险

1. **无真机验证**：本机无连接设备。以下需真实环境实测：
   - 强化档在具体盒子/手机上的实际帧率与发热；
   - `sharpen=0.5` 在不同片源上是否会引入可见白边（当前 0.5 为保守上限）；
   - `deband` 对已高度压缩片源是否会把噪点抹成涂抹感；
   - 电视直出（`mediacodec_embed`）链路上 UI 开关无效果——**这不是缺陷而是物理限制**
     （画面不经过渲染管线）。当前实现在该情况下会记一条 `mpv quality enhance skipped vo=...`
     debug 日志，便于定位「开了没反应」的反馈。
     仍可补强之处：在设置行上根据当前输出模式直接标注「当前输出模式下不生效」，
     让用户不必去翻日志。
2. **未引入神经网络超分**：本单只做内置缩放核 + 锐化 + 去色带，画质上限低于真 FSRCNNX/Anime4K。
   若要真上 shader，需要单独评估第三方资源的许可、体积（数 MB）与 GLSL 兼容性。
3. **未调整 `vd-lavc-o`**：工单提到该路径，但它属解码器选项层，与本次「渲染后处理」是两条不同的链路，
   且会影响解码兼容性。为避免与既有软解降负载（`vd-lavc-fast` 等）设置相互干扰，本单不动它。
4. `dither-size-fruit=8` 的取值按 mpv 语义（LUT 尺寸，默认 6、上限 8）设定，未在真机验证观感差异。

---

## 6. 回滚

本单为**新增功能 + 双端设置项**，关闭开关即等同旧行为（`build()` 返回空 map，
不发任何选项）。如需完全回滚：提交内容集中在 §3 的 5 个文件，
`git revert <commit>` 即可，无数据迁移、无设置键依赖（残留的两个 `Prefers` 键在旧版本中不会被读取）。
