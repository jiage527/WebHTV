# P18 去掉冷启动那闪一下的开屏图

> 用户原话：「应用开屏的时候会有开屏图片 是一闪而过的，我截不到图。你应该可以在文件里面找到。把它去除掉。」
>
> 需求边界：只去掉这张图，不新增任何功能、不改启动流程。

## 1. 那张图是什么

不是独立的启动页 Activity，也不是动画，而是 **Android 的启动窗口（starting window）背景**：
应用进程还没起来时，系统先按 Manifest 里给 Activity 指定的主题画一帧，这帧就叫 windowBackground。
它必然「一闪而过」——进程起来、界面画出来后立刻被替换，所以截不到。

| 位置 | 内容 |
|---|---|
| `app/src/mobile/AndroidManifest.xml:27` | `HomeActivity` 用 `@style/Theme.Splash` |
| `app/src/leanback/AndroidManifest.xml:46` | `HomeActivity` 用 `@style/Theme.Splash` |
| `app/src/mobile/res/values/styles.xml` | `Theme.Splash` 的 `android:windowBackground` = `@drawable/mobile_startup_window_background` |
| `app/src/leanback/res/values/styles.xml` | `Theme.Splash` 的 `android:windowBackground` = `@drawable/startup_window_background` |
| `app/src/mobile/res/drawable/mobile_startup_window_background.xml` | layer-list，`<bitmap gravity="fill" src="@drawable/mobile_startup"/>` |
| `app/src/main/res/drawable/startup_window_background.xml` | 底色 `@color/startup_background`(#4A4740) + `<bitmap gravity="fill" src="@drawable/startup_logo"/>` |

图片本体（`drawable-nodpi` 下的大图）：

| 文件 | 尺寸 | 大小 | 用途 |
|---|---|---|---|
| `app/src/mobile/res/drawable-nodpi/mobile_startup.png` | 2160×3840 | 693,813 B | 手机竖版海报（深蓝底 + WebHTV 字 + 金色电视图标） |
| `app/src/main/res/drawable-nodpi/startup_logo.png` | 3840×2160 | 815,371 B | 通用横版海报（同款内容） |
| `app/src/leanback/res/drawable-nodpi/startup_logo.png` | 3840×2160 | 815,371 B | 与上一份字节相同，画面同名资源覆盖 |

真机实拍（平板 SCM-W09，Android 10，改动前）：整屏是那张竖版海报，`gravity="fill"` 把它按屏幕比例拉满。

## 2. 怎么去掉的

原则：**主题里只留一层纯色**，图片资源与 layer-list 一并删掉，不靠「把图换成透明图」这种留垃圾的做法。

| # | 文件 | 改动 |
|---|---|---|
| 1 | `app/src/mobile/res/values/styles.xml` | `Theme.Splash` 的 `windowBackground` 由 `@drawable/mobile_startup_window_background` 改为 `@color/black` |
| 2 | `app/src/leanback/res/values/styles.xml` | `Theme.Splash` 不再覆盖 `windowBackground`（`Theme.Base` 本来就是 `@color/black`），样式保留为 `<style name="Theme.Splash" parent="Theme.Base" />` |
| 3 | `app/src/main/res/drawable/startup_window_background.xml` | 删除（仅被电视端 Theme.Splash 引用） |
| 4 | `app/src/mobile/res/drawable/mobile_startup_window_background.xml` | 删除（仅被手机端 Theme.Splash 引用） |
| 5 | `app/src/main/res/drawable-nodpi/startup_logo.png` | 删除 |
| 6 | `app/src/leanback/res/drawable-nodpi/startup_logo.png` | 删除 |
| 7 | `app/src/mobile/res/drawable-nodpi/mobile_startup.png` | 删除 |
| 8 | `app/src/main/res/values/colors.xml` | 删除已无引用的 `startup_background`（#4A4740） |

删图前用 `grep -rn 'startup_logo|mobile_startup|startup_window_background|startup_background' app/src --include=*.xml --include=*.java`
确认引用点只有上表这些；删完再跑一次，输出「无残留」，编译 `BUILD SUCCESSFUL`。

## 3. 为什么选黑色

启动窗口这一层要尽量和「下一帧」同色，否则会从「黑 → 亮」二次闪。

- 电视端 `Theme.Base` 的 `windowBackground` 本来就是 `@color/black`，保持继承即可；
- 手机端首页背景现在是 P15 的品牌插画（深蓝，四角约 `#010913`），黑色与它几乎同色，过渡看不出来。

没有选「浅色/主题色」是因为首页永远是深色壁纸，浅色反而会闪一下白。

## 4. 没能去掉的那一层（如实说明）

Android 12 及以上，**系统还会自己画一个启动画面**：应用图标 + 背景色，这是平台行为（`SplashScreen` API），
不是我们这张图。它同样一闪而过，但内容只有桌面图标，没有 WebHTV 海报。

要连这一层也去掉，得在 `values-v31/styles.xml` 里把 `android:windowSplashScreenAnimatedIcon` 设成透明，
那样冷启动会变成「纯黑一小段 → 直接进界面」。本单按用户要求只处理我们自己放的那张图，没有动系统层。

## 5. 验证

- `grep` 确认全仓无 `startup_logo` / `mobile_startup` / `startup_window_background` / `startup_background` 残留；
- 双 flavor 编译 `BUILD SUCCESSFUL`；
- 全量单测与既有基线一致（41 项环境相关失败，不新增）；
- 双端 release 出包后，用 `aapt2 dump resources` / zip 直读确认 `assets` 之外再无那三张海报；
- 真机冷启动抓帧（`am force-stop` → `am start` 后立刻 `screencap`）：改动前是海报，改动后应是纯黑。

## 6. 影响面

- 启动流程、首屏耗时、`StartupBudget` 的调度**完全没动**——这里改的只是进程起来之前那一帧的画法；
- APK 变小：两张 3840×2160 / 2160×3840 的 PNG（合计约 2.2 MB 未压缩前）不再进包；
- 想恢复：`git revert` 本提交即可（图片在历史里）。
