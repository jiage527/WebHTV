package com.fongmi.android.tv.player.gif;

/**
 * GIF 录制的参数推导：帧率、帧数、画布尺寸与帧延时的换算。
 *
 * <p>这些换算被单独拆出来是因为它们决定了<b>峰值内存</b>：每帧在压成调色板下标前
 * 都以 {@code int[]}（4 字节/像素）保存在内存里，因此画布尺寸和帧数是必须一起约束的
 * 参数。把公式集中在这里既便于单测，也避免录制逻辑里散落魔数。
 *
 * <p>不依赖 Android 类型，可直接在 JVM 单测里验证。
 */
public final class GifRecordPlan {

    /** 录制帧率。GIF 的帧延时以 1/100 秒为单位，10 帧/秒正好是 10cs，能整除。 */
    public static final int FPS = 10;
    /** 输出画布的最大宽度。录屏 GIF 不需要原始分辨率，320 已足够看清画面。 */
    public static final int MAX_WIDTH = 320;
    /** 可选录制时长（毫秒）。 */
    public static final int[] DURATIONS_MS = {3000, 5000, 10000};
    /** 帧数硬上限，用于兜住内存：100 帧 × 320×180 像素 ≈ 23MB。 */
    public static final int MAX_FRAMES = 100;
    /**
     * 帧延时下限（1/100 秒）。
     *
     * <p>主流浏览器对小于 2cs 的延时会忽略并改用 10cs，因此这里不产生更短的延时，
     * 免得文件在浏览器里比预期慢十倍。
     */
    public static final int MIN_DELAY_CS = 2;

    private GifRecordPlan() {
    }

    /** 该时长对应的帧数，钳制在 1–{@link #MAX_FRAMES}。 */
    public static int frameCount(int durationMs) {
        return frameCount(durationMs, FPS);
    }

    public static int frameCount(int durationMs, int fps) {
        if (durationMs <= 0 || fps <= 0) return 1;
        long frames = Math.round(durationMs / 1000.0 * fps);
        return (int) Math.min(MAX_FRAMES, Math.max(1, frames));
    }

    /** 单帧延时（1/100 秒）。 */
    public static int delayCs(int fps) {
        if (fps <= 0) return MIN_DELAY_CS;
        return Math.max(MIN_DELAY_CS, Math.round(100f / fps));
    }

    /** 按源尺寸等比缩放到不超过 {@code maxWidth} 的宽度，至少 1 像素。 */
    public static int targetWidth(int sourceWidth, int sourceHeight, int maxWidth) {
        if (sourceWidth <= 0) return 1;
        if (sourceHeight <= 0 || maxWidth <= 0) return Math.max(1, Math.min(sourceWidth, Math.max(1, maxWidth)));
        // 只缩不放：源画面比上限小时保持原宽度，避免把小画面插值放大白白增大文件。
        return Math.min(sourceWidth, maxWidth);
    }

    /**
     * 按源宽高比把宽度换算成高度。
     *
     * <p>偶数不强制：GIF 没有这个限制，硬凑偶数会引入不必要的信箱边。
     */
    public static int targetHeight(int sourceWidth, int sourceHeight, int targetWidth) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0) return 1;
        // 必须用浮点做等比换算：先整除再取整会把 568.9 截成 568，竖屏画面会逐渐变形。
        long scaled = Math.round((double) targetWidth * sourceHeight / sourceWidth);
        return (int) Math.max(1, scaled);
    }

    /** 由源尺寸直接得到输出画布宽度。 */
    public static int canvasWidth(int sourceWidth, int sourceHeight) {
        return targetWidth(sourceWidth, sourceHeight, MAX_WIDTH);
    }

    /** 由源尺寸直接得到输出画布高度。 */
    public static int canvasHeight(int sourceWidth, int sourceHeight) {
        return targetHeight(sourceWidth, sourceHeight, canvasWidth(sourceWidth, sourceHeight));
    }

    /** 录制进度换算出的剩余秒数，向上取整，用于按钮上的倒计时。 */
    public static int remainingSeconds(int recordedFrames, int totalFrames) {
        int remaining = Math.max(0, totalFrames - recordedFrames);
        return (int) Math.ceil(remaining / (double) FPS);
    }
}
