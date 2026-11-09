package com.fongmi.android.tv.player.preview;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 拖动进度条时的画面抽取器。
 *
 * <p>调用方给一个播放位置，本类返回该位置附近的一帧（异步）。同一时间档只会
 * 解码一次，重复请求直接丢弃；所有解码在单线程里串行执行——多个
 * {@link MediaMetadataRetriever} 同时解码既会拖慢播放，也会显著增加内存占用。
 *
 * <p>{@link MediaMetadataRetriever} 会自己向服务器重新发起请求，因此只在用户
 * 真正拖动进度条时使用，不在播放过程中预取。
 *
 * <p>未做 Android 版本兼容包装：{@code close()} 需要 API 29，而本应用 minSdk 为 24，
 * 所以统一用 {@code release()}。
 */
public class SeekPreviewExtractor {

    /** 缩略图宽度上限（像素）。高度按原比例换算。 */
    private static final int THUMB_WIDTH = 320;
    /** 单张缩略图约 320x180x4B ≈ 230KB，这里限 8MB 左右。 */
    private static final int BITMAP_CACHE_BYTES = 8 * 1024 * 1024;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "seek-preview");
        thread.setDaemon(true);
        return thread;
    });
    private final SeekPreviewCache<String> keys = new SeekPreviewCache<>();    private final LruCache<String, Bitmap> frames = new LruCache<>(BITMAP_CACHE_BYTES) {
        @Override
        protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
            return value.getByteCount();
        }
    };
    private final AtomicBoolean closed = new AtomicBoolean();

    public interface Callback {

        /**
         * 抽到帧后回调（主线程）。
         *
         * @param key   缓存键，用于判断回调是否还对应当前请求
         * @param frame 抽到的画面；null 表示该位置无法取帧
         */
        void onFrame(String key, @Nullable Bitmap frame);
    }

    /**
     * 请求某个位置的画面。
     *
     * @return 缓存键；调用方可据此判断回调是否已经过期
     */
    public String request(@Nullable String url, @Nullable Map<String, String> headers, long positionMs, Callback callback) {
        String key = keys.key(url, positionMs);
        if (closed.get()) return key;
        Bitmap cached = frames.get(key);
        if (cached != null) {
            App.post(() -> callback.onFrame(key, cached));
            return key;
        }
        if (TextUtils.isEmpty(url) || !keys.begin(key)) return key;
        executor.execute(() -> {
            Bitmap frame = null;
            try {
                frame = grab(url, headers, keys.bucket(positionMs));
            } catch (Exception ignored) {
                // 取帧失败很常见（直播、加密流、服务器拒绝 Range），静默降级为不显示预览。
            }
            final Bitmap result = frame;
            if (closed.get()) return;
            if (result != null) frames.put(key, result);
            // 无论成功与否都要解除在途标记：失败时若不解，该时间档会永久阻塞，
            // 用户拖回同一位置时永远不会重试。
            keys.end(key);
            App.post(() -> callback.onFrame(key, result));        });
        return key;
    }

    /** 是否已经有该位置的缓存（用于拖动时同步画旧帧，避免闪空白）。 */
    @Nullable
    public Bitmap peek(@Nullable String url, long positionMs) {
        return frames.get(keys.key(url, positionMs));
    }

    public void clear() {
        frames.evictAll();
        keys.clear();
    }

    public void release() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdownNow();
        frames.evictAll();
        keys.clear();
    }

    /**
     * 抽帧。{@code release()} 必须放在 finally：{@code MediaMetadataRetriever} 持有
     * 解码器资源，泄漏会直接导致后续播放起不来。
     */
    private static Bitmap grab(String url, Map<String, String> headers, long positionMs) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            applyDataSource(retriever, url, headers);
            return frameAt(retriever, positionMs);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 按地址类型选择正确的 setDataSource 重载。
     *
     * <p>{@code Range} 头必须剔除：{@link MediaMetadataRetriever} 自己会发起
     * Range 请求，外部再带一个会导致部分服务器返回 400。
     */
    private static void applyDataSource(MediaMetadataRetriever retriever, String url, Map<String, String> headers) {
        Map<String, String> safe = new HashMap<>();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry == null || TextUtils.isEmpty(entry.getKey()) || entry.getValue() == null) continue;
                if ("Range".equalsIgnoreCase(entry.getKey())) continue;
                safe.put(entry.getKey(), entry.getValue());
            }
        }
        if (url.startsWith("content://")) retriever.setDataSource(App.get(), Uri.parse(url));
        else if (url.startsWith("file://")) retriever.setDataSource(Uri.decode(url.substring("file://".length())));
        else if (!url.contains("://")) retriever.setDataSource(url);
        else retriever.setDataSource(url, safe);
    }

    /**
     * 取某位置的一帧。
     *
     * <p>API 27 起可让系统直接解码到目标尺寸（省一次整帧分配），低版本只能取回
     * 原尺寸帧再由 {@link #scale} 缩小。先试就近关键帧（快），失败再试精确帧。
     */
    private static Bitmap frameAt(MediaMetadataRetriever retriever, long positionMs) {
        long positionUs = Math.max(0L, positionMs) * 1000L;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            Bitmap scaled = retriever.getScaledFrameAtTime(positionUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_WIDTH, THUMB_WIDTH);
            if (scaled == null) scaled = retriever.getScaledFrameAtTime(positionUs, MediaMetadataRetriever.OPTION_CLOSEST, THUMB_WIDTH, THUMB_WIDTH);
            if (scaled != null) return scaled;
        }
        Bitmap frame = retriever.getFrameAtTime(positionUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        if (frame == null) frame = retriever.getFrameAtTime(positionUs, MediaMetadataRetriever.OPTION_CLOSEST);
        return frame == null ? null : scale(frame);
    }

    private static Bitmap scale(Bitmap frame) {
        if (frame.getWidth() <= THUMB_WIDTH) return frame;
        int height = Math.max(1, Math.round(frame.getHeight() * (float) THUMB_WIDTH / frame.getWidth()));
        Bitmap scaled = Bitmap.createScaledBitmap(frame, THUMB_WIDTH, height, true);
        if (scaled != frame) frame.recycle();
        return scaled;
    }
}
