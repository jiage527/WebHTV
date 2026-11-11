# T2-23 在线字幕搜索与下载

状态：**已交付**（提交 `4cd8f3ba`，recovery tag `recovery/T2-23-online-subtitle-search/20261109182859`，2026-11-10）

## Recovery anchor

- 目标 / 验收标准：播放器字幕轨菜单内可**在线搜索字幕、选一条、直接套用播出**；两个可用源均不得要求注册或 API key；字幕匹配不得出现「自信的错字幕」。
- 当前状态：**达成**。双 flavor 编译通过；`...player.subtitle.*` 48 个 JVM 测试全绿。
- 回滚锚点：`4cd8f3ba` 的父提交 `8b56fc23`（`chore(release): bump 5.6.4`）。
- 唯一遗留：**无真机端到端验证**（见下方「未验证」）。

## 交付内容

| 文件 | 作用 |
|---|---|
| `player/subtitle/OnlineSubtitle.java` | 一个线上字幕结果的值对象（名称/语言/格式/发布名/来源/下载地址/下载量/评分/是否 gzip），`toSub()` 复用既有 `Sub` 路径。 |
| `player/subtitle/SubtitleMatchPolicy.java` | 查询词构造 + 发布名归一化 + 排序打分 + 语言 id/标签映射。**纯 JVM**。 |
| `player/subtitle/OnlineSubtitleRepository.java` | 两个源的检索与下载落盘。 |
| `player/subtitle/SubHdParser.java` | SubHD 搜索页 HTML → 结果条目。 |
| `player/subtitle/SubtitleCookieJar.java` | 极简内存 cookie 存储，供 SubHD 握手。 |
| `player/subtitle/SubtitleSource.java` | 新增 `writeOnline()` / `decode()` / `digest()`；抽出 `subtitleDir()`。 |
| `ui/dialog/SubtitleSearchDialog.java` | 搜索对话框（关键词 + 语言行 + 结果列表）。 |
| `ui/dialog/TrackDialog.java` | 字幕轨菜单新增「搜索字幕」入口（`player.isVod()` 才显示）。 |
| `res/drawable/ic_action_search_subtitle.xml`、两个 `dialog_track.xml`、三份 `strings.xml` | 入口图标、按钮位、文案（en / zh-rCN / zh-rTW）。 |

## 关键决策（含理由）

1. **源的选择来自实测而非猜测**：OpenSubtitles 旧版 REST 无 key 可 200；新版 v2 API 即使带 key 也 403。SubHD 可搜可下但握手依赖会话。zimuku（404/410）、assrt（000 不可达）排除。
2. **一次只查一种语言**：`sublanguageid` 传多值实测 400，故按语言 id 逐条请求后合并去重。
3. **gzip 按魔数判断**，不信任响应头或扩展名；非 gzip 载荷原样透传；截断 gzip 返回空字节（契约由测试钉住）。
4. **SubHD 为 best-effort**：其失败只记日志，不牵连主源结果。
5. **排序不外包给网络**：`SubtitleMatchPolicy` 不引任何 Android 类。初版直接调 `LyricsMatcher.normalize()`，JVM 单测立刻以 `TextUtils not mocked` 失败——遂改为本包内的等价归一化，**这也让排序规则可被测试钉住**。
6. **不伪造可比性**：标题与发布名跨语种时不做字符比较（否则中文查询会把英文发布名全部拒掉），改为「不可比较」标记并让**可比较结果永远优先**；宁可不排序也不排错。
7. **不发明下载计数**：SubHD 卡片里的下载量按 `bi-download` 图标定位，而不是「第一个数字」（那是文件大小）。

## 验证（已完成）

```
JAVA_HOME=/c/Users/qw191/APP/JDK21/jdk-21.0.12.1+1
bash ./gradlew :app:compileMobileArm64_v8aDebugJavaWithJavac \
              :app:compileLeanbackArm64_v8aDebugJavaWithJavac \
              :app:testMobileArm64_v8aDebugUnitTest --tests "com.fongmi.android.tv.player.subtitle.*" --offline
→ BUILD SUCCESSFUL；48 tests / 0 failures / 0 errors
```

- `SubtitleMatchPolicyTest`（19）：查询词剥离年份/注释、繁简折叠、发布名噪音剥离、错集拒收、集数按数值比较、时长/热度/评分的边界与单调性、同链接去重、`limit` 语义、语言标签。
- `OnlineSubtitleProviderTest`（8）：**真实 SubHD 搜索页片段**（`SubHdSearchPageFixture`）解析出 id/发布名/语言/格式/下载量；gzip 解压、截断 gzip 拒收、纯文本透传、按内容判格式（srt/ass/vtt）；标题为空时**不发网络请求**。

## 未验证 / 已知局限

- **无真机端到端验证**：未验证「搜到 → 下载 → 套用 → 画面出字」的真机链路，也未在真机上确认 SubHD 握手在 Android 网络栈下的表现。
- SubHD 的 HTML 结构若变动，结果会变少而非报错；仓库内已用真实片段做回归测试，但**不能替代真实抓取**。
- 只接了 2 个源；`sublanguageid-all` 的重定向行为已证存在，但代码选择逐语言请求，未走该路径。
