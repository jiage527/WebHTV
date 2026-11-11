# T2-25 启动速度与首屏优化

状态：**部分交付**（提交 `e7717ab0`，recovery tag `recovery/T2-25-startup-first-screen-optimization/20261111143324-e7717ab0aeff`，2026-11-11）

工单原文要求的六项：`baseline.prof/profm` 埋点、启动任务并行化、首页骨架屏、常用站点配置预热、Room 索引优化、WebView 预创建。

## Recovery anchor

- **目标 / 验收标准**：从冷启动到首屏可见的时间缩短；工作项按「是否挡住首屏」显式分类，且该分类可被测试钉住，防止后续改动把网络/服务步骤悄悄挪回关键路径。
- **当前状态**：**部分达成**。本轮交付 **2/6 + 骨架屏**：启动任务并行化、Room 索引优化、首页骨架屏。**baseline.prof 埋点、站点配置预热、WebView 预创建未做**（见下「未完成」）。
- **回滚锚点**：`e7717ab0` 的父提交 `3669a9f5`（T2-24F MPV 硬件解码冻结修复）。
- **唯一遗留风险**：**无真机耗时对比数据**。SBOM：确认无崩溃、能进入，但「首屏到底快了多少」本轮没有量化（缺 baseline.prof 埋点）。

## 交付内容

| 文件 | 作用 |
|---|---|
| `startup/StartupBudget.java`（新） | 启动步骤分类表：每个启动步骤显式标注 `critical`（能否挡住首屏）。**纯 JVM**，因此分类可在无设备时被单测钉住。`DEFER_AFTER_FIRST_FRAME_MS = 80`。 |
| `startup/StartupBudgetTest.java`（新） | 6 项契约测试：只有 `store-restore`/`home-binding` 两项允许挡首屏；11 个网络/服务步骤一律不得挡；**未分类的新步骤默认「不许挡」**；WebView 永不在首帧前创建；延迟值在 (0, 200] 区间；步骤 id 唯一。 |
| `App.java` | `onCreate` 增加分段耗时日志。`ProxySetting.apply()` 与 `DanmakuSearchListFocusFixer.start()` 从主路径移到 `post(...)` 延迟队列——前者只在发请求时才有意义，后者只存在于 TV。 |
| `mobile/.../HomeActivity.java` | `PermissionUtil.requestFile`、`DLNARendererService.start`、`initConfig()` 从 `initView()` 主路径移出；新增 `runAfterFirstFrame()`，用 `OnPreDrawListener` 在**首帧真正绘制后**再投递延迟工作（`initConfig` 延迟 80ms、权限弹窗 1800ms、DLNA 2500ms）。 |
| `mobile/.../VodFragment.java` | 新增 `showSkeletonOnce()` / `hideSkeleton()`；只在 `homeContent()`（确定走原生列表）时铺骨架，走网页首页的分支不会闪一下 vod 网格；`mSkeletonShown` 保证每个 fragment 实例只铺一次。 |
| `mobile/res/layout/view_skeleton_vod.xml`（新） | 首屏骨架屏。复用真实卡片既有资源（`Vod.Grid` 圆角 + `shape_vod_name` 名称条），骨架与内容同形，切换不跳变，**零新增 drawable**。纯静态无动画。 |
| `mobile/res/layout/fragment_vod.xml` | `include` 骨架屏，默认 `gone`。 |
| `bean/History.java` + `db/AppDatabase.java` + `db/Migrations.java` + `schemas/38.json` | History 加 `(cid, createTime)` 复合索引，DB 版本 37→38，新增 `MIGRATION_37_38`。 |

## 关键决策（含理由）

1. **分类表做成纯 JVM 类而不是埋进 Activity**：它决定「启动时允许做什么」，必须可单测。若写成 `Activity` 里的散装代码，就只能靠人眼审阅，而启动变慢是**渐进发生**的。
2. **未分类步骤默认「不许挡首屏」**：`isCritical` 对未知 id 返回 `false`。默认放行的分类表会随新增步骤不断变慢；默认拒绝才会在新增步骤时**逼人显式表态**（测试会失败）。
3. **`(cid, key)` 索引刻意不加**：`key` 是 PRIMARY KEY，SQLite 已有唯一 autoindex。`EXPLAIN QUERY PLAN` 显示 `find(cid,key)`/`delete(cid,key)` 在任何情况下都走 `sqlite_autoindex_History_1`，第二个索引**永远不会被选中**，只会增加写开销和文件体积。这条判断写进了代码注释，避免后人「补一个显然的索引」。
4. **骨架屏零新增 drawable**：复用 `Vod.Grid` 与 `shape_vod_name`，保证骨架和真实内容同尺寸同圆角，切换时不跳变。新增一套骨架专用 drawable 会让「骨架形状」与「内容形状」日后各自漂移。
5. **骨架屏无动画**：骨架只停留数百毫秒，进场动画增加的恰是首帧前的主线程与渲染负担，与本工单目标直接冲突。
6. **首帧用 `OnPreDrawListener` 判定而不是固定 `postDelayed`**：固定延时要么在慢设备上早于首帧（白做），要么在快设备上白白多等。`onPreDraw` 是「这一帧马上要画了」的真实信号。

