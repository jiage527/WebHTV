# T3-quality-enhance-cycle-fix · 超分/锐化档位无法调回 + 重置无效

> 类型：**缺陷修复**（非编号工单，用户实测反馈）
> 反馈时间：2026-11-09 16:0x（用户：「超分按钮无法调节 我调成这样以后 调不回去了」「重置也对它不起作用」）
> 修复提交：`8e269bb5` · tag `recovery/T3-quality-enhance-cycle-fix/20261109082215-8e269bb51d72`
> 出包提交：`9f037021`（5.6.3 / versionCode 563）
> 用时：16:09:47 开工 → 16:22:15 提交，约 **12 分钟**

---

## 一、用户报告的现象

1. 超分/锐化调成「强化（高负载）」后，**再怎么点都回不去**。
2. 点「重置」**也没用**，档位不变。
3. 截图里还可见两处文案异常：超分行显示「强化（高负载）**（高负载）**」（重复），
   而降噪行显示「**关闭（高负载）**」（自相矛盾 —— 关闭怎么是高负载）。

## 二、根因（3 处，均经源码实证）

### 缺陷 1（主因）：档位到顶被钳制，无法回绕

`PlaybackPerformanceDialog` 的点击处理：

```java
MpvQualityEnhanceSetting.putMode(MpvQualityEnhanceSetting.getMode() + 1);
```

而 `MpvQualityEnhanceSetting.putMode` → `MpvQualityEnhanceOption.clampMode`：

```java
public static int clampMode(int value) {
    return Math.max(MODE_OFF, Math.min(MODE_STRONG, value));
}
```

`MODE_STRONG = 2`。到「强化」后再点：`2 + 1 = 3` → 被 `min(2, 3)` **钳回 2**。
⇒ 档位**永远停在强化**，点多少次都出不去，而且**没有任何报错**（`putMode` 静默存回合法值）。
降噪/去色带（`DENOISE_STRONG = 2`）**同一缺陷**。

**对比证据**：同一文件里其它所有循环选项都正确回绕，只有这两项漏了：

| 处理函数 | 写法 |
|---|---|
| `cycleBufferBytes` | `(getBufferBytesOption() + 1) % 4` ✅ |
| `cycleBackBuffer` | `(getBackBufferOption() + 1) % 4` ✅ |
| `cyclePlayCache` | `(getPlayCacheOption() + 1) % 5` ✅ |
| `MpvPerformanceSetting.putSoftTuneMode` | `(getSoftTuneMode() + 1) % 3` ✅ |
| **超分/锐化** | `putMode(getMode() + 1)` ❌ |
| **降噪/去色带** | `putDenoise(getDenoise() + 1)` ❌ |

### 缺陷 2：「重置」根本不写这两个键

`PlaybackPerformanceDialog.reset()` → `PlaybackPerformanceSetting.applyAuto()`：

```java
public static void applyAuto() {
    int kernel = PlayerSetting.getPlayer();
    clearOverrides(kernel);     // 只 Prefers.remove(perf_mpv_auto_overrides_v1)
    applyAutoProfile(kernel);   // 只重放内核预设（EXO/MPV/IJK 的性能项）
    putCurrentProfile(PROFILE_AUTO);
}
```

全链路**没有任何一处写** `perf_mpv_quality_enhance` / `perf_mpv_quality_denoise`
（全仓 grep 确认，这两条键只被 `MpvQualityEnhanceSetting` 自己读写）。
⇒ 重置对画质增强**完全不生效**，用户因此被困住 —— 这是缺陷 1 直接放大的后果。

### 缺陷 3：高负载提示用「两档位或」，且档位名里已含后缀

`enhanceText(text, mode, denoise)` 用 `needsPerformanceWarning(mode, denoise)`
= `mode == STRONG || denoise == STRONG` 来判断**每一行**是否加「（高负载）」，
而 `getModeText()` 内部**已经把「（高负载）」写进了档位名**。两处叠加导致：

- 超分「强化」→ 名字已含后缀 + 又拼一次 → **「强化（高负载）（高负载）」**；
- 降噪「关闭」而超分「强化」→ 用「或」判断命中 → **「关闭（高负载）」**。

（截图里这两处都出现了，与代码推断一致。）

## 三、修复内容

