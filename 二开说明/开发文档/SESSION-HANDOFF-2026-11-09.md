# 会话交接 · WebHTV 工单继续实施（2026-11-09）

> 本文档是新会话的**执行契约**。请先读完本文件，再动手。
> 权威跟踪文档在桌面：`C:\Users\qw191\Desktop\webhtv-reports\`
> （`HANDOFF-WebHTV-工单实施.md`、`WORKLOG-工单进度.md`、`工单完成情况-总清单.html`）

## 一、当前状态（2026-11-09 核实，勿凭记忆）

- 仓库：`C:\Users\qw191\AppData\Local\Temp\webhtv_probe`，分支 `main`
- **HEAD = `8b56fc23`**（chore(release): bump version to 5.6.4）
- **工作区干净**，**无 active guard**（`task_guard.sh status` 显示 `finished`）
- 剩余本地 commits 未 push

### 最近提交链（新→旧）

```
8b56fc23  5.6.4 出包
70f8961d  播放控制栏新增「超分」按钮
27974608  MPV 音频直通失败自动回退（4K 无声修复）
9f037021  5.6.3 出包
8e269bb5  超分档位循环缺陷修复
75b4a105  5.6.2 出包
5f3c8655  站点注入「粘贴地址一键添加」
1e5c51af  5.6.1 出包
be571c98  T2-20 超分/锐化/降噪
964678c7  T2-18 WebDAV 服务端
455db526  T2-17 FTP 浏览
6d7887ee  T2-15 片头片尾识别
1d251b51  T2-14 SMB/WebDAV
6be6728c  T2-13 播放队列
5cff2025  T2-10 GIF 录制
323c82a4  T2-08 季分组
f4f34581  T2-05 进度预览
deb3aea6  T2-02 保持播放列表
ccb5647a  T1-17 … be8dc4fb T1-04 … 6bcdc495 T0-02 … 9bc20436 T0-10
```

## 二、已完成进度

**工单：15 / 21 完成，54.5 / 92.5 人日**

| 批次 | 结果 |
| --- | --- |
| T0 | 11/11 ✅（`9bc20436`…`6bcdc495`）**不要重做** |
| T1 保留的 5 条 | 5/5 ✅（`be8dc4fb`、`80aee60d`、`b4a6e09b`、`0e3622ef`、`ccb5647a`） |
| T2 前 5 条 | 5/5 ✅（`deb3aea6`、`f4f34581`、`323c82a4`、`5cff2025`、`6be6728c`） |
| T2-14/15/17/18/20 | ✅（见提交链） |
| **额外修复（非编号工单）** | 超分档位循环缺陷（`8e269bb5`）、MPV 音频直通回退（`27974608`）、控制栏超分按钮（`70f8961d`） |

**两个额外修复的背景**（用户主动反馈的线上问题，不是工单）：
1. **超分/降噪档位调不回**：`clampMode` 是钳制语义，强化档 +1 被压回 → 改取模回绕。**已真机验证通过**（用户确认「是的 已经修复了」）。
2. **4K 无声 / 慢速源卡死**（同一根因）：`audio-spdif` 直通列表在无直通输出端的设备上仍非空 → mpv 报 `Could not open/initialize audio device -> no sound.` → **音频时钟停摆** → 默认 `video-sync=audio` 使画面冻结（但解复用器仍在拉数据，所以「速率数字还在跳」）→ 起播超时换源。修法：`MpvAudioSpdifFailurePolicy` 在 mpv 报音频输出初始化失败时一次性把 `audio-spdif` 置空；既有 `MpvDtsHdFallbackPolicy` 对其自身失败串保有优先权。**待真机验证。**

## 三、剩余工单（6 条 / 38 人日）

| 工单 | 优先级 | 人日 | 内容 |
| --- | --- | --- | --- |
| **T2-23** | **P0** | 6 | 在线字幕搜索与下载 |
| **T2-24** | **P0** | 6 | 多源自动容灾看门狗 |
| T2-25 | P1 | 6 | 播放速度调节优化 |
| T2-29 | P1 | 9 | 缓存 / 离线缓存 |
| T2-30 | P1 | 6 | WebDAV 云备份（**仅立单未开工**，方案已就绪） |
| T2-22 | P3 | 5 | 个性化推荐 |

**建议顺序**：先 T2-23 或 T2-24（P0）。**T2-30 方案文档已备好**：
`C:\Users\qw191\Desktop\webhtv-reports\T2-30-WebDAV云备份-方案.md`（方案④，7 步）。

## 四、每一单的作业规矩（硬要求）

```
1. git status --porcelain 确认工作区干净
2. bash .codex/scripts/task_guard.sh start --id <ID> --mode <lane> --scope <每个路径一次>
3. 改代码（**先实测代码再起 guard**：报表描述常与代码不符）
4. 双端编译：export JAVA_HOME=/c/Users/qw191/APP/JDK21/jdk-21.0.12.1+1
   ./gradlew :app:compileMobileArm64_v8aDebugJavaWithJavac :app:compileLeanbackArm64_v8aDebugJavaWithJavac --offline
