# P12 MPV 画质增强：切档即时生效 + 让效果可见

- 任务 ID：`P12`（upstream-integration-governor 的 MPV 家族；`P0…P11` 已用，`P12` 未占用）
- 关联工单：`T2-20`（画质增强首次交付）、`T3`（档位循环修复）、`T4`（播放页按钮）
- 用户诉求（原话，2026-11-13）：
  - 「那个MPV播放器不是带超分按钮的吗？那个抽风功能每次点击都要重新下载，并且好像没什么效果，你先给我解释一下，然后给出方案，我再决定要不要做？」
  - 「，是超分功能，每次点击都要从头加载视频，然后跳到当前播放进度」
  - 「并且好像没什么效果 没什么效果的同时，的确会增加播放器或是对系统的负载」
  - 决策：「就a和b吧」——只做方案 A + 方案 B；不做 C（第三方 shader）、不做 D（撤掉按钮）。
- lane：`upstream`（改动落在 vendored 的 `androidx.media3.mpvplayer` 源码）

## 1 问题拆成两件互不相同的事

| # | 现象 | 属于哪个方案 |
|---|---|---|
| 1 | 每点一次按钮，"从头加载 + 跳回进度" | A |
| 2 | 几乎没有画质变化，负载却上升 | B |

A 与 B 的根因不同，必须分开解决；只修 A 会让"没效果"更容易被归咎于程序。

## 2 根因（逐条有源码/字节码依据）

### 2.1 A 的根因：档位只能在下发配置时生效，所以只能重建播放器

链路（全部核对过行号）：

1. 播放页按钮 → `VideoActivity.onQualityEnhance()` → `MpvQualityEnhanceSetting.putMode(nextMode(...))` →
   `ConfigEvent.playerPerformance()`（`app/src/mobile/.../VideoActivity.java:1279-1290`、leanback 同构 `:1129-1140`）。
2. `PlaybackService.onConfigEvent` → `player.applyPerformanceSettings()`（`service/PlaybackService.java:274-287`）。
3. `PlayerManager.applyPerformanceSettings()` → `rebuildAndRestartMpv(null, "performance-settings-changed")`（`player/PlayerManager.java:4825-4846`）。
4. `rebuildAndRestartMpv` → `rebuildPlayer()` → `engine.start(spec.checkUa(), position, wasPlayWhenReady)`（`player/PlayerManager.java:4874-4906`）。
5. `MpvPlayerEngine.rebuild` → `player.release(); player = buildPlayer(listener)`（`player/engine/MpvPlayerEngine.java:113-117`）。

即：**选项只在 `buildConfig()`（`:1013`）里经 `MpvPlayerConfig.Builder` 下发，`MpvPlayer` 没有公开的"运行时改渲染参数"入口**，
所以换档只能把整个 `MpvPlayer` 拆掉重建。重建会丢掉 demuxer 缓冲 / 已解码帧 / 读位，
用户看到的"重新下载"就是这个。

### 2.2 A 在框架层是可做的（不是猜测）

- `vo_gpu_next.c:1176-1202 update_options()` 每帧调 `m_config_cache_update(p->opts_cache)` 与
  `m_config_cache_update(p->next_opts_cache)`，`changed` 时调 `update_render_options(vo)`；
  `update_options` 在 `:1364`、`:1932`、`:2217` 被调。
- `video.c:574-575`：`gl_video_conf`（`scale`/`cscale`/`dscale`/`deband`/`dither`/`scaler-resizes-only` … 所在表）
  的 `.change_flags = UPDATE_VIDEO`。
- `vo_gpu_next.c:2863-2931 update_render_options()` 把 `opts->scaler_resizes_only`、`map_scaler(...)`、
  `opts->deband`、`opts->dither_size`、`opts->temporal_dither` 等逐一搬进 libplacebo 参数。

⇒ 运行时改这批属性会即时生效，**不需要重建播放器、不重拉流、不丢进度**。

### 2.3 B 的根因：现有两档里有多条"等于或劣于默认值"的项，且最关键的一条在 gpu-next 下无效

`gl_video_opts_def`（`video.c:364-393`）实测默认值：

| 选项 | mpv 默认 | 现有代码 | 结论 |
|---|---|---|---|
| `scaler-resizes-only` | **true** | 写 `no` | 把默认改掉了：1:1 也走重型核 → **只在放大时有收益、代价在所有场景**（用户"没效果但更卡"的直接来源） |
| `correct-downscaling` | **true** | 写 `yes` | 冗余（无害，白写） |
| `linear-downscaling` | **true** | 未写 | — |
| `sigmoid-upscaling` | **true** | 强化档写 `yes` | **冗余**，让选项表看起来比实际丰富 |
| `scale` | `lanczos` | 轻度 `spline36` | **弱于默认** |
| `dscale` | `hermite` | `mitchell` | 略有差别 |
| `scale-antiring` | 0 | 0.5 / 0.7 | 真实生效 |

`--sharpen` 的致命一条：

- `options.rst`：「… **(Only for `--vo=gpu`)**」；
- 对 `vo_gpu_next.c` 全文 grep `unsharp|sharpen` ⇒ **0 命中**（gpu-next 从不读它）；
- 对照 `video.c:2315`：`if (p->opts.unsharp != 0.0) { … .hook = unsharp_hook … }` —— 只有旧 `gpu` 路径挂这个 hook。

