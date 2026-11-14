# P17 设置页 CNB 图标改为显示作者 QQ

日期：2026-11-14
用户原话：「这图片我标注的地方会跳转到原作者的也是一个放置源码的平台。你把它改成点击，就会显示 QQ 1913223463」

## 1. 那个图标原来是哪来的

设置 →「增强功能」页右上角有两个图标：GitHub（`@+id/githubRepo`）与 CNB（`@+id/cnbRepo`，就是用户圈出的那个花瓣形图标）。

CNB 图标的点击处理：

- 手机：`app/src/mobile/java/com/fongmi/android/tv/ui/fragment/SettingEnhanceFragment.java` 的 `onMenuItemClick` 里 `R.id.cnbRepo`
- 电视：`app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingEnhanceActivity.java` 的 `mBinding.cnbRepo.setOnClickListener`

两处原来都是 `openRepo(URL_CNB)`，`URL_CNB = "https://cnb.cool/fish2035/ext"` —— 上游作者的扩展仓库，所以点进去是别人的源码托管平台。

## 2. 改法

- 删掉 `URL_CNB` 常量（两端）。
- 新增 `private static final String CONTACT_QQ = "1913223463";`
- 两端各加一个 `showContact()`：

```java
new MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_WebHTV_LightDialog)
        .setTitle(R.string.contact_qq_title)
        .setMessage(getString(R.string.contact_qq, CONTACT_QQ))
        .setPositiveButton(R.string.dialog_positive, null)
        .setNeutralButton(R.string.contact_qq_copy, (dialog, which) -> Util.copy(CONTACT_QQ))
        .show();
```

**为什么用对话框而不是 Toast**：Toast 两秒就消失，号码还没读完就没了；对话框能停留、能选中「复制」，电视端用遥控器也能操作到按钮。

**为什么单独加「复制」按钮**：号码是 10 位数字，手抄容易错；`Util.copy()` 会顺带弹一句 `R.string.copied`（已复制）作为反馈。

## 3. 新增字符串（3 套 locale）

| key | en | zh-rCN | zh-rTW |
|---|---|---|---|
| `contact_qq_title` | Contact | 联系作者 | 聯絡作者 |
| `contact_qq` | `QQ: %1$s` | `QQ：%1$s` | `QQ：%1$s` |
| `contact_qq_copy` | Copy QQ | 复制 QQ | 複製 QQ |

其余语种回落到 `values/strings.xml` 的英文，属既有行为。

## 4. 验证

- 双 flavor `compileJavaWithJavac`：`BUILD SUCCESSFUL`。
- 真机（平板 SCM-W09）点该图标：弹出对话框，标题「联系作者」，正文「QQ：1913223463」，按钮「复制 QQ」「确定」。
- 点「复制 QQ」后剪贴板内容为 `1913223463`（`adb shell cmd clipboard` 或粘贴验证），并弹「已复制」。

## 5. 已知边界

- 只把这个图标的行为改成「显示联系方式」；GitHub 图标仍跳项目仓库（P16 改过）。
- 号码是写死在代码里的常量，改动需重新发包；如果以后要换，改 `CONTACT_QQ` 一处即可。
