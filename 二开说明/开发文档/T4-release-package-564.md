# T4 出包：WebHTV 5.6.4（手机版）

Task ID: `T4-release-package-564`
Lane: quick-fix
Base HEAD: `70f8961d9a21ba053191a52fa1e5f3c0d8b919e6`
Date: 2026-11-09

## 一、本包内容

| 提交 | 内容 |
| --- | --- |
| `27974608` | MPV 音频直通失败自动回退（4K 无声 / 慢速源卡死） |
| `70f8961d` | 播放控制栏新增「超分」按钮 |

## 二、构建

`./gradlew :app:assembleMobileArm64_v8aRelease --offline` → **BUILD SUCCESSFUL in 6m 1s**
（172 actionable tasks）。构建后已 `rm -rf app/.cxx`。

## 三、包体检（不可伪造）

`aapt2 dump badging`：

```
package: name='com.fongmi.android.tv' versionCode='564' versionName='5.6.4'
application-label-zh-CN:'影视'
native-code: 'arm64-v8a'
```

- 体积 **139,326,791 字节**
- SHA-256 **`fabe355df88fc16655401f6d63290daee2e4b8abee48128616f8e6f47582105f`**
- 签名 `apksigner verify --verbose` → **Verifies**，v2 scheme = true
- 签名指纹 SHA-256 `0d282cf5ec4042bc29c1390584438423c22427b330abba1c52a162ef1050b910`
  （与 5.6.1 / 5.6.2 / 5.6.3 及 debug 基线**完全一致**，可覆盖升级旧包）

### 修复确实进包（R8 证据）

`mapping.txt`（`mobileArm64_v8aRelease/mapping.txt:219890`）显示新策略类被 R8 **内联移除**：

```
androidx.media3.mpvplayer.MpvAudioSpdifFailurePolicy -> R8$$REMOVED$$CLASS$$191
232566: void maybeDisablePassthroughOnAudioOutputFailure(java.lang.String):5653 -> w7
232568: boolean ...MpvAudioSpdifFailurePolicy.shouldInspect(...):45 -> w7
232571: boolean ...MpvAudioSpdifFailurePolicy.isAudioOutputInitFailure(...):56 -> w7
```

因此类名字符串不在 dex 中（这是内联的**预期结果**，不是缺失）。改用其**字面量常量**核对，
全部在包内 dex 命中：

| 常量 | dex 命中 |
| --- | --- |
| `could not open/initialize audio device` | 1 |
| `audio output initialization failed` | 1 |
| `failed to initialize audio driver` | 1 |
| `passthrough disabled after audio-output init failure` | 1 |
| `audio-spdif` | 1 |
| `quality_enhance`（新按钮 id） | 3 |

新增中文串在 `resources.arsc` 命中：`超分` 5、`超分·轻度` 1、`超分·强化` 1、
`已开启高负载画质增强` 1。

## 四、交付

`C:\Users\qw191\Desktop\WebHTV-APK-5.6.4\WebHTV-5.6.4-手机版-MPV无声修复+超分快捷开关.apk`

## 五、待真机验证（用户）

1. **4K 无声**：**保持「音频直通」开着**，播之前无声的 4K 源，确认有声且画面正常。
   预期：mpv 报音频输出初始化失败后自动关闭直通、走 PCM。
2. **慢速源卡死**：同一 4K 源是否还出现「有画面无声音 → 卡住 → 换源」。
3. **超分按钮**：控制栏应出现「超分」；点击循环 `超分 → 超分·轻度 → 超分·强化 → 超分`；
   选「强化」应提示高负载；画质应有可见变化（仅 GPU 渲染时生效，Surface 直出时不生效）。
4. 覆盖安装后数据/收藏/历史保留。

## 六、回滚

重装 5.6.3 即可（`C:\Users\qw191\Desktop\WebHTV-APK-5.6.3\`）。
