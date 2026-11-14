# P16 设置页 GitHub 图标改指向二开项目主页

日期：2026-11-14
用户原话：「图片中我标注的位置的图标点击会跳到原作者的主页。把它改成跳转到我自己的这个项目地址。」

## 1. 那个图标原来是哪来的

设置 →「增强功能」页右上角有两个图标：GitHub 与 CNB（`app/src/mobile/res/layout/…` / `app/src/leanback/res/layout/activity_setting_enhance.xml:35` 的 `@+id/githubRepo`）。

点击处理在两端各一份：

- 手机：`app/src/mobile/java/com/fongmi/android/tv/ui/fragment/SettingEnhanceFragment.java:56`
- 电视：`app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingEnhanceActivity.java:51`

两处原来都是 `Github.getRepoUrl()`，它返回的是 `BuildConfig.UPDATE_REPO_URL`——那是**更新源坐标**（构建时由 `-PupdateRepoUrl` / `-PupdateGithubRepo` 决定，默认指向上游的 `fish2018/webhtv`），所以点进去看到的是上游仓库，不是本项目的地址。

## 2. 改法：新增一个只表示「项目主页」的常量

`app/src/main/java/com/fongmi/android/tv/utils/Github.java` 新增：

```java
/**
 * 二开项目自己的公开地址。
 * 图标只负责「我的项目主页」，与更新源（UPDATE_GITHUB_REPO / UPDATE_CNB_BASE）互不影响。
 */
public static final String PROJECT_URL = "https://github.com/jiage527/WebHTV";

public static String getProjectUrl() {
    return PROJECT_URL;
}
```

两端 `URL_GITHUB` 改为 `Github.getProjectUrl()`。

**为什么不去改 `BuildConfig.UPDATE_REPO_URL` 的默认值**：`UPDATE_GITHUB_REPO` / `UPDATE_CNB_BASE` 决定「版本检查 / 新包下载」从哪拉（`Updater.java` 用它拼 `api.github.com/repos/{repo}/releases…`）。把仓库坐标改成二开仓库，会顺带把更新链路也切过去；而本仓库是私有仓库，未带 token 的 `api.github.com` 请求会直接 404，等于把「检查更新」弄坏。图标只要换个落地页，没必要牵动更新源，所以单独开一个常量。

CNB 图标（`https://cnb.cool/fish2035/ext`）是扩展仓库，与「作者主页」无关，未改。

## 3. 验证

- 双 flavor `compileJavaWithJavac`：`BUILD SUCCESSFUL`。
- 全量单测：与既有基线一致（无新增失败）。
- 装真机点那个图标，浏览器落到 `https://github.com/jiage527/WebHTV`。
- APK 内核对：`classes*.dex` 里能搜到 `jiage527` 字符串（确认常量真的进包，没有被优化掉）。

## 4. 已知边界

- 该 GitHub 仓库目前是**私有仓库**：已登录且有权限的账号能打开，其他人会看到 404。若以后想让所有人都能点开这个图标，需要把仓库设为公开，或把 `PROJECT_URL` 换成一个公开页面。
- 只改了这一个图标的落地页；关于页、更新逻辑、CNB 图标都没动。
