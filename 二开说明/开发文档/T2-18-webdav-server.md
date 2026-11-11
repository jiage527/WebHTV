# T2-18 局域网 WebDAV/FTP 服务端（≡ E-05）

- 优先级：P2
- 人日：5
- 报表条目：B-18 局域网 WebDAV/FTP 服务端、E-05 WebDAV 服务端 / DLNA 内容声明增强
- 前置依赖：—
- 跨节合并说明：B-18 与 E-05 属不同章节但诉求同一件事（把本机媒体库经标准协议暴露出去、
  并让 DLNA 对端知道本机能播什么），所以一次实现、两节条目同时消项。
- guard id：`T2-18-webdav-server`，base HEAD `455db5268ff964afbcd3a002aea791a40314cc40`

## Recovery anchor

- 目标（验收标准）：手机/TV 端把本机文件经标准 WebDAV 协议共享给同一局域网内的设备
  （可被 Windows 资源管理器 / Finder / nPlayer / Infuse / Kodi 直接挂载，无需安装本 App）；
  同时修正 DLNA 渲染器「未声明任何可播格式」导致的搜不到/投不过去问题。
- 状态：**已完成**，双端编译通过 + 新增 46 项单测全通过 + 全量无新增失败。
- 下一步：无（本单已交付）。

## 一、工单正文与现状核实

工单原文：

> 局域网 WebDAV/FTP 服务端。前置依赖 —。
> 快/慢依据：`SocketHttpStreamServer` 已能 HTTP 提供媒体，扩为标准 WebDAV 供其他设备挂载。
> 跨节合并：局域网 WebDAV/FTP 服务端 / WebDAV 服务端 / DLNA 内容声明增强 —— 一次实现，两节条目同时消项。

**现状核实（读代码 + 反编译取证，未靠猜测）：**

1. `SocketHttpStreamServer` 是 **jupnp 的 `StreamServer` 实现**（UPnP 内部使用的 HTTP
   传输层，见 `DLNAServiceConfiguration.java:29-31` 的 `createStreamServer`），它只服务
   UPnP 自己的 SOAP/事件订阅，**不是**给用户挂载用的通用文件服务。工单说「扩为标准 WebDAV」
   的实际含义是：在本 App 已有的对外 HTTP 服务（`server/Nano.java`）上新增 WebDAV 端点。

2. `NanoHTTPD 2.3.1` 的 `Method` 枚举**已含** `PROPFIND` / `PROPPATCH` / `MKCOL` / `MOVE` /
   `COPY` / `LOCK` / `UNLOCK`（`javap fi.iki.elonen.NanoHTTPD$Method` 确认），
   `Response$Status` 已含 `MULTI_STATUS` / `CREATED` / `NO_CONTENT` / `CONFLICT` /
   `PRECONDITION_FAILED` / `PAYLOAD_TOO_LARGE` / `LENGTH_REQUIRED`（同样 `javap` 确认）。
   → **不必引入任何新依赖**，也不必自己写 socket 解析。

3. `Nano.serve()` 现以 `Process` 列表分发，注册顺序：`Action, Cache, DebugLogs, DriveCheck,
   Local, M3u8, Manage, Media, NetDiskProxy, Parse, PlaybackProgressApi, PlaybackRecordApi,
   Proxy, RemoteTrustSetup, WebResourceGateway`。已核对各 `isRequest` 前缀，`/dav` 与它们
   均不冲突。

4. **DLNA 侧存在实质缺陷**：`DLNARendererService.createConnectionManager()` 用
   `new DefaultServiceManager<>(service, ConnectionManagerService.class)`，实例由 jupnp 经
   **无参构造**创建；而 `javap -c` 反编译随包 `org.jupnp.support-3.0.4.jar` 可见无参构造最终
   走到 `new ProtocolInfos(new ProtocolInfo[0])`——即**空协议声明**。
   → DLNA 控制端问「你能播什么」会拿到空集合，进而判定本机什么都不支持。这正是
   「开着接收投屏、手机却搜不到或投不过去」的成因。这是 E-05 的真实缺口。

5. 项目已有 `ViewingRecordSyncStore.isLocalWriteEnabled()` → 403「本机 API 修改未开启」
   的先例（`server/process/PlaybackProgressApi.java:44`），说明「危险写入需用户显式开通」
   是本项目既定风格，本单沿用。

## 二、设计取舍

### 2.1 只共享一个专用子目录，而不是整块存储

共享根固定为 `/sdcard/WebDAV`（`WebDavRoot.NAME`）。

理由：服务把文件开放给局域网内**任何**设备。若以 `/sdcard` 为根，等于把其它应用留在共享存储
上的数据（下载内容、导出的配置、聊天缓存）一并开放——用户点一次开关并不会预期到这一点。
固定子目录的语义就是「共享这一个文件夹」，与 SMB 共享名是同一心智模型，也让「能访问什么」
有明确边界。

### 2.2 只读与可写分成两个独立开关，且写入默认关

