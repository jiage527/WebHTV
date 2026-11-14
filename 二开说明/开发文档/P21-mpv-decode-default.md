# P21 MPV「默认解码」选项（硬解 / 软解）

## 1. 需求

用户（2026-11-14）：「给 MPV 的播放设置里面添加一个选择默认使用软解或硬解的选项。」
随后补充位置：「我选择了 MPV 以后。它会有个播放性能。这个选项里面，你把它做到这个选项里面。」

因此落在 **设置 → 播放设置 → 播放性能 →（MPV）常用参数**，紧挨在「硬解路径」之上。

## 2. 改动前的实际行为（实测，不是猜测）

- 全局解码不存在持久设置：`PlayerManager` 构造时写死
  `this.engine = buildEngine(playerType, PlayerEngine.HARD);`
- 播放器界面上的「解码」按钮（`toggleDecode()` / `switchDecode()` / `switchDecodeFresh()`）
  只对 **本次播放** 生效，退出重进又回到硬解。
- MPV 侧本来只有「硬解路径」三选一（自动回退 / 零拷贝优先 / 兼容复制），
  没有「用不用硬解」这一层；`MpvPlayerEngine` 里 `decode == HARD ? getHwdecOption() : "no"`
  这一行早就能表达软解，只是没有入口。

## 3. 改法

| 文件 | 改动 |
| --- | --- |
| `setting/MpvPerformanceSetting.java` | 新增 `DECODE_HARD = 0` / `DECODE_SOFT = 1`、键 `perf_mpv_decode_default`、`getDecodeDefault()` / `putDecodeDefault(int)` / `getDecodeDefaultText()` / `initialDecode(int kernel, int hard, int soft)` |
| `setting/PlaybackPerformanceCatalog.java` | 新增参数 `MPV_DECODE`（标题「默认解码」，描述 >40 字，说明软解的代价） |
| `setting/PlaybackPerformanceUiPolicy.java` | `MPV_COMMON` 加入 `MPV_DECODE`，仅 MPV 内核显示 |
| `ui/dialog/PlaybackPerformanceDialog.java` | 文本分支 + 点击分支（两态循环） |
| `player/PlayerManager.java` | 构造时按设置决定初始解码；`prepareMpvOutputForNewItem()` 每次起播前同步 |
| `test/.../PlaybackPerformanceUiPolicyTest.java` | 常用项集合断言更新 + 新增「只在 MPV 出现」用例 |

### 3.1 为什么「选硬解」不记为已改

`putDecodeDefault(DECODE_HARD)` 调
`PlaybackPerformanceSetting.setOverride(MPV_DECODE, mode != DECODE_HARD)`：
选硬解等于回到出厂默认，面板头部的「已改 N 项」不该因此加一。

### 3.2 为什么还要在每次起播前同步

第一版只改了 `PlayerManager` 构造参数。实测发现：把「默认解码」从软解改回硬解后，
**下一次起播仍然是 `decode=soft`** —— 因为同一个 `MpvPlayerEngine` 实例会跨多次播放复用，
`decode` 字段只在构造时赋值。

补在 `prepareMpvOutputForNewItem()`（`start(...)` 里、建播放器之前调用）：

```java
if (engine instanceof MpvPlayerEngine mpvDefault) {
    int desired = MpvPerformanceSetting.initialDecode(
            playerType, PlayerEngine.HARD, PlayerEngine.SOFT);
    if (mpvDefault.getDecode() != desired) {
        mpvDefault.setDecode(desired);
        ...
    }
}
```

语义因此定为：**每次起播都按设置来；播放器里那个解码按钮只算本次临时切换。**

## 4. 验证

- 双 flavor `compileJavaWithJavac`：`BUILD SUCCESSFUL`。
- `--tests "…PlaybackPerformanceUiPolicyTest"`：`tests="7"`、`failures="0"`（原 6 + 新增 1）。
- 真机（一加 ACE 3V / Android 16）：面板出现「默认解码 硬解」，点一下变「软解」，
  头部「已改 1 项」→「已改 2 项」；`shared_prefs` 里写入 `perf_mpv_decode_default`。
- 软解生效的应用内日志：
  `decode=software … actualDecode=software … hwdec=no … vo=gpu-next`，
  mpv 属性 `hwdec` / `hwdec-current` 均为 `no`；播放正常（`firstFrameMs=1238`、`dropped=0`）。
- 改回硬解后新起播：`decode=hard … actual=surface/mediacodec_embed`，证明每次起播已跟随设置。

## 5. 已知边界（如实写）

1. **软解 + 电视直出 = 用不了直出**。`surfaceDirect` 要求 `decode == HARD`，
   选软解会自动走 `gpu`/`gpu-next` 渲染。电视上软解 4K 会明显吃力，这是硬解存在的理由。
2. **软解反而让 Anime4K 超分可用**：超分要求 GPU 渲染器，硬解直出（`mediacodec_embed`）用不了着色器。
3. **硬解失败仍会自动回退软解**（既有行为，`perf_decoder_fallback`，`PlayerManager` 里
   `buildEngine(playerType, PlayerEngine.SOFT)`）。实测某条线路在「硬解 + 电视直出」下
   `mpv.native.output` 报错后，引擎自动重建为软解 —— 所以日志里会看到「开始是 hard，
   几秒后变 soft」。这不是「默认解码」没生效，而是原有的失败保护；本次没有改动它。
