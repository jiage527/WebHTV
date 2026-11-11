# T2-CSP-URL-QUICKADD 站点注入「粘贴地址一键添加」

## 背景
用户反馈：「我没法一键添加，要填很多参数」。

## 现状核实（改动前）
- 「识别」面板（`CustomCspDialog.showRecognizePanel`）只走 JSON：`recognizedItems` 里
  只有 `CustomCspSetting.parse` 系列候选，`parseFlexible` 对裸 URL 直接抛异常。
- 全仓无任何「粘 URL 直接建站」的代码路径（grep `urlToItem|fromUrl|parseUrl` 为空）。
- `CustomCspSetting` 也没有针对站点注入的单测（`app/src/test/.../setting/` 下无该类测试）。
- 结论：用户的抱怨成立，缺的不是提示而是功能。

## 方案比较
1. **不做**：用户必须自己把地址改写成 JSON，正是被投诉的点。否决。
2. **在表单里预填 api 字段**：仍要手填名称/类型，改善有限。否决。
3. **新增独立「粘贴地址」入口**：多一个入口，认知负担上升。否决。
4. **让「识别」在 JSON 失败后回退到地址解析**（采用）：同一个输入框，粘 JSON 或粘
   地址都能用，不增加新入口。

## 实现
### 新增 `setting/CustomCspQuickAdd.java`（纯逻辑，不依赖 Android）
- `parse(text)` → `Parsed{valid,name,api,rawApi,headerText,reason}`，失败返回原因而非抛异常。
- 支持三种粘贴形态：裸地址、`名称 地址`、`头部块 + 地址`。
- `detectType`：裸苹果CMS JSON 接口 → `1`；`.xml` 或 `ac=videolist` → `0`。
  **这是关键**：`CustomCspSetting.getType()` 缺省回退是 `3`（Spider），而 `type==3`
  在 `SiteApi.isSpider` 里会去找 Jar/JS 脚本，普通 HTTP 接口会静默失败。
- `stripQueryNoise`：只摘 `ac/wd/pg/ids` 四个机器参数，未知参数原样保留
  （私有接口可能依赖）。
- `isDuplicate`：忽略 scheme、末尾 `/`、大小写与机器参数；保留有意义的参数。
- `isHeaderLine` 排除 URI scheme，避免把 `Referer: https://...` 当成接口地址。

### 改 `setting/CustomCspSetting.java`
- `quickAdd(String)`：把解析结果装成 JSON 再走 `parse()`，**复用既有 Item 装配路径**，
  不新增第二条建站分支。
- `hasApi(List,api)`、`quickAddParse(String)`。

### 改 `ui/dialog/CustomCspDialog.java`
- `recognizedItems` 末尾加入地址回退。
- `finishRecognize` 加去重（跳过已存在）与「已添加 N，跳过 M」提示。
- `showRecognizeFailure` 按 `reason` 给具体提示，不再一律「未识别到有效站点 JSON」。

### strings ×3
- 改：`setting_custom_csp_recognize_hint`（改为「粘贴接口地址，或站点 JSON」）、
  `setting_custom_csp_recognize_invalid`。
- 增 5 条：`_recognize_done_skip`、`_quick_no_url`、`_quick_bad_scheme`、
  `_quick_bad_host`、`_quick_dup`。全部均被引用。

## 验证
- 双端编译 BUILD SUCCESSFUL。
- 新增 `CustomCspQuickAddTest` **22/22**（tests=22 skipped=0 failures=0 errors=0）。
- 全量单测 **2173** 项仅既有 **41** 项环境失败（2151→2173，+22，失败类别与基线一致）。
- 单测抓出 **5 个真实缺陷**（非测试期望写错）：`www.` 未剥离、`url=` 前缀剥离失效、
  中文标签 `名称=` 不识别、`ac=videolist` 被当噪音导致 type 判错、非 http scheme
  误报 `no-url`。

## 提交
`5f3c8655`，tag `recovery/T2-CSP-URL-QUICKADD/20261109072758-5f3c86554355`。

## 已知限制
- 未做真机验证（无连接设备）。
- 只走被动识别，不做连通性探测（表单里的「测试」按钮仍是唯一的连通检查入口）。
- 日志与诊断体系（T2-21）不在锁定清单内，失败原因仍靠 `Notify` 提示。

## 回滚
`git revert 5f3c8655`，或 `git checkout 1e5c51af -- <改动文件>`。纯新增功能，
关闭入口即恢复原状（`quickAdd` 返回空列表时行为与改动前一致）。
