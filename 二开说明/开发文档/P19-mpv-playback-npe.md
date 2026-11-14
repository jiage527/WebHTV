# P19 MPV 模式播放必崩（NPE）修复

- 用户反馈：2026-11-14「现在点击播放视频就会报错。只要处于MPV的模式。」
- 影响范围：所有 MPV 内核的播放（手机 + 电视），从 P14 那个包（`e9b9047d`）开始。
- 修复提交：见 `git log`（P19）。

## 1 崩在哪

平板（HUAWEI SCM-W09 / Android 10）上点任意海报，App 直接进崩溃页。`logcat` 的致命异常：

```
java.lang.RuntimeException: Unable to create service com.fongmi.android.tv.service.PlaybackService:
  java.lang.NullPointerException: Attempt to invoke virtual method 'boolean g63.g6()' on a null object reference
    at l63.Y(...)
    at l63.c0
    at l63.d0
    at l63.<init>
    at xw3.t
    at xw3.<init>
    at com.fongmi.android.tv.service.PlaybackService.onCreate
```

用 R8 的 `mapping.txt` 反解混淆名：

| 混淆名 | 实际类 |
| --- | --- |
| `g63` | `androidx.media3.mpvplayer.MpvPlayer` |
| `l63` | `com.fongmi.android.tv.player.engine.MpvPlayerEngine` |
| `xw3` | `com.fongmi.android.tv.player.PlayerManager` |

即：`PlaybackService.onCreate` 构造 `PlayerManager` → 构造 `MpvPlayerEngine` → 构造过程中对**一个 null 的 MpvPlayer** 调了 `hasAnime4KShaders()`。

## 2 为什么会 null

`MpvPlayerEngine` 自己创建播放器：

```java
private MpvPlayer buildPlayer(Player.Listener listener) {
    MpvPlayer player = new MpvPlayer(App.get(), buildConfig());   // buildConfig() 先跑
    ...
    restoreSuperResolution(player);
    return player;
}
```

`buildConfig()` 里的 `applyQualityEnhanceOptions(builder, vo)` 需要问「超分链是否已在跑」，判断依据是**字段** `this.player`：

```java
player.hasAnime4KShaders()      // P14 加的
```

而这个字段只在 `PlayerManager` 的 `this.player = buildPlayer(listener)` 完成之后才有值。首次构造时它还是 `null`，于是构造链一进去就 NPE——播放器还没创建，服务先挂了。

同一个坑在 P14 里已经踩过一次：`restoreSuperResolution()` 当时就是因为「方法在字段赋值之前被调用」而改成只认传入参数。`applyQualityEnhanceOptions()` 漏了。

## 3 改法

```java
player != null ? player.hasAnime4KShaders()
        : Anime4KShaderChain.needsShaders(MpvQualityEnhanceSetting.getMode()));
```

- 有旧 player（重建场景）：仍问 mpv 的**实际状态**，与 P14 的意图一致——链真在跑才撤掉内置缩放核。
- 没有旧 player（首次构造）：按**档位**预判。因为 `buildPlayer()` 紧接着就调 `restoreSuperResolution()`，只要档位不是关闭、渲染器支持着色器，链马上会被挂上；此时若仍写缩放核，就回到 P14 之前「两套同时跑、档位差 0.028%」的老问题。
- 只在 `applyQualityEnhanceOptions` 这一处改。另一处调用 `applyQualityEnhanceRuntime()`（运行时切换档位）本来就有 `if (player == null) return false;`，安全。

## 4 验证

| 项 | 结果 |
| --- | --- |
| 双 flavor `compileJavaWithJavac` | `BUILD SUCCESSFUL in 28s` |
| 双端 `assemble*Release` | 见交付记录 |
| 真机（平板 HUAWEI SCM-W09 / Android 10） | 点海报可正常进入播放，不再弹崩溃页 |
| 全量单测 | 2518 / 41 failed / 1 skipped，与既有基线逐项一致（均为环境相关的既有失败） |

## 5 教训

「问当前状态」和「问配置意图」是两件事，写在一行三元里很容易忘掉前者可能在对象还没出生的时候被问到。凡是构造期间会被调用的方法，**不能依赖 `this` 字段已赋值**——P14 在 `restoreSuperResolution` 里想到过一次，这里又漏了一次，这次把理由写进了注释，避免第三次。
