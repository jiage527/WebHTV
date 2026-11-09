package com.fongmi.android.tv.player.gif;

import android.app.Activity;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.ScreenshotCapture;
import com.fongmi.android.tv.utils.Task;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把播放中的画面录成 GIF。
 *
 * <p>复用截图那套 {@link ScreenshotCapture#capture} 取帧（SurfaceView 走
 * {@code PixelCopy}，因此录到的只有画面、没有控件浮层），然后按固定间隔连续取帧，
 * 最后交给 {@link GifEncoder} 编码成文件。
 *
 * <p>三个关键约束：
 * <ul>
 *   <li><b>取帧必须在主线程</b>：{@code PixelCopy} 要求如此。因此录制期间由一个
 *       主线程 {@link Handler} 按间隔反复取帧，每次取完立刻缩放到输出画布并转成
 *       {@code int[]}，然后丢弃位图。</li>
 *   <li><b>色表必须在写完所有帧之后才能定</b>：GIF 用的是全局色表，所以帧数据先
 *       全部留在内存里，编码阶段才统计配色。</li>
 *   <li><b>编码必须在工作线程</b>：量化 + LZW 是纯 CPU 的活，放主线程会卡住播放。</li>
 * </ul>
 *
 * <p>实例持有一次录制的全部状态，因此同一个实例只用于一次录制；重复录制请新建实例。
 */
public final class GifRecorder {

    /** 录制文件名前缀，与截图保持一致，便于在相册里归到一起。 */
    private static final String PREFIX = "WebHTV_";
    private static final String SUFFIX = ".gif";
    private static final String MIME = "image/gif";
    /** 调色板色数。256 是 GIF 上限，录屏用满才能保住渐变与字幕边缘。 */
    private static final int MAX_COLORS = 256;

    public interface Listener {

        /** 每取到一帧就在主线程回调，用于刷新倒计时之类的进度显示。 */
        void onProgress(int recordedFrames, int totalFrames);

        /** 录制结束。成功时 {@code file} 非空，失败时 {@code error} 非空。 */
        void onFinished(@Nullable File file, @Nullable Throwable error);
    }

    private final Activity activity;
    private final View surface;
    private final Listener listener;
    private final int totalFrames;
    private final int delayCs;
    private final int canvasWidth;
    private final int canvasHeight;
    private final List<int[]> frames;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final Runnable tick;
    private long startedAtMs;
    private int targetFrame;

    private GifRecorder(Activity activity, View surface, int sourceWidth, int sourceHeight, int durationMs, Listener listener) {
        this.activity = activity;
        this.surface = surface;
        this.listener = listener;
        this.totalFrames = GifRecordPlan.frameCount(durationMs);
        this.delayCs = GifRecordPlan.delayCs(GifRecordPlan.FPS);
        this.canvasWidth = GifRecordPlan.canvasWidth(sourceWidth, sourceHeight);
        this.canvasHeight = GifRecordPlan.canvasHeight(sourceWidth, sourceHeight);
        this.frames = new ArrayList<>(totalFrames);
        this.tick = this::grabFrame;
    }

    /**
     * 开始录制。
     *
     * @param durationMs 录制时长，见 {@link GifRecordPlan#DURATIONS_MS}
     * @return 录制器实例，可用于 {@link #stop()} 提前结束
     */
    public static GifRecorder start(Activity activity, View surface, int durationMs, Listener listener) {
        GifRecorder recorder;
        try {
            recorder = new GifRecorder(activity, surface, surface.getWidth(), surface.getHeight(), durationMs, listener);
        } catch (RuntimeException error) {
            // 尺寸拿不到时无法建立画布，直接把失败交给调用方而不是抛出。
            listener.onFinished(null, error);
            return null;
        }
        recorder.schedule();
        return recorder;
    }

    private void schedule() {
        // 先立刻取一帧，否则第一帧要等到第一个间隔之后，开头会缺画面。
        startedAtMs = SystemClock.uptimeMillis();
        handler.post(tick);
    }

    /** 提前结束录制并开始编码，已取到的帧照常成片。 */
    public void stop() {
        if (stopped.getAndSet(true)) return;
        handler.removeCallbacks(tick);
        encode();
    }

    /** 放弃本次录制，不产出文件。用于页面销毁等场景。 */
    public void cancel() {
        if (stopped.getAndSet(true)) return;
        handler.removeCallbacks(tick);
        frames.clear();
    }

    private void grabFrame() {
        if (stopped.get()) return;
        ScreenshotCapture.capture(activity, surface, (bitmap, error) -> {
            if (stopped.get()) {
                if (bitmap != null) bitmap.recycle();
                return;
            }
            if (bitmap != null) {
                try {
                    frames.add(toPixels(bitmap));
                } finally {
                    // 位图只在取帧阶段需要，缩放完就回收，否则整段录制会攒下几十张原图。
                    bitmap.recycle();
                }
            }
            targetFrame++;
            listener.onProgress(frames.size(), totalFrames);
            if (frames.size() >= totalFrames) {
                stop();
                return;
            }
            handler.postDelayed(tick, delayToNextFrame());
        });
    }

    /**
     * 距离下一帧还需要等多久。
     *
     * <p>{@code PixelCopy} 本身就要花掉十几毫秒，如果每次都固定延时一个帧间隔，
     * 实际帧率会明显低于标称值，GIF 播放时就会比录制时快、动作发飘。这里按
     * <b>墙钟</b>对齐第 {@code targetFrame} 帧应当出现的时刻，迟到时立即补下一帧
     * （延时为 0），因此节奏不会因为取帧变慢而累积偏差。
     */
    private long delayToNextFrame() {
        long dueAtMs = startedAtMs + Math.round(targetFrame * 1000.0 / GifRecordPlan.FPS);
        return Math.max(0, dueAtMs - SystemClock.uptimeMillis());
    }

    /** 缩放到输出画布并取回 ARGB 像素；已经是对应尺寸时直接读。 */
    private int[] toPixels(Bitmap bitmap) {
        Bitmap scaled = bitmap;
        if (bitmap.getWidth() != canvasWidth || bitmap.getHeight() != canvasHeight) {
            scaled = Bitmap.createScaledBitmap(bitmap, canvasWidth, canvasHeight, true);
        }
        int width = scaled.getWidth();
        int height = scaled.getHeight();
        int[] pixels = new int[width * height];
        scaled.getPixels(pixels, 0, width, 0, 0, width, height);
        if (scaled != bitmap) scaled.recycle();
        return pixels;
    }

    private void encode() {
        List<int[]> captured = new ArrayList<>(frames);
        frames.clear();
        if (captured.isEmpty()) {
            listener.onFinished(null, new IOException("No frames captured"));
            return;
        }
        int framesCount = captured.size();
        Task.execute(() -> {
            File file = null;
            Throwable error = null;
            try {
                file = write(captured);
            } catch (Throwable throwable) {
                error = throwable;
            }
            File saved = file;
            Throwable failure = error;
            App.post(() -> listener.onFinished(saved, failure));
        });
    }

    /**
     * 编码写盘。阻塞：只应在工作线程调用。
     *
     * <p>帧数不足预定数量时（例如提前 {@link #stop()}）会让每帧延时保持原值，
     * 于是成片时长自然缩短，而不是把有限的帧摊薄成慢动作。
     */
    private File write(List<int[]> captured) throws IOException {
        GifPalette palette = GifPalette.build(captured, MAX_COLORS);
        File directory = targetDirectory();
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Cannot create " + directory);
        File file = new File(directory, fileName());
        int width = canvasWidth;
        int height = canvasHeight;
        try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(file), 1 << 16)) {
            GifEncoder encoder = new GifEncoder(output, width, height, palette, true);
            for (int[] pixels : captured) encoder.writeFrame(palette.index(pixels), delayCs);
            encoder.finish();
            output.flush();
        }
        MediaScannerConnection.scanFile(App.get(), new String[]{file.getAbsolutePath()}, new String[]{MIME}, null);
        return file;
    }

    private static File targetDirectory() {
        // 与截图落到同一目录，用户在一个地方就能找到两种产物。
        if (Setting.hasFileAccess()) return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ScreenshotCapture.DIRECTORY);
        return new File(App.get().getExternalFilesDir(Environment.DIRECTORY_PICTURES), ScreenshotCapture.DIRECTORY);
    }

    private static String fileName() {
        return PREFIX + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + SUFFIX;
    }

    public int totalFrames() {
        return totalFrames;
    }
}