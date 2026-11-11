# T2-14 SMB / WebDAV 网络存储挂载

- 工单：T2-14（P0，5 人日），报表条目 A-04
- 分支：`main`
- 状态：**已实施**（双端编译通过 + `netdisk` 包 66 项单测全通过 + 全量单测既有失败项未增加）

## 1. 要实现的用户能力

在设置里多出一处「网络存储」入口，用户可以：

- 手动添加一条 SMB（含 Windows 共享、群晖等）或 WebDAV（含 Nextcloud、坚果云、Alist 之类）账号，填写地址、端口、共享名、目录、用户名、密码；
- 添加前先「测试连接」，成功与否当场给出提示，失败不丢已填内容；
- 进入账号后像本地文件管理器一样逐层浏览目录、返回上级；
- 点视频/音频/字幕文件直接播放，与播放本地文件走同一套播放器与进度条（可拖动 seek）。

另外，用户在别处（地址栏、外部应用分享、`.strm` 文件内容）直接粘贴 `smb://…` 或 `webdav://…` 地址也能播。

## 2. 为什么不能直接把 `smb://` 交给播放器

这是本单最关键的技术约束，已用随包二进制实证，不是推测：

```
$ LC_ALL=C grep -a -c -- "smb://"              app/src/arm64_v8a/assets/mpv-libs/arm64-v8a/libmpv.so
0
$ LC_ALL=C grep -a -c -- "libsmbclient"        app/src/arm64_v8a/assets/mpv-libs/arm64-v8a/libmpv.so
0
$ LC_ALL=C grep -a -c -- "ftp://"              app/src/arm64_v8a/assets/mpv-libs/arm64-v8a/libmpv.so
1
```

两个 ABI（`arm64-v8a`、`armeabi-v7a`）结果一致。也就是说随包 mpv **没有编译 SMB 支持**（连字符串常量都不存在，说明整个 protocol 没有编进去）。Exo 侧也没有 SMB 的 `DataSource`。

因此本单的做法是：**把远端文件经本机 HTTP 服务中转出去**，交给播放器的是一个普通的 `http://127.0.0.1:<port>/netdisk?…` 地址。这与项目里既有的 `file://` → `/file/`、`assets://` → `/` 是同一套路子，播放器与两套 UI 一行都不用改。

## 3. 改动清单

### 新增 `app/src/main/java/com/fongmi/android/tv/netdisk/`（14 个文件）

纯逻辑层（不依赖 Android，可单测）：

| 文件 | 职责 |
| --- | --- |
| `NetDiskType.java` | 协议枚举 SMB / WEBDAV，默认端口、路径分隔符、scheme 别名映射 |
| `NetDiskCodec.java` | RFC 3986 百分号编解码；`normalize/join/parent/name`；媒体扩展名判定 |
| `NetDiskAccount.java` | 账号模型；**`toJson()` 刻意不序列化密码** |
| `NetDiskEntry.java` | 目录项；目录优先、忽略大小写的排序 |
| `NetDiskUrl.java` | `smb://[域;]用户:密码@主机[:端口]/共享/路径` 与 `webdav[s]://…` 的解析；代理地址双向换算 |
| `NetDiskProxyPath.java` | 端点前缀 `/netdisk` 的唯一来源（纯逻辑层与 Android 层共用） |
| `NetDiskRange.java` | RFC 7233 单区间解析 |
| `NetDiskClient.java` | 客户端接口 |
| `NetDiskStore.java` | 账号清单 + 按主机/共享/用户名匹配已存凭据 |
| `NetDiskSession.java` | 按账号缓存客户端 |

需要 Android 的部分：

| 文件 | 职责 |
| --- | --- |
| `NetDiskCrypto.java` | AES/GCM 加密口令；AndroidKeyStore 优先，不可用时退到本地密钥文件 |
| `NetDiskCredentialStore.java` | 账号清单存 `files/net_disk_accounts.json`，口令单独加密存 SharedPreferences |
| `SmbNetDiskClient.java` | smbj 实现（20s 建连超时、30s 读超时、多协议协商、匿名回退、5000 条上限） |
| `WebDavNetDiskClient.java` | sardine（`OkHttpSardine`）列举 + 自带 OkHttp 请求带 `Range` 取流 |