## 验证（已完成）

```
# 双 flavor release 编译
:app:assembleMobileArm64_v8aRelease   BUILD SUCCESSFUL in 4m 23s
:app:assembleLeanbackArm64_v8aRelease BUILD SUCCESSFUL in 4m 38s

# 单元测试
:app:testMobileArm64_v8aDebugUnitTest --tests "*StartupBudgetTest"
BUILD SUCCESSFUL — 6 tests / 0 failures
```

**包内容核对**（防止「编译过了但东西没进去」）：

- `aapt2 dump resources`：`layout/view_skeleton_vod`（`0x7f0d0133`）在包内。
- R8 `mapping.txt`：`ViewSkeletonVodBinding skeleton -> t` 保留（证明骨架屏被真实引用，未被 shrink 掉）。
- dex 字符串：`index_History_cid_createTime` 在；内嵌 v38 identity hash `b061ae09d03a91bde272cbb2276239d8` 在；**旧污染 hash `3ca4b376...` 不在**。
- 说明：`StartupBudget` 类本身在 release 包中不存在，属**预期**——其生产侧唯一引用是 `static final long DEFER_AFTER_FIRST_FRAME_MS`（编译期常量 `80L`，被 javac 内联），其余方法仅单测使用，故被 R8 判定为死代码移除。功能不缺失。

**真机验证**（HUAWEI SCM-W09 平板，adb `GYH9X20331X09073`）：

- **真实升级路径**：装 v37 release（`WebHTV-mobile-20261111-mpvfix.apk`）→ 覆盖安装 v38 release → 冷启动**无崩溃**、无 `Room cannot verify the data integrity`、应用存活。
- 启动 `am start -W`：`TotalTime 398ms`、`Status ok`。
- **真实数据确定性迁移**：真实 v37 库（`Keep:1 / Track:2 / Config:1`）执行 `MIGRATION_37_38` 后 `user_version 37→38`、History 索引集合与 Room 期望完全一致（`sqlite_autoindex_History_1` + `index_History_cid_createTime`）、三张表行数全部保留。

## 调试过程中撞到的坑（供后人避免）

开发期间在设备上反复安装过**多个 v38 中间 debug 包**，其中一版是「版本号 38 + 双索引」（hash `3ca4b376`）。删掉冗余索引后新包 hash 变为 `b061ae09`，设备上残留的库由旧包写入 → Room 发现「同版本号、指纹不符」→ 启动即崩：

```
Room cannot verify the data integrity. Looks like you've changed schema but forgot to update the version number.
Expected identity hash: b061ae09...  found: 3ca4b376...
```

**这是开发过程污染，不是产品缺陷**：`3ca4b376` 在任何交付包中都不存在（已从候选 APK 的 dex 中逐一搜过，均无）。真实用户从任何已发布版本（v37）升级走的是正常迁移，已用真实数据验证通过。**教训**：同一版本号下不要发布两种 schema；改 schema 必须同时改版本号，或确保中间产物不外流。

## 未完成 / 已知局限

| 工单要求项 | 状态 | 说明 |
|---|---|---|
| 启动任务并行化 | ✅ 已做 | 见 `App.java` / `HomeActivity.java` |
| Room 索引优化 | ✅ 已做 | `MIGRATION_37_38` |
| 首页骨架屏 | ✅ 已做 | `view_skeleton_vod.xml` + `VodFragment` |
| `baseline.prof/profm` 埋点 | ❌ 未做 | 因而**没有首屏耗时的量化对比**。当前只有分段 `SpiderDebug` 日志（release 包按设计不输出），需在 debuggable 构建上抓。 |
| 常用站点配置预热 | ❌ 未做 | |
| WebView 预创建 | ❌ 未做 | 注意 `StartupBudget.mayCreateWebViewBeforeFirstScreen()` 目前**恒为 `false`**；若日后要做预创建，必须同时改这个判定并让单测失败提醒，不能只改实现。 |

- **无真机首屏耗时对比**：本轮验证的是「不崩、能进、迁移正确」，**未量化提速幅度**。要给出可信数字，需补 `baseline.prof` 或至少一轮 debuggable 包的冷启动分段日志对比。
- 骨架屏的**视觉观感未经用户确认**：它复用真实卡片形状，但真机上「像不像内容」需要人看一眼。
- 平板验证环境是「删库重装后新建的空库」，与手机上的存量数据不同；存量数据路径已用**离线确定性迁移**覆盖，但未在手机上做过覆盖安装。

## 下一步

1. 由用户在手机上覆盖安装本轮交付包，确认历史/收藏完好、首屏观感可接受。
2. 若需要「快了多少」的数字，补 `baseline.prof` 埋点后做一轮冷启动对比。
3. 剩余三项（站点预热、WebView 预创建、prof 埋点）另开一单。
