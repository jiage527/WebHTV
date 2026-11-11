# T4 MPV 音频直通失败自动回退（4K 无声 + 慢速源卡死同一根因）

Task ID: `T4-mpv-audio-passthrough-fallback`
Lane: quick-fix
Base HEAD: `9f0370211606acd7e534e2336a2159736d807f10`
Date: 2026-11-09

## 一、用户现象（真机：一加ACE 3V）

| 现象 | 观测 |
| --- | --- |
| 同一 4K 源 MPV **无声**，画面异常 | Exo / IJK 同一源**声音与画面都正常** |
| 1080p 同源 MPV **完全正常** | 说明源、网络、解码器能力都不是原因 |
| 慢速源 MPV：出画面 → 不断换源 → 最后连接失败 | 进度条**仍在显示 xx MB/s** |
| **手动关掉「音频直通」后，4K 有声、画面也正常** | 用户原话：「问题都不存在了，什么鬼？」 |

关键对照：**关掉直通修好了两个看似无关的症状**，说明它们同根。

## 二、根因

### 2.1 直通开关在 MPV 路径下只影响一个选项

全仓检索 `isAudioPassThrough`，MPV 播放路径上**只有一处**读取：

`app/src/main/java/com/fongmi/android/tv/player/engine/MpvPlayerEngine.java:1121-1127`

```java
private String resolveAudioSpdifCodecs() {
    boolean enabled = PlayerSetting.isAudioPassThrough(PlayerSetting.MPV);
    String codecs = enabled ? MpvAudioCapabilities.getAudioSpdifCodecs(App.get())
            : String.join(",", MpvAudioCapabilities.getAudioCompressedCodecs(App.get()));
    ...
    return codecs;
}
```

所以用户 A/B 的差异**只可能来自 `audio-spdif`**。

### 2.2 `audio-spdif` 的应用时机与后果

`androidx/media3/mpvplayer/MpvPlayer.java:1810`：

```java
if (!TextUtils.isEmpty(config.audioSpdif())) setOption("audio-spdif", config.audioSpdif());
```

即：**非空 → 走 IEC61937 直通载体；空 → 不设置，mpv 用自身默认（空 = 不直通）**。

随包 `libmpv.so` 内含以下字符串（`arm64-v8a/libmpv.so` 已核对）：

- `Could not open/initialize audio device -> no sound.`（命中 1）
- `audio output initialization failed`（命中 1）
- `Failed to initialize audio driver '%s'`（命中 1）

手机扬声器无法承接 IEC61937 直通载体 → 音频输出初始化失败 → **无声**；而 mpv 默认 `video-sync=audio`，音频时钟停摆后**视频等音频**，画面随之冻结；此时解复用器仍在拉数据，所以**速率数字仍在跳动**。起播超时后触发换源 → 表现为「不断换源、最后连接失败」。

**同一根因解释了全部三个症状。**

### 2.3 排除项（已实证，不是原因）

- **不是解不了码**：`third_party/mpv-native-overrides/ffmpeg.sh` 只 `--disable-{muxers,encoders,devices}`，解码器未裁；随包 so 内 `eac3/ac3/dts/truehd/aac` 等解码器符号齐全。
- **不是源或网络**：Exo/IJK 同源正常。
- **不是 `ao` 配置**：`MpvPlayerConfig.java:209` `ao="audiotrack,opensles"`，两种情况相同。
- **不是我上一轮曾误判的「mpv 内置默认 `audio-spdif=ac3,eac3,dts`」**：用本机 mpv v0.41.0 实测
  `--audio-spdif  String (default: )`，**默认是空**。该误判已在动手前推翻，未进入代码。

### 2.4 为什么之前没人兜住

`MpvDtsHdFallbackPolicy` 只对 **DTS-HD** 的 AudioTrack 初始化失败做一次性降级（`audio-spdif=dts`），
匹配串是 `audiotrack init failed` / `audiotrack.getstate failed`。
全仓检索 `Could not open/initialize audio device`、`audio output initialization failed`、
`Failed to initialize audio driver`，**Java 侧命中 0** —— 没有任何逻辑对这一类失败做出反应，
所以一旦直通失败，整条片子就一直坏下去。

`docs/P3-5-mpv-compressed-audio-offload.md` 记录过电视上同源问题（`route=false` 但
`audio-spdif` 仍含 `aac,mp3`，导致音频时钟停摆、视频时间戳晚约 960ms 被丢帧），
当时只补了 AAC/MP3 的路由门控，**IEC61937 那一半未动**。

## 三、修法（最小、可逆）

新增纯逻辑类 `androidx/media3/mpvplayer/MpvAudioSpdifFailurePolicy`：

- `isAudioOutputInitFailure(line)`：只认 mpv 自己的音频输出初始化失败串（三条，取自随包 so）。
- `shouldInspect(configuredCodecs, line, attempted)`：仅当**配置过非空直通**且**尚未尝试**时返回 true。
- `disabledCodecs()`：返回 `""`，即 mpv 自身默认（不直通）。

`MpvPlayer` 接入（与既有 `maybeRetryDtsHdAsCore` 完全同构）：

- 字段 `passthroughFallbackAttempted`，随新媒体重置。
- 在 `logMessage` 的既有恢复链旁调用 `maybeDisablePassthroughOnAudioOutputFailure(line)`。
- **优先级保护**：若该行属于既有 `MpvDtsHdFallbackPolicy` 的处置范围（DTS-HD 场景），
  直接让位，保持既有 DTS-HD 降级契约不变。

### 为什么不让位不行

既有验收契约（`docs/P3-3-MPV-AUDIO-FALLBACK.md`）要求「只有确认的 DTS-HD AudioTrack
初始化失败才触发一次 `audio-spdif=dts` 降级」。若本策略抢先把它置空，接 HDMI 功放的
用户会从「DTS-HD 降到 DTS core 仍有环绕声」退化成「全部走 PCM」，属于**功能倒退**。

### 与「关掉直通」的关系

回退值 = 空 = 用户手动关掉直通的**同一状态**，因此用户实测有效的路径变成自动行为，
不需要用户自己去关，也不会为 HDMI 用户改变任何行为。

## 四、验证

- `:app:testMobileArm64_v8aDebugUnitTest --tests "androidx.media3.mpvplayer.MpvAudioSpdifFailurePolicyTest"`
  → **BUILD SUCCESSFUL**，`tests=10 failures=0 errors=0 skipped=0`
- `--tests "androidx.media3.mpvplayer.*"` → BUILD SUCCESSFUL（既有 DTS-HD / 解码策略测试未回归）
- `:app:compileMobileArm64_v8aDebugJavaWithJavac :app:compileLeanbackArm64_v8aDebugJavaWithJavac` → BUILD SUCCESSFUL

### 未做（明确记录）

- **未真机验证**：需用户在 4K 源上实测「打开直通也能出声」。本修复的价值正是让用户不再需要手动关直通。
- 未改 `MpvAudioCapabilities` 的路由探测（那是「预先判断」，本单做的是「事后兜底」，两者互补且互不依赖）。

## 五、回滚

`git revert <本单提交>` 即可；新增类为独立文件，删除无副作用。
