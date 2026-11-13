# P14 — 让 Anime4K 超分可验证 + 应用改名换图标

单号：P14（追加单，无工单编号）
日期：2026-11-14
前置：P13（`10a237cb`，内嵌 Anime4K 着色器并挂链）

## 1. 为什么有这一单

P13 交付后用户在真机上反馈「还是没有效果」，并连提四个疑点：开关是否触发、强度是否够、
是否真的和 Kazumi 一样、为什么 Kazumi 有效果。逐条实测后确认：

| 疑点 | 实测结论 |
|---|---|
| 打包是否完整 | APK 内 `assets/anime4k/*` 10 个条目逐个 SHA256 与源文件一致，**不是打包问题** |
| 开关是否触发 | `logcat` 抓到 `SLOW_MPV_CALL operation=command:change-list` 成串出现，**命令确实发到了 mpv** |
| 命令是否被执行 | 无法判断——`safeCommand()` 是 `try { mpvCommand() } catch (Throwable ignored) {}`，返回码与异常全被吞；mpv 对编译不过的着色器是**静默跳过**，所以「发了」与「跑了」在日志里长得一模一样 |
| 是否叠了别的处理 | 日志里同时出现 `shader chain applied anime4k=7` 与 `mpv quality enhance runtime vo=gpu-next written=5 scale=ewa_lanczos`，**两套缩放同时在跑** |
| 与 Kazumi 的差异 | Kazumi 挂上 Anime4K 后不再改 `scale`；我们还在发 `scale/cscale/dscale/scale-blur/scale-antiring`，两套互相抵消。效率档 vs 质量档同帧像素差仅 **0.028%**，而同档连拍噪声底是 **0%** ⇒ 档位差别被缩放核淹没了 |

本单三处改动分别对应第 3、4、5 行。

## 2. 改动

### 2.1 `MpvPlayer`：让命令的真相可见

- 新增 `private int commandOrLog(String[] command)`：调 `mpvCommand(...)`，返回 `result < 0` 时打
  `SpiderDebug.log("mpv", "shader command rejected result=%d op=%s", ...)`；抛异常时打
  `"shader command threw %s"` 并返回 -1。`applyShaderPipeline` 全部改用这个方法，
  统计 `failures`，末尾日志为
  `shader chain applied anime4k=%d lut=%s failures=%d native=%s`。
- 新增 `public String readNativeShaderChain()`（读 `glsl-shaders` 属性，即 **mpv 自己认下的链**）。
- 新增 `hasAnime4KShaders()`：`!appliedShaderPaths.isEmpty() && !anime4kShaderPaths.isEmpty()`。
  **刻意问「mpv 已接受的」而不是「我们想让它持有的」**——链没挂上却撤掉缩放核，
  等于两边都没有，画面比原来更差。
- 保留逐条 `change-list ... append/remove` 的下发方式，不用 `change-list glsl-shaders set`：
  该选项是 path list，Unix 上用 `:` 分隔，而绝对路径本身可能含 `:`，整串下发有歧义风险。

### 2.2 `MpvPlayerEngine.applySuperResolution`：读回核对

挂链之后立刻读回，数实际条目：

```java
String native_chain = player.readNativeShaderChain();
int landed = countChainEntries(native_chain);
SpiderDebug.log("player-engine",
        "super resolution applied vo=%s tier=%s requested=%d landed=%d native=%s", ...);
if (landed < paths.size()) {
    SpiderDebug.log("player-engine", "super resolution NOT fully mounted ...");
    return SuperResolutionResult.UNAVAILABLE;
}
```

这样「挂了 7 条却只生效 3 条」会在日志里直接暴露，而不是靠肉眼猜画面。

### 2.3 `MpvQualityEnhanceOption.build(mode, denoise, vo, anime4kActive)`

新增 4 参重载（原 3 参转调 `anime4kActive=false`，行为不变）。超分在跑时：

- **不发** `scale` / `cscale` / `dscale` / `scale-blur` / `cscale-blur` / `scale-antiring` /
  `cscale-antiring` / `sharpen` —— 这些与着色器做的是同一件事，叠着只会互相抵消；
- **照发** `deband` / `temporal-dither` / `dither-size-fruit` —— 降噪是着色器完全不碰的环节。

这与 Kazumi 一致：它挂上 Anime4K 后不再改 `scale`。

### 2.4 两端 `VideoActivity`（P13 已接入，本单未改）

`onQualityEnhance()` → `applySuperResolution()` 的调用链沿用 P13 的实现，
本单只是让它返回的 `APPLIED / UNAVAILABLE` 由读回核对决定。

## 3. 应用改名与换图标

- `app_name`：
  - 默认（英文）`My Video`
  - `values-zh-rCN` / `values-zh-rTW`：`我的影视`
  - `AndroidManifest.xml:62` 是 `android:label="@string/app_name"`，无需改 manifest。
- 通知渠道名：`Notify.createChannel()` 里原来是硬编码 `setName("TV")`，会出现在系统
  「设置 → 通知」里露出旧名，改为 `ResUtil.getString(R.string.app_name)`。
- 图标：由用户提供的圆形图（1294×1294，圆外透明）生成
  - `mipmap-{m,h,x,xx,xxx}dpi/ic_launcher.png`：48/72/96/144/192，圆外填外环同色 `#042E54`（不透明）
  - `mipmap-*/ic_launcher_round.webp`：同尺寸，圆外透明
  - `mipmap-*/ic_launcher_foreground.png`：108dp 画布（108/162/216/324/432），圆形占 **65%**
    （≈70dp，`72dp` 是启动器圆形遮罩的可见直径，留一点余量防止外环被裁）
  - `ic_launcher-playstore.png`：512×512
  - `mipmap-anydpi-v26/ic_launcher{,_round}.xml`：`background=@color/ic_launcher_background`
    （`#042E54`，采样自图标外环均值）/ `foreground=@mipmap/ic_launcher_foreground`
  - 删除原矢量 `drawable/ic_launcher_foreground.xml`（已被位图前景取代）

生成脚本：`gen_icons.py`（Pillow，圆形遮罩 8× 过采样做抗锯齿）。

## 4. 验证

- 双 flavor `compileJavaWithJavac`：`BUILD SUCCESSFUL`（1m 24s）。
- `MpvQualityEnhanceOptionTest`：**33 tests / 0 failures**（新增
  `anime4kActiveDropsScalerOptionsButKeepsDenoise`、`anime4kActiveAtOffModeChangesNothing`）。
- 双端 release 出包：`BUILD SUCCESSFUL in 7m 24s`。
- APK 内核对（`aapt2 dump resources` + zip 直读）：三组图标各 5 档齐全，
  尺寸 48/72/96/144/192 与 108/162/216/324/432 正确；`color/ic_launcher_background` 存在。
- 标签：`application-label:'My Video'`、`application-label-zh-CN:'我的影视'`。
- 真机（一加 ACE 3V / Android 16 / `c5229a1f`）安装后系统「应用详情」页显示
  **新图标 + 我的影视 + 版本 5.6.4**；冷启动 `TotalTime: 455`。

## 5. 局限（如实记账）

- **画质是否「看得出」仍需用户肉眼确认**。本机能证明的是「链挂上了、没有再被缩放核覆盖、
  命令返回码与落链条数都进了日志」，不能证明「用户觉得好看」。
- 超分需要 `gpu` / `gpu-next` 渲染器；硬解直出（`mediacodec_embed`）用不了，P13 已做成
  「退档 + 提示」。
- 未改电视端默认渲染器。
