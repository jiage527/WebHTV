package com.fongmi.android.tv.player.gif;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 录制参数换算的验证。重点是<b>内存约束</b>与<b>尺寸钳制</b>：帧数是内存的主要
 * 放大器，而画面尺寸和宽高比一旦算错就会写出扭曲或超大的文件。
 */
public class GifRecordPlanTest {

    @Test
    public void frameCountFollowsDurationAndFps() {
        assertEquals(30, GifRecordPlan.frameCount(3000));
        assertEquals(50, GifRecordPlan.frameCount(5000));
        assertEquals(100, GifRecordPlan.frameCount(10000));
    }

    @Test
    public void frameCountHonoursTheMemoryCap() {
        // 10 秒已到上限，20 秒不能再翻倍，否则内存直接翻番。
        assertEquals(GifRecordPlan.MAX_FRAMES, GifRecordPlan.frameCount(10000));
        assertEquals(GifRecordPlan.MAX_FRAMES, GifRecordPlan.frameCount(60000));
    }

    @Test
    public void degenerateInputsStillYieldOneFrame() {
        assertEquals(1, GifRecordPlan.frameCount(0));
        assertEquals(1, GifRecordPlan.frameCount(-100));
        assertEquals(1, GifRecordPlan.frameCount(1000, 0));
    }

    @Test
    public void delayIsHundredthsOfASecond() {
        assertEquals(10, GifRecordPlan.delayCs(10));
        assertEquals(4, GifRecordPlan.delayCs(25));
    }

    @Test
    public void delayNeverGoesBelowTheBrowserFloor() {
        // 浏览器会把 <2cs 的延时当 10cs，所以不能产生 1cs。
        assertEquals(GifRecordPlan.MIN_DELAY_CS, GifRecordPlan.delayCs(100));
        assertEquals(GifRecordPlan.MIN_DELAY_CS, GifRecordPlan.delayCs(0));
    }

    @Test
    public void wideSourceIsScaledDownToTheCanvasLimit() {
        assertEquals(GifRecordPlan.MAX_WIDTH, GifRecordPlan.canvasWidth(1920, 1080));
        assertEquals(180, GifRecordPlan.canvasHeight(1920, 1080));
    }

    @Test
    public void narrowSourceIsNotUpscaled() {
        // 小画面放大只会让文件更大而不会更清晰。
        assertEquals(160, GifRecordPlan.canvasWidth(160, 120));
        assertEquals(120, GifRecordPlan.canvasHeight(160, 120));
    }

    @Test
    public void portraitSourceKeepsItsAspectRatio() {
        int width = GifRecordPlan.canvasWidth(1080, 1920);
        int height = GifRecordPlan.canvasHeight(1080, 1920);
        assertEquals(GifRecordPlan.MAX_WIDTH, width);
        assertEquals(569, height);
        // 高应当明显大于宽，证明确实按竖屏比例算。
        assertTrue(height > width);
    }

    @Test
    public void squareSourceStaysSquare() {
        assertEquals(320, GifRecordPlan.canvasWidth(640, 640));
        assertEquals(320, GifRecordPlan.canvasHeight(640, 640));
    }

    @Test
    public void degenerateSizesNeverProduceZeroPixels() {
        // 宽或高为 0 会让 GIF 编码器直接报错，这里必须兜住。
        assertEquals(1, GifRecordPlan.targetWidth(0, 100, 320));
        assertEquals(1, GifRecordPlan.targetHeight(0, 100, 320));
        assertEquals(1, GifRecordPlan.targetHeight(100, 0, 320));
        assertTrue(GifRecordPlan.canvasHeight(1920, 0) >= 1);
    }

    @Test
    public void remainingSecondsRoundsUpSoTheButtonNeverShowsZeroEarly() {
        assertEquals(5, GifRecordPlan.remainingSeconds(0, 50));
        assertEquals(1, GifRecordPlan.remainingSeconds(45, 50));
        assertEquals(0, GifRecordPlan.remainingSeconds(50, 50));
        assertEquals(0, GifRecordPlan.remainingSeconds(80, 50));
    }

    @Test
    public void durationsAreWithinTheFrameCap() {
        for (int duration : GifRecordPlan.DURATIONS_MS) {
            int frames = GifRecordPlan.frameCount(duration);
            assertTrue("duration " + duration, frames >= 1 && frames <= GifRecordPlan.MAX_FRAMES);
        }
    }
}
