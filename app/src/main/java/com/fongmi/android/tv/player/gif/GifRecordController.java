package com.fongmi.android.tv.player.gif;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;

/**
 * 播放页上「录制 GIF」按钮的行为：点一下选时长开始录，再点一下提前结束。
 *
 * <p>两端播放页共享这一个控制器，避免把倒计时、二次点击、页面销毁时的清理逻辑
 * 各写一遍。只有按钮文字需要刷新，而按钮在页面重建后可能换实例，因此显示目标
 * 通过 {@link #attach(View)} 单独绑定，页面停止时可以安全摘掉。
 */
public final class GifRecordController {

    /** 按钮上显示录制状态的方式。 */
    public interface Display {

        void onGifRecording(String text);

        void onGifIdle();
    }

    @Nullable
    private GifRecorder recorder;
    @Nullable
    private Display display;

    /** 绑定按钮作为显示目标。 */
    public void attach(View button) {
        this.display = button == null ? null : new ViewDisplay(button);
    }

    public boolean isRecording() {
        return recorder != null;
    }

    /**
     * 按钮点击。录制中则提前结束，否则弹出时长选择。
     *
     * @param surface 取帧用的画面视图，与截图使用同一个
     */
    public void onClick(Activity activity, View surface, View button) {
        if (recorder != null) {
            // 二次点击 = 提前结束，已录到的帧照常成片。
            recorder.stop();
            return;
        }
        if (surface == null || surface.getWidth() <= 0 || surface.getHeight() <= 0) {
            Notify.show(R.string.play_gif_record_failed);
            return;
        }
        attach(button);
        CharSequence[] items = new CharSequence[GifRecordPlan.DURATIONS_MS.length];
        for (int i = 0; i < items.length; i++) {
            items[i] = activity.getString(R.string.play_gif_record_duration, GifRecordPlan.DURATIONS_MS[i] / 1000);
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.play_gif_record_title)
                .setItems(items, (dialog, which) -> start(activity, surface, GifRecordPlan.DURATIONS_MS[which]))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void start(Activity activity, View surface, int durationMs) {
        GifRecorder started = GifRecorder.start(activity, surface, durationMs, new GifRecorder.Listener() {
            @Override
            public void onProgress(int recordedFrames, int totalFrames) {
                if (display != null) display.onGifRecording(activity.getString(R.string.play_gif_record_countdown, GifRecordPlan.remainingSeconds(recordedFrames, totalFrames)));
            }

            @Override
            public void onFinished(@Nullable File file, @Nullable Throwable error) {
                recorder = null;
                if (display != null) display.onGifIdle();
                if (file != null) Notify.show(activity.getString(R.string.play_gif_record_saved, file.getName()));
                else if (error != null) Notify.show(R.string.play_gif_record_failed);
                else Notify.show(R.string.play_gif_record_cancelled);
            }
        });
        if (started == null) return;
        recorder = started;
        if (display != null) display.onGifRecording(activity.getString(R.string.play_gif_record_countdown, GifRecordPlan.remainingSeconds(0, started.totalFrames())));
    }

    /** 页面销毁：正在录制就放弃，避免回调打到已销毁的界面上。 */
    public void release() {
        display = null;
        if (recorder != null) {
            recorder.cancel();
            recorder = null;
        }
    }

    /** 直接把按钮的文字当作显示目标，录制时改成倒计时、结束后还原。 */
    private static final class ViewDisplay implements Display {

        private final TextView button;
        @Nullable
        private final CharSequence idleText;

        ViewDisplay(View button) {
            this.button = button instanceof TextView text ? text : null;
            this.idleText = this.button == null ? null : this.button.getText();
        }

        @Override
        public void onGifRecording(String text) {
            if (button != null) button.setText(text);
        }

        @Override
        public void onGifIdle() {
            if (button != null && idleText != null) button.setText(idleText);
        }
    }
}
