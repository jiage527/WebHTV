# P20 默认背景换成用户提供的横版 / 竖版壁纸（第二版）

- 用户反馈：2026-11-14「之前的默认背景没搞好。图1是横版的默认背景。图2是竖版的默认背景 你不要裁切它。尽量保持它完整的图片结构。」
- 上一版（P15）的做法：用一张方形插画，横版缩放 + 压暗模糊、竖版放大 1.26 倍再从中间羽化裁切。用户认为「没搞好」——主体被裁、放大变形。
- 这一版：**直接使用用户给的两张原图，等比缩放，不裁、不补、不合成。**

## 1 素材与输出

| 用途 | 源图 | 输出资源 | 尺寸 | 体积 |
| --- | --- | --- | --- | --- |
| 横屏（电视 / 平板横 / 手机横） | 用户图 1，2732×1535（1.7798 ≈ 16:9） | `app/src/main/res/drawable-nodpi/wallpaper_brand.webp` | 1920×1080 | 见构建记录 |
| 竖屏（手机 / 平板竖） | 用户图 2，1535×2732（0.5619 = 9:16） | `app/src/mobile/res/drawable-port/wallpaper_brand.webp` | 1080×1920 | 见构建记录 |

- 缩放用 LANCZOS，源图与目标比例差都 <0.1%，等于纯缩放，**没有任何裁切**。
- WebP `quality=92, method=6`。

## 2 为什么不再做「补边」

试过 5 种补边方案，全部放弃，理由记录在此以免后人来一遍：

1. **上下镜像补足**：圆弧被镜像成「双重弧」，底部光柱镜像出菱形尖角。
2. **边缘行拉伸**：拉伸带在圆弧 / 光柱上形成可见的矩形块与硬接缝。
3. **边缘行拉伸 + 模糊**：矩形块变成软块，接缝仍在。
4. **逐行平均色垫底 + 羽化**：顶部出现与天空色调不一致的暗带。
5. **逐列平均色延续 + 线性叠化**：光柱被延长到圆弧之外（像光束穿过穹顶），底部仍有接缝。

结论：这张图是**有构图的插画**（穹顶弧 + 光柱 + 底部光晕 + 两侧胶片带），不是可平铺纹理，任何合成都会留下痕迹。**原图等比缩放反而是最干净的**。

## 3 各屏幕比例下的实际观感

`CustomWallView` 的 `ImageView` 是 `centerCrop`，所以：

| 屏幕 | 比例 | 显示方式 |
| --- | --- | --- |
| 电视 16:9 | 1.778 | 与素材同比例，**完全无裁切** |
| 平板横 16:10 | 1.600 | 左右各裁约 5%，圆弧两端与胶片带各少一点 |
| 手机竖 0.447（如 1240×2772） | — | 素材 0.5625 → 左右各裁约 10%，穹顶弧与光柱完整保留 |
| 平板竖 0.625 | — | 素材 0.5625 → 上下各裁约 5% |

「尽量保持完整结构」的落点就在这里：**结构与主体（穹顶、光柱、光晕）在所有比例下都完整**，被裁掉的只是两侧边缘的胶片带与留白。真要零裁切，只能用 `fitCenter` 留黑边，那比裁一点边难看，未采用。

## 4 改动清单

| 文件 | 改动 |
| --- | --- |
| `app/src/main/res/drawable-nodpi/wallpaper_brand.webp` | 换成用户横版原图（1920×1080） |
| `app/src/mobile/res/drawable-port/wallpaper_brand.webp` | 换成用户竖版原图（1080×1920） |

代码零改动：P15 引入的 `Setting.WALL_BRAND = 40`、`getDesignResId()` 映射、一次性迁移逻辑全部沿用，换的只是这两张资源。

## 5 验证

- 双 flavor 编译、双端 release 出包、全量单测（见 WORKLOG 记录）。
- 平板（HUAWEI SCM-W09 / Android 10）横竖屏截图：横屏穹顶弧在左、胶片带在上、光晕在中；竖屏穹顶在上、光柱居中、底部光晕；前景海报与文字均可读，无合成痕迹。

## 6 以后再换壁纸怎么做

1. 把新图放到上面两个路径（横版 → `drawable-nodpi`，竖版 → `mobile/res/drawable-port`），文件名保持 `wallpaper_brand`。
2. 横版按 16:9、竖版按 9:16 给，**不要预先加边**。
3. 不要写代码去裁 / 补 / 合成——`centerCrop` 自己会处理比例差。

## 7 顺带修掉的一处：旋转后壁纸不换图

改完资源后发现：应用内旋转屏幕（不重启）时，`CustomWallView` 仍挂着上一方向的那张图，被 `centerCrop` 裁成一团。
原因是 Activity 声明了 `configChanges` 自行处理旋转、不会重建，`ImageView` 上的 drawable 也就没人换。

在 `CustomWallView` 里补了一个 `onConfigurationChanged`：方向变了、且当前是内置壁纸时重载一次。

```java
@Override
protected void onConfigurationChanged(@NonNull Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    if (isInEditMode()) return;
    if (newConfig.orientation == lastOrientation) return;
    lastOrientation = newConfig.orientation;
    if (!isStaticBuiltInWall()) return;      // 用户自选的图片 / GIF / 视频不必重载
    removeCallbacks(refreshRunnable);
    post(refreshRunnable);
}
```

实测：竖屏启动 → 直接旋转到横屏（不重启），背景由竖版换成横版，无裁切残影。
