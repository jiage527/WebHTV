# T4 播放界面新增「超分」按钮

Task ID: `T4-play-ui-quality-enhance-button`
Lane: quick-fix
Base HEAD: `2797460885e9a3e456571da8c8a803f5d25c3b1f`
Date: 2026-11-09

## 一、需求

用户原话：「顺便在播放界面里面加入超分的开关」。

即：不必进「设置 → 播放性能 → 超分/锐化」，在**播放控制栏**就能直接调超分档位。

## 二、现状

超分/锐化此前只能通过 `PlaybackPerformanceDialog`（设置 → 播放性能）调整。
控制栏已有 `LUT`、`A-B`、`截图`、`GIF` 等同类按钮，且都有「用户可在
「播放器按钮配置」里自行显示/隐藏 + 排序」的机制（`PlayerButtonSetting`）。

## 三、实现

### 3.1 按钮注册

`PlayerButtonSetting` 新增 `QUALITY_ENHANCE = "quality_enhance"`，并加入 `DEFAULT` 列表
（位置紧跟 `LUT`）。由此**自动获得**既有能力：按钮配置对话框里可排序、可隐藏，
`getTotalCount()` 等统计同步。

### 3.2 档位循环与既有语义完全一致

复用 `MpvQualityEnhanceOption.nextMode`（**取模回绕**），与设置页走同一个方法：

```
关闭 → 轻度 → 强化 → 关闭
```

`nextMode` 正是上一轮修复「调不回关闭」缺陷时引入的方法，因此控制栏与设置页
**共用同一条已验证的回绕逻辑**，不会出现两边行为不一致。

### 3.3 生效路径复用既有链路

```
按钮点击 → MpvQualityEnhanceSetting.putMode(nextMode(...))
        → ConfigEvent.playerPerformance()
        → PlaybackService:277 player.applyPerformanceSettings()
        → 对 MPV 走 rebuildAndRestartMpv（保留进度与播放状态）
```

这条链路与设置页 `PlaybackPerformanceDialog.refresh()` 完全相同。

### 3.4 可见性：仅 MPV 且尊重用户隐藏偏好

超分作用于 GPU 着色器阶段（`vo=gpu`/`gpu-next`），Surface 直出
（`mediacodec_embed`）时完全不经过该管线。所以按钮与既有 `A-B` 按钮同构：

```java
boolean available = player().isMpv() && PlayerButtonSetting.isVisible(QUALITY_ENHANCE);
```

非 MPV 内核时按钮隐藏，避免「点了没反应」——这正是
`MpvQualityEnhanceOption.isSupported` 早已确立的原则。

### 3.5 高负载提示

强化档触发 `modeNeedsWarning` 时提示
`play_quality_enhance_heavy`，与设置页文案一致（工单要求「TV 端算力有限需给性能提示」）。

### 3.6 改动文件

- `PlayerButtonSetting.java`：新增常量 + DEFAULT 项
- `mobile/leanback VideoActivity.java`：点击监听、注册、文本刷新、可见性
- `mobile/leanback ControlDialog.java`：底部控制面板转发（点击 + 文本 + 可见性）
- `mobile/leanback view_control_vod_action.xml`、`dialog_control.xml`：按钮视图
- 三份 `strings.xml`：`play_quality_enhance` / `_light` / `_strong` / `_heavy`
- leanback `ControlDialog`：加入遥控器焦点链（`bindRemoteFocus`、`focusInitialControl`）

## 四、验证

- `:app:compileMobileArm64_v8aDebugJavaWithJavac :app:compileLeanbackArm64_v8aDebugJavaWithJavac` → **BUILD SUCCESSFUL**（两端）
- 布局完整性核对：`abLoop` / `lut` 出现的**全部**布局均已同步新增按钮（4 个文件各 1 处），
  且 `view_control_vod_action.xml` 仅有 mobile / leanback 两个变体，无遗漏变体
- `--tests "com.fongmi.android.tv.player.mpv.*" --tests "com.fongmi.android.tv.setting.*"`
  → 425 tests，**3 failed**，失败项为
  `MpvPreloadControllerTest`(2) 与 `MpvConfigStoreTest`(1)，
  **与既有基线完全一致**，且这两个类不在本单改动范围内（本轮未触碰）。

### 未做（明确记录）

- **未真机验证**：需用户在手机上确认按钮出现在控制栏、点击可循环、画质有变化。
- 未新增单测：`PlayerButtonSetting` 依赖 `R.string` 与 `Prefers`（Android 环境），
  仓库现有测试中亦无该类测试，保持一致而不额外引入 Robolectric。
- 未改按钮默认位置以外的排序：沿用 `applyOrder` 的既有语义，
  用户已有的自定义排序不受影响（新按钮追加在末尾）。

## 五、回滚

`git revert <本单提交>`；按钮常量与布局节点可独立移除，不影响既有按钮。
