# P13 MPV 超分：改用 Anime4K 神经网络着色器

- 任务 ID：`P13`（upstream-integration-governor 的 MPV 家族；`P0`–`P12` 已用，`P13` 未占用）
- 关联工单：`T2-20`（画质增强首次交付）、`T3`（档位循环修复）、`T4`（播放页按钮）、`P12`（切档即时生效 + 两档差异）
- lane：`upstream`（改动落在 vendored 的 `androidx.media3.mpvplayer` 源码与 MPV 引擎层，并新增第三方 GLSL 资产）
- 用户诉求（原话，2026-11-13）：
  - 「切换卡顿没有了 但是还是没有效果 Kazumi的安卓版的超分是真实有效的 你看看你们的实现方式有什么区别吗？」
  - 「现在我们这款软件上面的超分 俩个模式都没有效果」
  - 决策（ask_user_question）：**「照 Kazumi 路线做」** + 渲染器问题「不确定，你按能用的来」

## 1 P12 为什么做不到「看得出效果」

P12 只改 mpv 的**内置缩放核**（`scale`/`cscale`/`scale-blur`/`sharpen`/`deband`）。这类参数做的是**重采样**，
不是超分辨率：它没有引入任何新的高频信息，1080p 片源在 1080p 屏上（`scaler-resizes-only` 为默认 true 时不缩放）
几乎必然看不出差别。P12 已把两档差距调大（同族 `ewa_lanczos` ↔ `ewa_lanczossharp`、`scale-blur=0.9`），
但天花板就是重采样核的天花板。

真正的「超分」需要**在着色器里跑神经网络**重建边缘与线条，也就是 Anime4K。

## 2 与 Kazumi 的差异（逐项对照，源码为据）

Kazumi（`Predidit/Kazumi`，Flutter + media-kit/libmpv）的「超分」实现：

| 维度 | Kazumi | WebHTV（P12 之前） |
|---|---|---|
| 技术路线 | Anime4K 神经网络 GLSL，挂 `glsl-shaders` | 只改 mpv 内置缩放核 |
| 是否真超分 | 是（CNN 重建线条/边缘） | 不是（重采样 + 锐化） |
| shader 资产 | APK 内置 10 个 `.glsl`，运行时拷到私有目录 | 仓库里没有任何 `.glsl` |
| 切档方式 | `change-list glsl-shaders set/clr` 运行时换链 | 重建 `MpvPlayer`（P12 已改为运行时下发） |
| 硬解直出 | 检测到 `mediacodec_embed` 就弹兼容性提示 | 静默无效（开关开着但画面不变） |

Kazumi 的关键代码（下载自 `raw.githubusercontent.com/Predidit/Kazumi/main/...`，存于本地工作目录）：

- `lib/pages/player/controller/player_playback_controller.dart:480-521 setShader(...)`：
  `pp.command(['change-list','glsl-shaders','set', buildShadersAbsolutePath(dir, mpvAnime4KShaders)])`；
  关闭走 `['change-list','glsl-shaders','clr','']`。
- `lib/services/shaders/shader_asset_service.dart`：把 `assets/shaders/*.glsl` 从 AssetBundle 拷到
  `getApplicationSupportDirectory()/anime_shaders/`，再把**绝对路径**交给 mpv。
- `lib/utils/constants.dart` 的两条链（`mpvAnime4KShaders` 质量档 / `mpvAnime4KShadersLite` 效率档）。
- `lib/pages/player/player_item.dart:856-956 handleSuperResolutionChange(...)`：
  Android 且渲染器为 `mediacodec_embed` 时弹「兼容性提示」，**不启用**超分；质量档另有性能提示。
- 许可：Anime4K 为 **MIT（Copyright (c) 2019 bloc97）**，与 Kazumi 的 GPL-3.0 兼容，可内嵌分发。

## 3 本仓库具备条件（字节级核实，不是推测）

- 随包 `libmpv.so`（`app/src/arm64_v8a/assets/mpv-libs/arm64-v8a/libmpv.so`，17,820,224 B）存在
  **`glsl-shaders`**、`glsl-shader-opts` 选项名，且 `libplacebo`、`HOOKED_tex`、`HOOKED_pos`、
  `Registering hook pass`、`Error while parsing WHEN!` 等用户着色器解析器符号齐全。
- **项目内已有同类管线**：`player/lut/MpvLutShaderFactory.java:111-183` 用一个**运行时生成**的
  `//!HOOK MAIN` / `//!BIND HOOKED` / `//!TEXTURE` 用户着色器实现 LUT，已在真机交付运行；
  `androidx/media3/mpvplayer/MpvPlayer.java` 原有的 `applyShaderPipeline` 就是
  `change-list glsl-shaders append/remove`——**与 Kazumi 是同一机制**。
- ⇒ 把 Anime4K 的 `.glsl` 打进 APK、运行时落到私有目录、再把绝对路径 append 进
  `glsl-shaders`，在本仓库是完全可行的；不需要 JNI、不需要改原生二进制。

> 更正 P12 之前的旧结论：`docs/T2-20-quality-enhance.md` 与 `MpvQualityEnhanceOption` 类注释里
> 「随包 libmpv 无 `user-shaders` ⇒ 不能挂第三方 shader」是**错的**。`user-shaders` 本就不是这项的
> 选项名（正确名是 `glsl-shaders`），且「二进制里搜不到某字面串」不能推出「功能不存在」。

## 4 方案

### 4.1 资产（`app/src/main/assets/anime4k/`，共 407,178 B）

