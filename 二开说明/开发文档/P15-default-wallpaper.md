# P15 默认背景（壁纸）换成品牌插画

日期：2026-11-14
用户原话：「把应用的默认背景改成这个你自己修改一下。让他在横屏下，竖屏下。都比较自然。不需要添加另外的功能，只需要把默认背景修改就可以。」

## 1. 「默认背景」在代码里到底是什么

`CustomWallView` 被 `BaseActivity`（手机端与电视端各一份）在 `onCreate` 里 **插到 `android.R.id.content` 的第 0 层**，尺寸 `MATCH_PARENT`：

- `app/src/mobile/java/com/fongmi/android/tv/ui/base/BaseActivity.java:61`
- `app/src/leanback/java/com/fongmi/android/tv/ui/base/BaseActivity.java:60`

也就是说它是**全应用每一页的共同背景**（不是启动图，也不是播放器背景）。它内部是一张 `scaleType="centerCrop"` 的 `ImageView`（`app/src/main/res/layout/view_wall.xml`），显示哪张图由 `Setting.getWall()` 决定：

- 内置设计壁纸 → `CustomWallView.getDesignResId(int wall)` 映射到 `wallpaper_design_*.webp`
- 内置纯色 → 直接画 `ColorDrawable`
- 用户自选/网络壁纸 → 缓存文件

改动前的出厂默认是 `Setting.WALL_DREAM_PURPLE`（梦幻紫霞，`wallpaper_design_22_dream_purple.webp`）。

## 2. 改动内容

| 位置 | 改动 |
|---|---|
| `app/src/main/java/com/fongmi/android/tv/setting/Setting.java` | 新增常量 `WALL_BRAND = 40`；`DEFAULT_WALLS` 数组首位插入它；`getWall()` 的默认值从 `WALL_DREAM_PURPLE` 改为 `WALL_BRAND`（连 WALL_GREEN / 旧纯色壁纸的兼容映射一起改）；新增一次性迁移 `migrateWallBrand()`；`getBuiltInWallColor(WALL_BRAND)` 返回 `0xFF2D9CD2`；`getBuiltInWallName(WALL_BRAND)` 返回「我的影视」 |
| `app/src/main/java/com/fongmi/android/tv/ui/custom/CustomWallView.java` | `getDesignResId` 增加 `case Setting.WALL_BRAND -> R.drawable.wallpaper_brand;`；`DEFAULT_WALL_COLOR` 从紫色改成品牌色（未知壁纸时的兜底色，避免闪一下紫） |
| `app/src/main/res/drawable-nodpi/wallpaper_brand.webp` | 新增，1920×1080（横屏 / 电视端） |
| `app/src/mobile/res/drawable-port/wallpaper_brand.webp` | 新增，1080×2400（手机竖屏） |

没有新增设置项、没有加新的壁纸选择入口；原来设置页的「切换壁纸」按钮仍然按 `DEFAULT_WALLS` 循环，只是循环的第一张变成了品牌图。

### 2.1 已经装过 App 的机器怎么办（一次性迁移）

实测发现：旧版本会把「当时的出厂默认」（`wall=22` 梦幻紫霞）真的写进 `Prefers`，所以**只改 `getWall()` 的默认值，对已经装过的手机完全无效**——第一次装上新包时，屏幕背景仍然是紫色。

所以 `getWall()` 里加了一次性迁移：

```java
private static int migrateWallBrand(int wall) {
    if (Prefers.getBoolean("wall_brand_migrated", false)) return wall;
    Prefers.put("wall_brand_migrated", true);
    if (wall != WALL_DREAM_PURPLE) return wall;   // 用户自己选过别的，不动
    Prefers.put("wall", WALL_BRAND);              // 还停在旧出厂默认，迁到品牌壁纸
    return WALL_BRAND;
}
```

只迁「还停在旧默认值」的机器；用户自己点过别的壁纸（含自定义图片、纯色、GIF）一律不动。迁移只跑一次（`wall_brand_migrated` 标记）。

## 3. 横竖屏为什么都自然

- **横屏**：源图 2032×1144，比例 1.7762，与 16:9 的 1.7778 只差 0.09%，缩到 1920×1080，不裁不拉；再整体压到 **0.65 明度 + 3px 高斯虚化**。压暗虚化是实机试出来的：这张图的高光是纯白蓝，满屏铺开时标题栏、分类标签、海报边缘全被压掉（第一版 0.86 明度不虚化，横屏下「韩剧」标签正好压在白色 logo 上）。
- **竖屏**：源图是横构图。`centerCrop` 在竖屏上会把左右两侧（圆环两侧的光带）全裁掉，硬拉伸又会把圆环压成椭圆。所以竖屏单独排版：
  1. 主体等比放大到整宽再以中心裁掉两侧（只裁到左右边缘的电影胶片带，不碰圆环），占屏宽 100%、高约 1040px；
  2. 上下用从源图边缘取色的竖向渐变铺底（`#010913 → #041E38 → #00060D`），主体上下各做 30% 的幂次羽化，两端本来就是同色系深藏青，所以看不到接缝；
  3. 整体再做一次上下压暗；最后统一乘 **0.82 明度**，与横屏那一版同一档，旋转屏幕时不会突然变亮。
- 资源按方向分流：`drawable-nodpi`（默认/横屏/电视）与 `drawable-port`（手机竖屏）。`centerCrop` 只做等比放大后裁切，任何比例都不会变形。

排版脚本：`C:\Users\qw191\Documents\deepseek-harness\default-workspace\gen_startup_bg.py`（`build_land` / `build_port`），输出两个 webp 后再拷进仓库。

## 4. 主题色

`CustomWallView.theme()` 会取壁纸的「代表色」写进 `Setting.getWallColor()`，在「主题色 = 跟随壁纸」时会给整个界面染色。品牌图取的是画面里高饱和像素的均值 **`#2D9CD2`**（青蓝），与图标同色系。

## 5. 验证

- 双 flavor `compileJavaWithJavac`：`BUILD SUCCESSFUL`。
- 单测：`Setting` / `CustomWallView` 无既有单测，全量跑一遍确认未新增失败。
- 真机：一加 ACE 3V（Android 16）装 release 包，竖屏与横屏各截一张首页，确认背景无变形、无接缝、文字可读。

## 6. 已知边界

- 只改**出厂默认**：用户如果以前手动切过壁纸，`Prefers` 里已存了 `wall` 值，不会被覆盖（这是「默认」应有的语义，不是缺陷）。
- 竖屏版本是重新排版的，不是原图的等比缩放，因此竖屏下上下两侧是纯渐变，没有原图的光带细节。