⇒ 现有两档之间**唯一真正的差别就是 `sharpen` 0.2 → 0.5**，而它在 gpu-next 下被静默忽略；
轻度档的 `spline36` 还比默认的 `lanczos` 更钝。这就是"开了没效果"。

### 2.4 一条需要更正的旧结论（方法学错误）

`docs/T2-20-quality-enhance.md` 与 `MpvQualityEnhanceOptionTest.neverEmitsOptionNamesMissingFromBundledMpv`
断言 `deband-iterations` / `deband-threshold` / `deband-range` / `deband-grain` 在随包 libmpv 中**不存在**。

实际是：mpv 的子选项名由配置系统在**运行时**用「父选项名 + `-` + 子字段名」拼出，
字面串 `deband-iterations` 本来就不会出现在二进制里。依据：

- `video.c:558`：`{"deband", OPT_SUBSTRUCT(deband_opts, deband_conf)}` —— 子选项结构在编译进包的代码里；
- `options.rst:6978-6997` 正式文档化了 `--deband-iterations=<0..16>`、`--deband-threshold=<0..4096>`、
  `--deband-range=<1..64>`、`--deband-grain=<0..4096>`；
- 同一 NUL token 扫描里 `iterations`/`threshold`/`range`/`grain` 四个字段名**都在**。

⇒ 旧结论的**检索方法**不成立（裸子串命中 0 不能推出选项不存在）。本单不依赖这四个选项，
但该结论与那条测试断言的措辞需要更正，避免以后继续以它为据。

## 3 方案（用户已批准的 A + B）

### 3.1 A：加一条运行时下发通道，切档不再重建

1. `MpvPlayer`（vendored 源码，可改）新增 public 入口，仿现有 `applyAutoCacheBaseline` / `applyAutoHlsBitrate`
   的既有形态；内部走已有的 private `setRuntimeStringChecked`（= `mpvSetPropertyString(name, value) >= 0`）。
   **返回值必须如实反映"原生是否接受"**，不接受要留日志 —— 这类选项的静默失败正是本单最难查的一段历史。
2. **首次下发前保存原值，关闭时回写**（沿用同文件 `hlsAdOriginalKeepOpen` 等既有"存原值再还原"的做法），
   这样即使用户在 `mpv.conf` 里自己设过缩放核，关掉增强也不会被我们永久改掉。
3. `MpvPlayerEngine` 暴露一个方法：算出选项 → 下发；返回**"运行时这条路是否已处理（因此不需要重建）"**。
   - 真实 vo 用 `player.getObservedCurrentVideoOutput()`（`isSurfaceDirect()` 已在用），而不是配置期的猜测值。
   - 电视直出（`mediacodec_embed`）不过着色器管线 ⇒ 返回"未处理"，让调用方维持旧路径。
4. `PlayerManager` 转发一层给两端 `VideoActivity`。
5. 两端 `onQualityEnhance()`：**先试运行时；返回"未处理"才回落到 `ConfigEvent.playerPerformance()`**。
   这样任何情况下都不会比现在更差。

### 3.2 B：删掉等于默认/劣于默认的项，让梯度真实存在

- 删除 `scaler-resizes-only=no`（还默认值 = 1:1 时不再白耗 GPU）。
- 删除冗余的 `correct-downscaling=yes`、`sigmoid-upscaling=yes`。
- 真实梯度（轻度 → 强化）：
  - `scale` / `cscale`：`spline36` → `ewa_lanczossharp`
  - `scale-antiring` / `cscale-antiring`：`0.5` → `0.7`
  - 强化档追加 `scale-blur`（`<1` 更锐，文档建议落在 0.8–1.2 内）
- `sharpen` **只在 `vo=gpu` 下发**（gpu-next 下它被静默忽略；继续发就是在制造"看起来设了但没生效"的假象）。

### 3.3 已知边界（写清楚，不靠报告遮掩）

- **1:1 显示时没有可信办法加细节**：源分辨率 == 屏幕分辨率时缩放器无事可做；
  唯一与实际分辨率无关的锐化是 `--sharpen`，而它只在 `vo=gpu` 存在。
  ⇒ 这种内容上"看不出差别"是物理限制，不是开关坏了。
- 电视直出仍然无效（着色器管线根本不经过），维持既有日志。
- 本单**不引入**第三方 shader（C 已否决），**不移除**按钮（D 已否决）。

## 4 验证计划

- 纯 JVM 单测：`MpvQualityEnhanceOptionTest` 同步更新（选项集变化 + `sharpen` 的 vo 门禁 +
  "不再发出等于默认值的项"）；新增"存原值/还原"的纯逻辑测试。
- 编译双 flavor；全量单测与既有 41 项基线对比，**不得新增失败**。
- 出包并交付用户手动实测（用户明确：实机验证他自己做）。
- 真机需要用户配合：在**源分辨率与屏幕不同的内容**上切档对比，并回报是否有"卡一下"。

## 5 回滚

单次原子提交 + recovery tag；`MpvQualityEnhanceOption.build()` 与 `MpvPlayer` 新入口相互独立，
若运行时通道有问题，把 `VideoActivity` 的回落条件放宽即可恢复到"每次都重建"的旧行为。