9 个 Anime4K v4.0 shader + `LICENSE-ANIME4K.txt`（MIT 原文）。来源为 Kazumi 仓库的
`assets/shaders/`（上游原作者 bloc97/Anime4K v4.0）。放 `main` 源集，两端 flavor 都带。

### 4.2 档位 → 链（`player/anime4k/Anime4KShaderChain.java`，纯逻辑）

顺序照搬上游推荐链，**顺序敏感**（`//!HOOK`/`//!BIND` 前后衔接）：

- 效率档：Clamp → Restore_CNN_M → Restore_CNN_S → Upscale_CNN_x2_M → AutoDownscalePre_x2/x4 → Upscale_CNN_x2_S
- 质量档：Clamp → Restore_CNN_VL → Upscale_CNN_x2_VL → AutoDownscalePre_x2/x4 → Upscale_CNN_x2_M
- 关闭：空链

### 4.3 落盘（`player/anime4k/Anime4KShaderStore.java`）

mpv 的 `glsl-shaders` 需要**真实文件系统路径**，`file:///android_asset/` 在 native 侧打不开。
首次使用时把 assets 逐个拷到 `filesDir/anime4k_shaders/`，按字节数校验复用；拷贝失败删掉半截文件。
**任一条落盘失败就整档放弃**（返回空表），不半途换链——避免出现「一半 shader」的画面劣化。

### 4.4 挂载（`MpvPlayer` / `MpvPlayerEngine` / `PlayerManager` / 两端 `VideoActivity`）

- `MpvPlayer.setAnime4KShaders(List<String>)`：重算整条链 = Anime4K 链（顺序敏感）+ LUT 放最后
  （色彩映射应作用在超分结果上）。整条换、不做差集，避免共用 pass 留在旧位置。
  **不用 `change-list glsl-shaders set`**：该选项是 path list，Unix 上用 `:` 分隔，绝对路径可能含 `:`，
  拼接有歧义；逐条 `remove`/`append` 与既有 LUT 代码同一套写法。
- `MpvPlayerEngine.applySuperResolution()`：算完 vo 与档位后下发，返回
  `APPLIED / DISABLED / NEEDS_GPU_RENDERER / UNAVAILABLE`。
- `MpvPlayerEngine.restoreSuperResolution(player)`：`buildPlayer` 里新 player 的链是空的，重建后补回。
  ⚠️ 只认传入的 player，**不能用读字段的 `currentVideoOutput()`**——它在字段赋值之前被调用。
- `PlayerManager.applySuperResolution()`：非 MPV 内核返回 `null`，调用方沿用各自内核原路径。
- `VideoActivity.onQualityEnhance()`：切档 → 下发；`NEEDS_GPU_RENDERER` 时**把档位退回关闭并弹提示**
  （与 Kazumi 同一做法），而不是把开关留在「已开启」而画面毫无变化；`APPLIED` 且质量档时给性能提示；
  只有整个运行时路径都接不住时才回落 P12 的 `ConfigEvent.playerPerformance()`（会重载媒体）。

## 5 最佳实践复审（AGENTS.md §8 要求）

| 类别 | 结论 |
|---|---|
| 上游源码/文档 | 逐条读 mpv 官方文档（`options.rst`/`vo.rst`）、gpu-next wiki、`vo_gpu_next.c`/`video.c`；确认 `glsl-shaders` 是用户着色器入口、`//!HOOK` 元数据语法、`sharpen` 仅 `vo=gpu` |
| 成熟同类项目 | Kazumi（Android，Flutter+libmpv）用同一套 Anime4K 资产与 `change-list glsl-shaders` 机制，本站调研其源码与资产清单 |
| 上游资产 | Anime4K v4.0（bloc97），MIT 许可，随 shader 一并分发许可原文 |
| 二进制核实 | 随包 `libmpv.so` 的选项名与用户着色器解析器符号逐条扫描确认 |
| 不适用项 | 无 GPU/无视觉的 CI 无法离线验证 GLSL 编译结果 ⇒ 不假装测过，改为交由用户真机对比观感 |

**没有引入新依赖**：只是一批 `.glsl` 资产 + 既有 `glsl-shaders` 通道。

## 6 已知边界

1. **硬解直出（`mediacodec_embed`）用不了着色器**——物理限制，Kazumi 同样如此。本单的处理是
   退档 + 明确提示，而非静默无效。电视端默认就是硬解直出，因此电视上大概率会看到该提示。
2. 本次**不**改电视端默认渲染器（那是另一项行为变更，需用户同意）。用户可按提示在播放设置里
   把渲染器切为 `gpu` 或 `gpu-next`。
3. 画质是否「看得出」需真机对比（本机无视觉能力，截图判读不可用）。
4. VL 档（质量档）在低端设备上可能掉帧——保留了既有的高负载提示。

## 7 验证

- 单测：`player/anime4k/Anime4KShaderChainTest`（11 例）——链定义、顺序、去重、两档不同、
  **链里点到的每个文件都真的在 `assets/anime4k/` 下存在且非空**、许可随行。
- 编译：双 flavor `compileJavaWithJavac`。
- 全量单测：与既有 41 项基线失败逐项比对，不得新增。
- 出包：双端 release APK；核对 `classes.dex`/assets 内确实带上了 shader。
- 真机观感：交由用户手动对比「关闭 / 效率档 / 质量档」。

## 8 回滚

单次提交可整体回退：删除 `app/src/main/assets/anime4k/` 与 `player/anime4k/` 两个类，
`MpvPlayer.setAnime4KShaders` 与链式拼接回退到只处理 LUT，`onQualityEnhance` 恢复 P12 行为。