- `WebDavSetting.isEnabled()` 默认 `false`
- `WebDavSetting.isWritable()` 默认 `false`，且**关闭服务时一并收回**（`putEnabled(false)`
  会调 `putWritable(false)`）

理由：多数人只想要「让对方来播我这儿的片子」，这只需只读。写入意味着对方能上传、覆盖、删除。
默认勾上等于替用户做了一个他不知情的决定。收回写入是为了避免「下次再打开时以为还是只读，
而写入权限其实被静默留着了」。

UI 用**多选列表**（`WebDavServerDialog`）而不是单个开关：这是两个独立意图，一个开关表达不了。
状态文字用**三态**（关 / 仅可读 / 可读写），因为用户回来查看时最需要一眼确认的就是「对方能不能写入」。

### 2.3 拒绝而非静默降级的几处

| 情形 | 处理 | 理由 |
|---|---|---|
| 未开启服务 | 回 404（不是 403） | 不回 403 是为了不向局域网暴露「这台设备存在但被关掉了」 |
| 双重编码的分隔符（`%252F`） | 403 | 这类段绝不是合法文件名，是逃逸尝试 |
| 控制字符（含 NUL/CR/LF） | 403 | 文件系统行为未定义 |
| 分块传输（`Transfer-Encoding: chunked`） | 411 | NanoHTTPD 2.3.1 不解析分块请求体，直接读流会把分块长度行当内容写进文件 |
| 声明的字节数与实收不符 | 400，且删除临时文件 | 截断的传输不能拿去顶掉用户原本完好的文件 |
| 根目录被 DELETE / MOVE | 403 | 误操作会让整个共享目录连同文件一起消失 |
| 上游目录不存在的 PUT / MKCOL | 409（协议要求，不替对方递归建） | 客户端据此知道要先建目录 |

### 2.4 两处「不做」

- **不做 `LOCK`/`UNLOCK`**：`DAV: 1` 已如实声明只支持基础能力。不实现加锁时，
  在 PROPFIND 里回 `<D:supportedlock/>`（空元素），让客户端知道无需锁即可读写；
  不回该属性的话部分客户端会认为服务端能力未知而拒绝写入。
- **不做 `Depth: infinity`**：规范允许拒绝，按 1 处理。一次性展开整棵目录树对媒体共享
  既无必要也极慢。

### 2.5 FTP 服务端未做

报表条目 B-18 标题含「WebDAV/FTP 服务端」。本单只实现 WebDAV，理由：WebDAV 已覆盖工单
真正的诉求（其他设备挂载本机媒体）；FTP 服务端需要另写控制/数据双通道与主动被动模式协商，
与 T2-17 的 FTP **客户端**是两套独立代码，收益重叠而成本另算。若后续需要，应单独立单。

## 三、实现清单

### 新增文件

**纯逻辑层（不依赖 Android，便于单测）**

| 文件 | 职责 |
|---|---|
| `webdav/WebDavPath.java` | 请求路径解析与安全校验、href 转义、可见名判定、排序 |
| `webdav/WebDavXml.java` | XML 转义、`<D:response>` 生成、`multistatus` 包装、RFC 1123 时间、按扩展名猜 MIME |
| `webdav/WebDavSetting.java` | 服务开关 + 写入开关（`Prefers`，无 DB 迁移） |
| `webdav/WebDavRoot.java` | 共享根解析，含 `getCanonicalPath` 纵深防御 |
| `dlna/DLNAProtocolDeclaration.java` | DLNA 可接收/可提供的协议声明 |

**服务端与界面**

| 文件 | 职责 |
|---|---|
| `server/process/WebDavServer.java` | WebDAV 端点（`/dav`）：OPTIONS / PROPFIND / GET / HEAD / PUT / MKCOL / DELETE / MOVE |
| `ui/dialog/WebDavServerDialog.java` | 设置对话框（多选：仅可读 / 可读写）+ 显示局域网挂载地址 |

### 修改既有

- `server/Nano.java`：注册 `new WebDavServer()`
- `service/DLNARendererService.java`：`createConnectionManager()` 改为显式传入
  `DLNAProtocolDeclaration.source()/sink()`
- `mobile/.../SettingEnhanceFragment.java` + `fragment_setting_enhance.xml`：新增 `webDavServer` 行
- `leanback/.../SettingEnhanceActivity.java` + `activity_setting_enhance.xml`：同上
- `res/values{,-zh-rCN,-zh-rTW}/strings.xml`：各新增 12 条

### 关键实现点

- **路径校验必须逐段解码**。若先整体解码再切段，`%2F` 会变成分隔符
  （`a%2F..%2F..%2Fb` 就能凭空造出上级目录）；逐段解码后每段都是不可再分的名字。
- **反斜杠也当分隔符**：Windows 客户端可能发 `..\..\x`；只按 `/` 切的话它会整段保留成一个
  「文件名」而被当成正常名字通过。