| 文件 | 改动 |
|---|---|
| `player/mpv/MpvQualityEnhanceOption.java` | 新增 `nextMode` / `nextDenoise`（取模回绕）；新增**按行**判断 `modeNeedsWarning` / `denoiseNeedsWarning` |
| `setting/MpvQualityEnhanceSetting.java` | 档位名**不再内嵌**「（高负载）」后缀；新增 `reset()` 显式关闭两档 |
| `ui/dialog/PlaybackPerformanceDialog.java` | 点击改用 `nextMode`/`nextDenoise`；`reset()` 追加 `MpvQualityEnhanceSetting.reset()`；`enhanceText` 改为接收**按行**布尔量 |
| `test/.../MpvQualityEnhanceOptionTest.java` | 新增 4 项回归测试 |

设计要点：
- **不改变任何 mpv 选项内容**（`build()` 未动），只修交互与文案，硬件行为零变化。
- **关闭时仍返回空 map**，保持「关闭与旧版本逐字节一致」的既有契约。
- `needsPerformanceWarning` 保留（弹窗提示仍用它），只把**行内文案**改为按行判断。

## 四、验证

> **真机结论（2026-11-09，用户实测）**：装上 5.6.3 后用户确认 **已修复**。
> 这是本工单体系内**第一条经过真机验证**的交付（其余条目受「本机无连接设备」限制只能到编译 + 单测层面）。

### 4.1 一次「以为没修好」的复盘（值得复用）

用户第二次反馈「降噪调到最高也调不回去」并附截图。但截图里两行都显示
「（高负载）（高负载）」——**这正是 5.6.2 的旧文案**。

当时的处理（有效，可复用）：**不凭「我改过了」作答，而是对比两个 APK 包内的实际字节**，
用不可伪造的证据判断用户到底跑的是哪版：

| 包内字符串 | 5.6.2 | 5.6.3 |
|---|---|---|
| `强化（高负载）` | 1 | **0** |
| `去色带+高精度（高负载）` | 1 | **0** |
| `高负载`（总计） | 4 | **2** |

⇒ 证明用户看的是**旧界面（尚未升级）**，而非修复无效。
同时告知用户在「设置 → 最底部 → 关于」可看到版本号（`5.6.3-202611091623` 才是新版），
使其能自行核验。**用户随后升级并确认已修复。**

**教训**：用户反馈「没修好」时，先确认**用户实际跑的是哪个版本**，再怀疑代码；
本次靠「包内字符串差异」这个不可伪造证据结案。

### 4.2 自动化验证记录

| 项 | 结果 |
|---|---|
| 双端编译 | `BUILD SUCCESSFUL in 1m 24s`（mobile + leanback） |
| 定点单测 | `MpvQualityEnhanceOptionTest` **27/27**（原 23 + 新 4），`skipped=0 failures=0 errors=0` |
| 全量单测 | **2177** 项，失败 **41** 项 —— 与基线**同类同数**（`ExoCompressedAudioDirectPolicyTest` 35 / `Avs3ExtractionTest` 3 / `MpvPreloadControllerTest` 2 / `MpvConfigStoreTest` 1，根因 `SystemClock ... not mocked`），**无新增失败** |
| 修复是否真入包 | 对比 5.6.2 包：`强化（高负载）` / `去色带+高精度（高负载）` 各由 1 处 → **0 处** |
| 改动范围 | 仅 4 个文件，`task_guard` scope 校验 PASS |
| **真机运行** | ✅ **2026-11-09 用户在「一加ACE 3V」实测通过** |

新增的 4 项回归测试（directly 钉住本次缺陷）：
- `modeCyclesBackToOff` —— 强化必须能回到关闭（**核心断言**）
- `denoiseCyclesBackToOff` —— 降噪同理
- `cyclingFromOutOfRangeValueStaysLegal` —— 被旧值/手工改坏的越界档位也能循环回合法值
- `warningIsPerRowNotShared` —— 高负载提示只看本行，杜绝「关闭（高负载）」

## 五、已知未做 / 顺带发现

1. ~~未真机验证~~ → **已于 2026-11-09 真机验证通过**（见四）。
2. **顺带发现（本次未改，属独立问题）**：`perf_mpv_quality_enhance` / `perf_mpv_quality_denoise`
   **不在 `Backup.APP_PREFS` 白名单**里 → 备份/恢复不包含这两项设置。
   影响很小（用户重新选一次即可），但若要修应另开一单，避免混入本次修复。

## 六、回退

单独立提交 + recovery tag，可直接 `git revert 8e269bb5`。
未触碰 `build()` 生成的 mpv 选项，回退后硬件行为与修复前一致。