5. 需要时跑单测：:app:testMobileArm64_v8aDebugUnitTest --tests "<fqcn>" --offline
6. bash .codex/scripts/task_guard.sh finish --verified "<证据>" --commit-message "<信息>"
```

- 提交信息末尾加 `Assisted-by: Cindy (https://cindy.app)`
- `finish` 会自动提交 + 打 `recovery/<task-id>/<时间戳>` annotated tag
- **guard scope 在 start 时冻结**；改到 scope 外须：
  `rm -rf .codex/task-state/current` → `git stash push -u -m <msg>` → 扩 scope 重 start → `git stash pop`
- 一单 = 一个 guard 会话 = 一个原子提交
- 每批 5 单完成后**停下询问用户**是否继续

## 五、环境与坑（务必遵守）

**环境**
- JDK：`/c/Users/qw191/APP/JDK21/jdk-21.0.12.1+1`
- Android SDK：`C:\Users\qw191\APP\AndroidSDK`（build-tools 36.0.0 / 37.0.0）
- Gradle 一律加 `--offline`
- 产物：`app/build/outputs/apk/mobileArm64_v8a/release/`

**坑（踩过的）**
- **`assemble` 会生成未被 gitignore 的 `app/.cxx`** → 跑完必须 `rm -rf app/.cxx`
- **release 构建 4–10 分钟**，必须后台跑：`nohup ./gradlew … > log 2>&1 &` + 轮询 tail，前台会撞 300s 工具超时
- **终端编码是 GBK**：Python 打印中文会 `UnicodeEncodeError` → 中文一律写 UTF-8 文件再用 `read` 读回
- **Windows Python 不认 `/c/...` 路径** → 用 `C:\...`
- **并行 bash 有竞态**：有依赖的复制/校验必须串行（本轮 `cp` 与 `sha256sum` 并行曾报「文件不存在」）
- `docs/` 被 gitignore（`.gitignore:32`）→ 只作本地制品，不入提交；**但新会话可读**
- 对 `~/.gradle/caches` 全盘 `find` 会超时 → 限定 `-maxdepth` + `timeout`
- `apksigner` 只有 `.bat`（需 `JAVA_HOME`）；`aapt2.exe` 读含中文路径会失败 → 先 `cp` 成 ASCII 名

**改代码前必读**
- 仓库根 `AGENTS.md`：lane / scope / 验证 / 提交规则
- 动 FFmpeg / Media3 / mpv / libplacebo / JNI / 原生二进制前，必须先读
  `.codex/skills/upstream-integration-governor/SKILL.md`，走「评估 → 用户批准」两段式
- **不要碰 `third_party/`**（188M，含原生锁与预编译库）

## 六、已知既有失败测试（勿修，不是回归）

全量固定 **41 项**环境失败，根因 `java.lang.RuntimeException: Method elapsedRealtime in android.os.SystemClock not mocked`：

- `ExoCompressedAudioDirectPolicyTest` 35
- `Avs3ExtractionTest` 3
- `MpvPreloadControllerTest` 2
- `MpvConfigStoreTest` 1

跑单测时若这 4 个类失败属正常；**别的类失败才是回归**。

## 七、打包与交付（用户偏好，务必遵守）

- **只改 APK 文件名 + 升版本号**（`app/build.gradle` 的 `versionCode`/`versionName`）
- **不改 `applicationId`、不改启动器名**（保持「影视」）→ 保证可覆盖升级旧包、数据/收藏/历史保留
- **只打手机版**：`./gradlew :app:assembleMobileArm64_v8aRelease --offline`
- 交付目录：`C:\Users\qw191\Desktop\WebHTV-APK-<版本>\`
- 每包必验：
  - `aapt2 dump badging` → versionCode / versionName / `application-label-zh-CN:'影视'` / `native-code: 'arm64-v8a'`
  - `apksigner.bat verify --verbose` → Verifies，v2 = true
  - `sha256sum`
  - 签名指纹应为 `0d282cf5ec4042bc29c1390584438423c22427b330abba1c52a162ef1050b910`
- **R8 会内联移除新类**（mapping 显示 `R8$$REMOVED$$CLASS$$NNN`）→ 别用类名查 dex，
  改用其**字面量常量**（字符串）核对是否进包
- 交付同时写 `安装说明.html`（自包含单文件）

**历史包**
- `C:\Users\qw191\Desktop\WebHTV-APK-5.6.4\`（最新：MPV 无声修复 + 超分按钮）
- `WebHTV-APK-5.6.3\`、`WebHTV-APK-5.6.2\`、`WebHTV-APK-5.6.1\`、`WebHTV-APK\`

## 八、待真机验证（用户在手机「一加ACE 3V」上自行装包验证）

用户**能用手机远控本机自行装包**，所以**不要一律标「未真机验证」而放弃**。

- ⏳ **5.6.4 的 MPV 无声修复**：把「音频直通」改回**打开**，播之前没声音的 4K，确认有声且画面正常
- ⏳ **5.6.4 控制栏「超分」按钮**：控制栏应出现「超分」，点击循环 `超分 → 超分·轻度 → 超分·强化 → 超分`
- ⏳ 5.6.2 覆盖升级 + 粘地址一键添加能否搜到/播放
- ⏳ T2-14/17/18/20 各项真连 SMB/WebDAV/FTP、WebDAV 服务端、强化档帧率发热

## 九、用户反馈问题的处置原则（2026-11-09 教训，重要）

1. **用户说「没修好」时，先确认用户实际跑的是哪个版本，再怀疑代码。**
   用**不可伪造的包内字节对比**（解出 `classes*.dex` 搜字面量字符串）证明实际版本，
   而不是凭「我改过了」作答。并告知自查入口：**设置 → 最底部 → 关于**。
   （超分档位修复就是靠这招定位的：`超分（高负载）` 在 5.6.2 dex 命中 1、5.6.3 命中 0。）
2. **结论必须先经源码/随包二进制实证，不许靠猜。** mpv 选项语义须查 mpv 上游源码或用
   本机 `mpv.exe`（`C:\Users\qw191\APP\Win-box-base\resources\mpv\mpv.exe`，v0.41.0）
   `--list-options` 实测，或对随包 `libmpv.so` 双 ABI 抽查。
3. **动手修用户报的问题前，先说一声并立单走 guard 流程。**

## 十、当前待办（第一动作）

用户在等 **5.6.4 真机验证结果**；同时可继续做剩余工单。

**新会话第一步**：
1. `ls /c/Users/qw191/AppData/Local/Temp/webhtv_probe/.git` 确认仓库在
2. `bash .codex/scripts/task_guard.sh status` 确认无未完成 guard
3. `git log --oneline -1` 确认 HEAD（应为 `8b56fc23`）
4. 询问用户：**继续下一单（T2-23 在线字幕搜索 P0）**，还是**先等 5.6.4 验证结果**？
   —— 若用户已授权继续，直接按「四、作业规矩」开工。

> ⚠️ 剩余工单编号以本文件「三、剩余工单」为准。
> 旧文档里「下一步 = T2-14」「下一步 = T2-22」等表述**已过期**（T2-14 已完成）。
> T1-01/02/03/05/06/07/09~12/14/16/18/19 与 T3 全部 12 条**均已被用户删除**，不要再做。
