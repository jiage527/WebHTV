package com.fongmi.android.tv.utils;

import android.content.Context;
import android.content.res.Configuration;

import androidx.annotation.ColorInt;

import com.google.android.material.color.MaterialColors;

/**
 * 自绘面板（用代码搭界面的 BottomSheet）取色用。
 *
 * <p><b>为什么需要它。</b>这些面板的控件是 {@code new TextView(context)} 建出来的，
 * 拿不到 XML 里的主题属性，早先就直接写死白色（{@code 0xFFFFFFFF}）。而应用主题是
 * {@code Theme.Material3.DynamicColors.DayNight}——日间模式下面板背景变浅，
 * 白字就看不见了（夜间模式背景深、白字正常，所以问题只在白天暴露）。真机确认过：
 * 用户的原话是「下载管理，在日间模式下。看不清。在夜间模式下就好了」。
 *
 * <p><b>取的是主题色而不是判断日夜后写死两套颜色。</b>面板背景来自主题的
 * {@code colorSurface}（BottomSheet 的容器色），文字必须是同一个主题里的
 * {@code colorOnSurface}，这样「背景与文字」永远成对变化；自己按 uiMode 挑亮/暗两套常量，
 * 在动态取色（Android 12+ 会按壁纸改调色板）或用户换了主题时就会对不上。
 *
 * <p>取不到属性时按日夜退回一组安全值，而不是拍一个固定色：面板至少保证可读。
 * 电视端主题是 {@code Theme.Material3.Dark}，取出来的仍是浅色文字，观感与改动前一致。
 */
public final class ThemeColors {

    /** 取不到主题属性时的兜底：日间用深灰、夜间用浅色。 */
    @ColorInt
    private static final int FALLBACK_LIGHT = 0xFF202124;
    @ColorInt
    private static final int FALLBACK_DARK = 0xFFECEFF1;

    /** 行底色的叠加强度，对应原先写死的 {@code 0x11FFFFFF}（约 6.7%）。 */
    private static final float ROW_TINT = 0.067f;

    private ThemeColors() {
    }

    /** 主文字：标题、条目名、输入内容。 */
    @ColorInt
    public static int onSurface(Context context) {
        return MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, isLight(context) ? FALLBACK_LIGHT : FALLBACK_DARK);
    }

    /** 次要文字：路径、状态行、字段标签。 */
    @ColorInt
    public static int secondary(Context context) {
        return withAlpha(onSurface(context), 0.62f);
    }

    /** 小字：条目右侧的进度/大小。 */
    @ColorInt
    public static int detail(Context context) {
        return withAlpha(onSurface(context), 0.55f);
    }

    /** 面板底部动作文字（继续/删除等）。 */
    @ColorInt
    public static int action(Context context) {
        return withAlpha(onSurface(context), 0.82f);
    }

    /** 行的浅底色：用主题的表面色叠一点主文字色，日夜都成立。 */
    @ColorInt
    public static int rowBackground(Context context) {
        int surface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, isLight(context) ? 0xFFFFFFFF : 0xFF121212);
        return MaterialColors.layer(surface, onSurface(context), ROW_TINT);
    }

    /** 高亮（如播放队列里「正在播放」那一项）。 */
    @ColorInt
    public static int accent(Context context) {
        // colorPrimary 由 appcompat 声明（material 只声明 colorOnSurface / colorSurface 这一族），
        // 所以这里必须走 androidx.appcompat.R，写 com.google.android.material.R 会编不过。
        return MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0xFF1A73E8);
    }

    /**
     * 按比例降不透明度。
     *
     * <p>主题色本身是不透明的，直接拿来做次要文字会与主文字一样抢眼；分层靠透明度而不是
     * 换一个色号，这样在深浅两套主题下层次关系都一致。
     */
    @ColorInt
    private static int withAlpha(@ColorInt int color, float alpha) {
        int value = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (color & 0x00FFFFFF) | (value << 24);
    }

    private static boolean isLight(Context context) {
        int mask = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mask != Configuration.UI_MODE_NIGHT_YES;
    }
}
