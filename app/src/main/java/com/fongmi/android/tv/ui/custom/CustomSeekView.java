package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.Util;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.TimeBar;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.cache.PlaybackDiskBufferStore;
import com.fongmi.android.tv.player.preview.SeekPreviewExtractor;
import com.fongmi.android.tv.setting.SeekPreviewSetting;
import com.github.catvod.crawler.SpiderDebug;

import java.util.HashMap;
import java.util.Map;
import java.util.Formatter;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class CustomSeekView extends FrameLayout implements Player.Listener, TimeBar.OnScrubListener {

    private static final int MAX_UPDATE_INTERVAL_MS = 1000;
    private static final int MIN_UPDATE_INTERVAL_MS = 200;
    private static final long DISK_RANGE_GAP_TOLERANCE_MS = 2000;
    private static final long PAUSED_BUFFER_LOG_INTERVAL_MS = 5000;
    private static final long SEEK_POSITION_TOLERANCE_MS = 1500;
    private static final long SEEK_POSITION_HOLD_TIMEOUT_MS = 10000;

    private final StringBuilder timeBuilder = new StringBuilder();
    private final Formatter timeFormatter = new Formatter(timeBuilder, Locale.getDefault());
    private final TextView positionView;
    private final TextView durationView;
    private final DefaultTimeBar timeBar;
    private final Runnable runnable;
    /** 拖动进度条时的画面预览；抽帧与缓存都在 extractor 里。 */
    private SeekPreviewExtractor previewExtractor;
    private SeekPreviewView previewView;
    /** 最近一次请求的键，用于丢弃已经过期的抽帧回调。 */
    private String previewKey;
    /** 当前拖动位置，用于把异步返回的帧摆到正确位置。 */
    private long previewPosition;
    private long currentDuration;
    private long currentPosition;
    private long currentBuffered;
    private boolean scrubbing;
    private boolean attached;
    private Player player;
    private Player progressPlayer;
    private long lastPausedBufferLogAtMs;
    private long lastPausedBufferedPosition;
    private long lastBufferReadLogAtMs;
    private long lastBufferReadEffectiveMs = -1;
    private long pendingSeekPosition = C.TIME_UNSET;
    private long pendingSeekOrigin = C.TIME_UNSET;
    private long pendingSeekDeadlineMs;

    public CustomSeekView(Context context) {
        this(context, null);
    }

    public CustomSeekView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CustomSeekView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        LayoutInflater.from(context).inflate(R.layout.view_control_seek, this);
        positionView = findViewById(R.id.position);
        durationView = findViewById(R.id.duration);
        timeBar = findViewById(R.id.timeBar);
        runnable = this::updateProgress;
        timeBar.addListener(this);
        resetView();
    }

    public void setPlayer(Player player) {
        if (this.player != null) this.player.removeListener(this);
        clearPendingSeek();
        this.player = player;
        if (player != null) player.addListener(this);
        if (attached) updateTimeline();
    }

    /**
     * Uses the in-process engine for timeline reads while commands continue to
     * go through the MediaController. MediaSession does not continuously send
     * buffered-position-only changes while playback is paused, which otherwise
     * leaves native-player buffer bars stale until playback resumes.
     */
    public void setProgressPlayer(@Nullable Player progressPlayer) {
        if (this.progressPlayer == progressPlayer) return;
        this.progressPlayer = progressPlayer;
        lastPausedBufferLogAtMs = 0;
        lastPausedBufferedPosition = 0;
        if (SpiderDebug.isEnabled()) {
            SpiderDebug.log("playback-progress", "action=bind source=%s",
                    progressPlayer == null ? "controller" : "direct-engine");
        }
        if (attached) updateTimeline();
    }

    @Nullable
    private Player getProgressPlayer() {
        return progressPlayer != null ? progressPlayer : player;
    }

    private String stringToTime(long time) {
        return Util.getStringForTime(timeBuilder, timeFormatter, time);
    }

    private void updateTimeline() {
        Player progress = getProgressPlayer();
        if (!attached || progress == null) return;
        long duration = progress.getDuration();
        if (duration < 0) duration = 0;
        currentDuration = duration;
        setKeyTimeIncrement(duration);
        timeBar.setDuration(duration);
        durationView.setText(stringToTime(duration));
        updateProgress();
    }

    private void updateProgress() {
        removeCallbacks(runnable);
        Player progress = getProgressPlayer();
        if (!attached || progress == null) return;
        long reportedPosition = Math.max(0, progress.getCurrentPosition());
        long position = stableSeekPosition(reportedPosition);
        long buffered = effectiveBufferedPosition(progress);
        long duration = progress.getDuration();
        if (duration < 0) duration = 0;
        if (duration != currentDuration) {
            currentDuration = duration;
            setKeyTimeIncrement(duration);
            timeBar.setDuration(duration);
            durationView.setText(stringToTime(duration));
        }
        if (position != currentPosition) {
            currentPosition = position;
            if (!scrubbing) {
                timeBar.setPosition(position);
                positionView.setText(stringToTime(position));
            }
        }
        if (buffered != currentBuffered) {
            currentBuffered = buffered;
            timeBar.setBufferedPosition(buffered);
        }
        logPausedBufferProgress(progress, position, buffered);
        if (progress.isPlaying()) {
            postDelayed(runnable, delayMs(progress, position));
        } else {
            postDelayed(runnable, MAX_UPDATE_INTERVAL_MS);
        }
    }

    private long effectiveBufferedPosition(Player progress) {
        long buffered = Math.max(0, progress.getBufferedPosition());
        String mediaKey = PlaybackDiskBufferStore.mediaKey(progress.getCurrentMediaItem());
        long diskBuffered = PlaybackDiskBufferStore.process().contiguousEnd(
                mediaKey, buffered, DISK_RANGE_GAP_TOLERANCE_MS);
        long effective = Math.max(buffered, diskBuffered);
        long duration = progress.getDuration();
        long bounded = duration > 0 ? Math.min(effective, duration) : effective;
        long now = SystemClock.elapsedRealtime();
        if (SpiderDebug.isEnabled()
                && (bounded != lastBufferReadEffectiveMs
                || now - lastBufferReadLogAtMs >= PAUSED_BUFFER_LOG_INTERVAL_MS)) {
            lastBufferReadLogAtMs = now;
            lastBufferReadEffectiveMs = bounded;
            SpiderDebug.log("playback-progress",
                    "action=buffer-read source=%s positionMs=%d nativeBufferedMs=%d diskBufferedMs=%d effectiveBufferedMs=%d key=%s",
                    progress == player ? "controller" : "direct-engine",
                    Math.max(0, progress.getCurrentPosition()), buffered, diskBuffered, bounded,
                    Integer.toHexString(mediaKey == null ? 0 : mediaKey.hashCode()));
        }
        return bounded;
    }

    private void logPausedBufferProgress(Player progress, long position, long buffered) {
        if (progress.isPlaying() || buffered <= lastPausedBufferedPosition) return;
        long now = SystemClock.elapsedRealtime();
        if (lastPausedBufferLogAtMs > 0
                && now - lastPausedBufferLogAtMs < PAUSED_BUFFER_LOG_INTERVAL_MS) return;
        lastPausedBufferLogAtMs = now;
        lastPausedBufferedPosition = buffered;
        if (SpiderDebug.isEnabled()) {
            SpiderDebug.log("playback-progress",
                    "mode=paused source=%s positionMs=%d bufferedPositionMs=%d bufferedDurationMs=%d",
                    progress == player ? "controller" : "direct-engine",
                    position, buffered, Math.max(0, buffered - position));
        }
    }

    private void resetView() {
        clearPendingSeek();
        positionView.setText("00:00");
        durationView.setText("00:00");
        timeBar.setPosition(currentPosition = 0);
        timeBar.setDuration(currentDuration = 0);
        timeBar.setBufferedPosition(currentBuffered = 0);
    }

    private void setKeyTimeIncrement(long duration) {
        if (duration > TimeUnit.HOURS.toMillis(3)) {
            timeBar.setKeyTimeIncrement(TimeUnit.MINUTES.toMillis(5));
        } else if (duration > TimeUnit.MINUTES.toMillis(30)) {
            timeBar.setKeyTimeIncrement(TimeUnit.MINUTES.toMillis(1));
        } else if (duration > TimeUnit.MINUTES.toMillis(15)) {
            timeBar.setKeyTimeIncrement(TimeUnit.SECONDS.toMillis(30));
        } else if (duration > TimeUnit.MINUTES.toMillis(10)) {
            timeBar.setKeyTimeIncrement(TimeUnit.SECONDS.toMillis(15));
        } else if (duration > 0) {
            timeBar.setKeyTimeIncrement(TimeUnit.SECONDS.toMillis(10));
        }
    }

    private long delayMs(Player progress, long position) {
        float speed = progress.getPlaybackParameters().speed;
        long mediaTimeUntilNextFullSecondMs = 1000 - position % 1000;
        long mediaTimeDelayMs = Math.min(timeBar.getPreferredUpdateDelay(), mediaTimeUntilNextFullSecondMs);
        long delayMs = (long) (mediaTimeDelayMs / Math.max(speed, 0.1f));
        return Util.constrainValue(delayMs, MIN_UPDATE_INTERVAL_MS, MAX_UPDATE_INTERVAL_MS);
    }

    private void seekToTimeBarPosition(long positionMs) {
        beginPendingSeek(positionMs);
        player.seekTo(positionMs);
        updateProgress();
        player.play();
    }

    public void previewSeekPosition(long positionMs) {
        Player progress = getProgressPlayer();
        long duration = currentDuration > 0 ? currentDuration : progress == null ? 0 : Math.max(0, progress.getDuration());
        long position = duration > 0 ? Util.constrainValue(positionMs, 0, duration) : Math.max(0, positionMs);
        removeCallbacks(runnable);
        if (!scrubbing) clearPendingSeek();
        scrubbing = true;
        timeBar.setPosition(position);
        positionView.setText(stringToTime(position));
    }

    public void commitSeekPreview(long positionMs) {
        Player progress = getProgressPlayer();
        long duration = currentDuration > 0 ? currentDuration : progress == null ? 0 : Math.max(0, progress.getDuration());
        long position = duration > 0 ? Util.constrainValue(positionMs, 0, duration) : Math.max(0, positionMs);
        scrubbing = false;
        beginPendingSeek(position);
        removeCallbacks(runnable);
        postDelayed(runnable, MIN_UPDATE_INTERVAL_MS);
    }

    private void beginPendingSeek(long targetPosition) {
        Player progress = getProgressPlayer();
        long origin = progress == null ? currentPosition : Math.max(0, progress.getCurrentPosition());
        pendingSeekOrigin = origin;
        pendingSeekPosition = Math.max(0, targetPosition);
        pendingSeekDeadlineMs = SystemClock.elapsedRealtime() + SEEK_POSITION_HOLD_TIMEOUT_MS;
        currentPosition = pendingSeekPosition;
        timeBar.setPosition(pendingSeekPosition);
        positionView.setText(stringToTime(pendingSeekPosition));
    }

    private long stableSeekPosition(long reportedPosition) {
        if (pendingSeekPosition == C.TIME_UNSET) return reportedPosition;
        if (SystemClock.elapsedRealtime() >= pendingSeekDeadlineMs || hasReachedPendingSeek(reportedPosition)) {
            clearPendingSeek();
            return reportedPosition;
        }
        return pendingSeekPosition;
    }

    private boolean hasReachedPendingSeek(long reportedPosition) {
        long delta = pendingSeekPosition - pendingSeekOrigin;
        if (Math.abs(delta) <= SEEK_POSITION_TOLERANCE_MS) {
            return Math.abs(reportedPosition - pendingSeekPosition) <= SEEK_POSITION_TOLERANCE_MS;
        }
        if (delta > 0) return reportedPosition >= pendingSeekPosition - SEEK_POSITION_TOLERANCE_MS;
        return reportedPosition <= pendingSeekPosition + SEEK_POSITION_TOLERANCE_MS;
    }

    private void clearPendingSeek() {
        pendingSeekPosition = C.TIME_UNSET;
        pendingSeekOrigin = C.TIME_UNSET;
        pendingSeekDeadlineMs = 0;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        updateTimeline();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        removeCallbacks(runnable);
        hidePreview();
        // 线程池与位图缓存按实例持有，必须显式释放，否则每次进播放页都会积下一个
        // 空闲线程和几十 MB 缩略图。视图重新挂回时会自动重建。
        releasePreviewExtractor();
        super.onDetachedFromWindow();
    }

    @Override
    public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
        resetView();
        // 换集/换源后旧帧全部失效，否则会出现「进度是新的、画面是旧的」。
        clearPreviewFrames();
        hidePreview();
    }

    @Override
    public void onScrubStart(@NonNull TimeBar timeBar, long position) {
        clearPendingSeek();
        scrubbing = true;
        positionView.setText(stringToTime(position));
        updatePreview(position);
    }

    @Override
    public void onScrubMove(@NonNull TimeBar timeBar, long position) {
        positionView.setText(stringToTime(position));
        updatePreview(position);
    }

    @Override
    public void onScrubStop(@NonNull TimeBar timeBar, long position, boolean canceled) {
        scrubbing = false;
        hidePreview();
        if (!canceled) seekToTimeBarPosition(position);
        else updateProgress();
    }

    // ------------------------------------------------------------------ 预览

    /**
     * 拖动时刷新画面预览。
     *
     * <p>预览需要播放地址，而 {@code MediaController} 只能给出当前的
     * {@link MediaItem}，因此地址与请求头都从 {@code requestMetadata} 里取
     * （已确认该字段会随 session 的 Bundle 一起传递）。直播与取不到地址时
     * 直接不显示，不报错。
     */
    private void updatePreview(long positionMs) {
        if (!SeekPreviewSetting.isEnabled()) return;
        MediaItem item = currentMediaItem();
        if (item == null || item.requestMetadata.mediaUri == null || currentDuration <= 0) return;
        SeekPreviewView view = ensurePreviewView();
        if (view == null) return;
        SeekPreviewExtractor extractor = ensurePreviewExtractor();
        String url = item.requestMetadata.mediaUri.toString();
        previewPosition = positionMs;
        // 先把已有帧（如果有）立刻画上，避免拖动时闪空白；没有则显示占位框。
        view.show(extractor.peek(url, positionMs), previewLeft(positionMs, view), previewTop(view));
        previewKey = extractor.request(url, headers(item), positionMs, (key, frame) -> {
            // 回调是异步的：已被后续拖动超越的帧、或已经松手的帧，直接丢弃。
            if (!scrubbing || !key.equals(previewKey) || !isAttachedToWindow()) return;
            if (frame == null) view.hide();
            else view.show(frame, previewLeft(previewPosition, view), previewTop(view));
        });
    }

    private SeekPreviewExtractor ensurePreviewExtractor() {
        if (previewExtractor == null) previewExtractor = new SeekPreviewExtractor();
        return previewExtractor;
    }

    private void releasePreviewExtractor() {
        if (previewExtractor == null) return;
        previewExtractor.release();
        previewExtractor = null;
    }

    private void clearPreviewFrames() {
        if (previewExtractor != null) previewExtractor.clear();
    }

    private MediaItem currentMediaItem() {
        Player progress = getProgressPlayer();
        return progress == null ? null : progress.getCurrentMediaItem();
    }

    /** 把 requestMetadata.extras 里的字符串重新组装成请求头。 */
    private static Map<String, String> headers(MediaItem item) {
        Map<String, String> result = new HashMap<>();
        android.os.Bundle extras = item.requestMetadata.extras;
        if (extras == null) return result;
        for (String key2 : extras.keySet()) {
            String value = extras.getString(key2);
            if (value != null) result.put(key2, value);
        }
        return result;
    }

    /** 懒创建浮层：只有真正用到预览时才加 View，不影响不使用该功能的耗电与内存。 */
    private SeekPreviewView ensurePreviewView() {
        if (previewView != null) return previewView;
        ViewGroup parent = getPreviewParent();
        if (parent == null) return null;
        previewView = new SeekPreviewView(getContext());
        parent.addView(previewView);
        return previewView;
    }

    /**
     * 浮层挂在 Activity 的内容根上。
     *
     * <p>不挂在进度条自己那行里：那行高度只够放进度条，浮层会被裁掉。挂在内容根上
     * 既不会被裁剪，也能盖在视频上面（控制层本来就画在视频之上）。
     */
    private ViewGroup getPreviewParent() {
        View root = getRootView();
        View content = root.findViewById(android.R.id.content);
        if (content instanceof ViewGroup group) return group;
        return root instanceof ViewGroup group ? group : null;
    }

    /**
     * 计算浮层左上角坐标。
     *
     * <p>进度条与浮层不在同一个 View 树分支上，所以统一换算到窗口坐标再相减，
     * 直接用 {@code getLeft()/getTop()} 会因参照系不同而错位。
     */
    private int previewLeft(long positionMs, SeekPreviewView view) {
        ViewGroup parent = getPreviewParent();
        if (parent == null || timeBar.getWidth() <= 0) return 0;
        int[] bar = locationInWindow(timeBar);
        int[] host = locationInWindow(parent);
        float fraction = currentDuration <= 0 ? 0f : Math.min(1f, Math.max(0f, (float) positionMs / currentDuration));
        float center = bar[0] - host[0] + timeBar.getWidth() * fraction;
        int margin = SeekPreviewView.gap(getContext());
        int max = Math.max(margin, parent.getWidth() - view.previewWidth() - margin);
        return Math.max(margin, Math.min(Math.round(center - view.previewWidth() / 2f), max));
    }

    /** 浮层默认贴在进度条上方；进度条太靠顶时改放下方，避免被裁掉。 */
    private int previewTop(SeekPreviewView view) {
        ViewGroup parent = getPreviewParent();
        if (parent == null) return 0;
        int[] bar = locationInWindow(timeBar);
        int[] host = locationInWindow(parent);
        int gap = SeekPreviewView.gap(getContext());
        int above = bar[1] - host[1] - view.previewHeight() - gap;
        if (above >= 0) return above;
        return Math.min(bar[1] - host[1] + timeBar.getHeight() + gap, Math.max(0, parent.getHeight() - view.previewHeight()));
    }

    private static int[] locationInWindow(View view) {
        int[] location = new int[2];
        view.getLocationInWindow(location);
        return location;
    }

    private void hidePreview() {
        previewKey = null;
        if (previewView != null) previewView.hide();
    }
}