- **`..` 压到根为止**（不改写结果路径、也不越界），兼容会发 `a/../b` 的客户端。
- **PUT 直接读 `session.getInputStream()`**，不走 `parseBody`。两个原因：
  1. `Nano.serve()` 只为 POST 调 `parseBody`，PUT 时 `files` 恒为空——照它写会把每个上传
     都存成 0 字节文件，而客户端收到 201 以为成功（最坏的一类错误）。
  2. `parseBody` 会把整个请求体落地再 `map` 成 `MappedByteBuffer`，上传几 GB 视频会吃满地址空间。
- **上传先写 `.webdav-upload` 临时文件再 `renameTo`**：直接写目标的话，中断会留下半个文件把
  用户原本完整的那份顶掉；同目录内改名是原子的。
- **不用 `Path.copy`**：它内部把 `IOException` 吞掉了，失败时会静默返回「成功」。
- **只读 `Content-Length` 指定的字节数，不读到 EOF**：HTTP/1.1 默认复用连接，读到 EOF 会在
  keep-alive 连接上阻塞到超时（每个上传都先卡几十秒）。
- **复用既有 `NetDiskRange`** 解析 Range（已有 17 项单测钉住边界），不另写一份。
- **空文件单独处理**：`NetDiskRange` 对 `total=0` 会算出「长度 1」（服务网盘读接口时 `total=0`
  表示未知，那里是合理降级），但这里 `total` 已知，声明成 1 而实际发 0 字节会让客户端一直等。
- **DLNA `additionalInfo` 一律用 `*`**：该字段只能是 `*` 或 `名=值`；把编解码器名字裸写进去
  （如 `H.264`）不符合语法，严格的实现会丢弃整条声明。
- **DLNA 协议用 `http-get` 而非 `rtsp-rtp-udp`**：本机 `SocketHttpStreamServer` 只提供 HTTP 取流，
  声明 rtsp 会诱导控制端去尝试 UDP 推送，而那条路径根本没实现。

## 四、验证

| 项目 | 结果 |
|---|---|
| 双端编译 | `:app:compileMobileArm64_v8aDebugJavaWithJavac` + `:app:compileLeanbackArm64_v8aDebugJavaWithJavac` **BUILD SUCCESSFUL** |
| 新增单测 | `WebDavPathTest` 22、`WebDavXmlTest` 15、`DLNAProtocolDeclarationTest` 9 = **46/46 通过** |
| 全量单测 | **2128** 项，失败 **41** 项，与干净 HEAD 完全一致（既有环境失败，勿修） |

既有 41 项失败（与本单无关，干净 HEAD 可复现）：
`ExoCompressedAudioDirectPolicyTest` 35、`Avx3ExtractionTest` 3（实际名 `Avs3ExtractionTest`）、
`MpvPreloadControllerTest` 2、`MpvConfigStoreTest` 1；根因
`java.lang.RuntimeException: Method elapsedRealtime in android.os.SystemClock not mocked`。

### 单测抓出的问题（均为**测试期望写错**，0 生产缺陷）

1. `simplePath` / `queryAndFragmentAreCut`：我在期望里写了 `/dav/...`，但 `normalize` 拿到的
   已是去掉前缀的路径（`/dav` 由 `stripPrefix` 处理）。
2. `encodedParentIsRecognized`：`/..%2F..%2Fetc` 整段解码出 `./../etc`，段内含分隔符 →
   按逃逸拒绝，而我原期望它被压到 `etc`。拒绝比压到根更保守，故改期望并注明。
3. `httpDateUsesRfc1123` / `...RegardlessOfDefaultZone`：**代码是对的，是我把 epoch 算错了**
   （`1762646400000` 是 2025-11-09，2026-11-09 应为 `1794182400000`）。用 `date -u -d` 核对后修正。

### 未验证 / 需真实环境实测

- 真机与真实客户端的挂载：Windows 资源管理器「映射网络驱动器」、macOS Finder「连接服务器」、
  nPlayer / Infuse / Kodi 的实际列举与拖动播放。本机无连接设备，只做到编译 + 单测层面。
- 大文件（>4GB）上传与 seek 的实际表现。
- DLNA 声明生效后，各家控制端是否真的能搜到并投送成功（`ConnectionManagerService` 的行为
  已由反编译确认，但控制端侧的匹配策略无法在本机验证）。

## 五、风险与回滚

- **默认关闭**，因此合并后不改变任何既有行为；未开启时 `/dav` 对外等同不存在。
- 回滚：本单为单次原子提交，`git revert <commit>` 即可完全回退。
  新增的 `webdav` 包与 `WebDavServer` 无其它调用方；`Nano` 中删掉一行注册、
  `DLNARendererService` 恢复原 `DefaultServiceManager` 构造即可。
- 已知限制：不支持 `LOCK`（`DAV: 1` 已如实声明）、不支持 `Depth: infinity`、
  不支持 FTP 服务端（见 2.5）。