### 新增 `server/process/NetDiskProxy.java`

`/netdisk?account=<id>&path=<相对路径>`。要点：

- 如实实现 `Range`：可满足的区间回 `206` + `Content-Range`，否则回 `200`；越界回 `416` + `Content-Range: bytes */total`；始终带 `Accept-Ranges: bytes`。**这一条是「能不能拖进度条」的关键**：不回正确的 206，Exo 与 mpv 都会判定该源不可 seek。
- 异常时 `NetDiskSession.invalidate(accountId)`，让下次请求重建连接，避免用户改完密码仍复用坏会话。

### 修改既有文件

- `server/Nano.java`：注册 `NetDiskProxy`。
- `utils/UrlUtil.java`：新增 `convertNetDisk(url)`，`smb://`/`webdav://` → 本机代理地址。
- `player/Source.java`：在 `fetch(...)` 的返回处接入 `resolveNetDisk(...)`。
  **选这里的原因**：它是所有播放地址的必经出口，同时盖住两条来源——用户直接粘贴的 smb 地址，以及本地 `.strm` 文件里写着的 smb 路径（`Strm` 抽取器会把文件内容当作地址返回）。转换失败时退回原地址，让播放器报它自己的错。
- `ui/dialog/NetDiskDialog.java`：`BottomSheetDialog` 面板（账号列表 / 目录浏览 / 账号表单）。
- 两端设置页接线：mobile `SettingEnhanceFragment.java` + `fragment_setting_enhance.xml`，leanback `SettingEnhanceActivity.java` + `activity_setting_enhance.xml`，各自加一行「网络存储」及条数摘要。
- `strings.xml` × 3（`values`、`values-zh-rCN`、`values-zh-rTW`）各 23 条，全部有引用点。

## 4. 关键决策

- **不建数据库、不做迁移**（项目硬约束）：账号清单用 JSON 文件，循环模式之外的少量状态走 `Prefers`。
- **密码绝不进明文清单**：`NetDiskAccount.toJson()` 不写密码字段；口令经 AES/GCM 加密后单独存；加密不可用时**宁可不保存**也不落明文。
- **共享名是边界**：`smb://nas/media/../../etc` 这类地址必须在切出共享名之后才展开 `..`，否则会把 `media` 自身弹掉、跑到共享之外。这一条最初写错，由单测抓出后修正。
- **路径段用 `%20` 而非 `+`**：`URLEncoder` 会把空格编成 `+`，在 URI 路径段里是非法的；`+` 也会被 NanoHTTPD 还原成空格，导致真实文件名为 `a+b.mkv` 的文件永远打不开。
- **代理查询串要先摘掉 `?`**：`queryOf` 若不摘，首个键名会变成 `?account`，整个查询都读不出来（同为单测抓出的缺陷）。
- **`NetDiskSession.client()` 不做口令覆盖**：地址里已带凭据时，不能再用存储里的空值覆盖它。

## 5. 验证

```
$ ./gradlew :app:compileMobileArm64_v8aDebugJavaWithJavac \
            :app:compileLeanbackArm64_v8aDebugJavaWithJavac --offline
BUILD SUCCESSFUL

$ ./gradlew :app:testMobileArm64_v8aDebugUnitTest --tests "com.fongmi.android.tv.netdisk.*" --offline
BUILD SUCCESSFUL
```

| 测试类 | 项数 | 结果 |
| --- | --- | --- |
| `NetDiskUrlTest` | 27 | 全通过 |
| `NetDiskRangeTest` | 17 | 全通过 |
| `NetDiskStoreTest` | 22 | 全通过 |
| **合计** | **66** | **全通过** |

单测先跑出 3 项失败，逐一核对后确认：其中 2 项是**真实缺陷**（共享名边界、代理查询串首键），已改生产代码；1 项是我自己写错的期望值（`start=100` 配未知总长时本就该是 partial），已改测试。

## 6. 未覆盖的部分

- **无真机运行验证**（本机无连接设备）。SMB/WebDAV 的实际连通性、以及真实播放器的 seek 表现，需要有 NAS 的环境实测。
- 仅做单区间 `Range`；多段请求按 RFC 降级为整文件（播放器不会发多段请求）。
- 未做写入（上传/改名/删除），本单只要求浏览与播放。
