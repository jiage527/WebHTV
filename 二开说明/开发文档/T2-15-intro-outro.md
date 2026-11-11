# T2-15 智能片头片尾识别（自动跳过）

- 工单：A-09，P1，5 人日
- 提交：见本轮 guard finish 输出
- 验收：双端编译 `BUILD SUCCESSFUL`；`player.intro` 单测 52/52 通过；全量 2038 项仅既有 41 项环境失败

## 要实现的能力

读片源自带的 MKV/MP4 章节标记认出片头（OP/Opening/片头/主题曲）与片尾（ED/Ending/片尾），
并在同一部剧后续集没有章节标记时，用其它集学到的固定位置推断，从而自动跳过片头、
到片尾自动跳下一集。替换现有纯手动记录的体验，但保留手动记录的入口与语义。

## 关键实证

- **Exo 侧**：随包 `media3-exoplayer-1.11.0-alpha01-fongmi-runtime.jar` 中
  `ExoPlayerImpl.getCurrentMediaChapters()`(1074) / `readMediaChapters()`(2181) /
  `MediaChapterProvider` 存在，`MediaChapter.timeUs` 是章节起点。
- **mpv 侧**：`MpvPlayer.java` 观察 `chapter`(INT64) 与 `chapter-list`(NODE)，
  `parseChapters` 把每项包成 `MediaEdition`——该实现里 `durationUs` 承载的是**章节起点**。
  两套引擎的单位/字段不一致，故新增 `IntroChapterAdapter` 统一收敛。
- 结论：章节只在起播一段时间后到达，且可能永远不到（网络源/无章节文件），
  所以「时长更新」与「章节到达」是两个独立触发点，都要重判。

## 设计决策

1. **判定链 = 章节 → 同剧学习；手动记录不入链。**
   现有手动按钮写的是 `History` 的「续播位置」语义，而不是「跳过某段」的区间语义。
   把续播点当区间用会让播放位置变得莫名其妙。手动值改为**只当样本**喂进档案
   （`IntroSession.recordManualOpeningEnd/recordManualEndingStart`），提升同剧推断准确度。
2. **总开关默认关闭**（`intro_auto_skip`）。自动跳过会移动播放位置，属于用户直接
   可察觉的行为改变，改成默认开启会让老用户觉得「播放器乱跳」。关闭时行为与旧版本一致。
3. **档案按「剧」存**（`History` 的 key 本身不含集号，粒度一致），
   样本分三类：`openingEnds`（跳转目标）、`openingLengths`（决定起点）、`endingStarts`。
4. **只用中位数 + 过半一致性门槛**，不用平均值：样本常只有三五条且易有离群值。
   **只知道终点不知道长度就不推断**——硬猜起点等于一次可能很长的误跳过。
5. **不过阈值不给结果**：学习出的区间仍要过与手动记录同一套长度校验
   （`IntroChapterDetector.limitOf`），避免产生用户改不动的值。
6. **章节标题整词匹配**：`ED` 只有两字母，子串匹配会把 `Edited`/`Reduced` 认成片尾。
7. **不建数据库、不做迁移**：档案存 JSON 文件 `intro_profiles.json`，
   内容未变不写盘（播放进度上报频繁，避免 IO 抖动）。

## 防误跳（`IntroSkipGuard`）

- 同一区间只跳一次（用户手动拖回片头不会被反复弹走）
- 只在播放中跳（暂停时拖动找画面不触发）
- 只往前跳

## 改动清单

新增 `app/src/main/java/com/fongmi/android/tv/player/intro/`（14 文件）：
`IntroSegment`、`IntroChapterPoint`、`IntroChapterAdapter`、`IntroChapterDetector`、
`IntroStats`、`IntroLearner`、`IntroProfile`、`IntroProfileStore`、`IntroSetting`、
`IntroSkipGuard`、`IntroResolver`、`IntroSession`。

改既有：
- `player/PlayerManager.java`：新增 `getCurrentMediaChapters()`
- `player/engine/PlayerEngine.java`：新增默认实现
- `player/engine/ExoPlayerEngine.java`：委托 `player.getCurrentMediaChapters()`
- `player/engine/MpvPlayerEngine.java`：返回 `Collections.emptyList()`（mpv 章节 = `MediaEdition`，走另一条路）
- mobile `VideoActivity.java`：`mIntro` 会话、`onPrepare`/`onTracksChanged` 喂数据、进度回调 `applyIntroSkip`
- leanback `VideoActivity.java`：同上
- mobile `SettingPlayerFragment.java` + `fragment_setting_player.xml`、leanback `SettingPlayerActivity.java` + `activity_setting_player.xml`：`introAutoSkip` 开关行
- strings ×3：`player_intro_auto_skip`

新增测试：`IntroChapterDetectorTest`(21)、`IntroLearnerTest`(18)、`IntroSkipGuardTest`(13)。

## 单测抓出的问题

4 项失败全部查明：**0 个生产缺陷，4 个测试期望写错**。
- `requiresNextChapterToKnownEnd`：原用例第二个标签写成 `Opening`，本身就该被识别，
  改成 `Cold Open` 才真正测「片头是最后一个章节」。
- `medianOfEvenCount`：`[1,3,5,7]` 的中位数按取中间两值均值应为 `4`，期望值写成了 `3`。
- `endingSkipsOnReachingStart`：原名与断言不符，断言「跳两次都为真」与「只跳一次」的
  设计矛盾；改名 `endingSkipsOnceOnReachingStart` 并改断言。
- `manualRecordContributesSamplesForLearning`：档案对重复值去重，三个长度样本写成同一个值
  只凑出一个样本 → 改为三个不同值，并补断言长度与来源。

## 未验证/风险

- 无真机运行验证（本机无连接设备）。章节识别的真实命中率、以及自动跳过在真实片源上的
  体感，需要有带章节标记的 MKV/MP4 实测。
- `IntroSetting.useChapter/useLearned` 两个细分开关已实现且参与判定，但本轮只暴露了总开关 UI。
