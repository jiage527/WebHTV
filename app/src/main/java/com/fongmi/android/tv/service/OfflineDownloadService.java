package com.fongmi.android.tv.service;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadNotificationHelper;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.media3.exoplayer.scheduler.Scheduler;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.download.OfflineDownloadManager;
import com.fongmi.android.tv.download.OfflineDownloadStatus;
import com.fongmi.android.tv.utils.Notify;

import java.util.List;

/**
 * 离线下载的前台服务。
 *
 * <p>为什么必须有它：Media3 的 {@code DownloadManager} 本身只在进程里跑，用户一切到后台、
 * 系统回收进程，下载就断了；而且 Android 要求在后台持续做数据传输必须挂前台服务，
 * 否则随时被杀、还容易被判成后台偷跑。manifest 里用的是
 * {@code foregroundServiceType="dataSync"}（该权限 T2-18 起已在清单里）。
 *
 * <p>通知渠道单独建一个（{@link #CHANNEL}），不复用播放用的 "default"：下载通知要一直挂着
 * 显示进度，跟播放控制的通知混在一个渠道里，用户想单独静音/屏蔽其中一个就做不到。
 * 通知 id 取 {@code Notify.ID + 3}（+2 已被 ManageService 用掉），沿用项目既有约定。
 */
public class OfflineDownloadService extends DownloadService {

    private static final String CHANNEL = "offline_download";
    private static final int NOTIFICATION_ID = Notify.ID + 3;
    private static final long UPDATE_INTERVAL_MS = 1000L;

    private DownloadNotificationHelper helper;

    public OfflineDownloadService() {
        super(NOTIFICATION_ID);
    }

    public static void start(Context context) {
        try {
            DownloadService.start(context, OfflineDownloadService.class);
        } catch (Throwable ignored) {
        }
    }

    public static void pauseAll(Context context) {
        try {
            DownloadService.sendPauseDownloads(context, OfflineDownloadService.class, false);
        } catch (Throwable ignored) {
        }
    }

    public static void resumeAll(Context context) {
        try {
            DownloadService.sendResumeDownloads(context, OfflineDownloadService.class, false);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 暂停/继续某一条。
     *
     * <p>Media3 的 {@code DownloadManager} 只有全局的 {@code pauseDownloads()}，单条要靠
     * 「停因」表达：设成非 0 即停住，设回 0 即继续。理由见
     * {@link com.fongmi.android.tv.download.OfflineDownloadStatus#STOP_REASON_PAUSED}。
     */
    public static void setPaused(Context context, @Nullable String id, boolean paused) {
        if (id == null || id.isEmpty()) return;
        try {
            DownloadService.sendSetStopReason(context, OfflineDownloadService.class, id,
                    paused ? OfflineDownloadStatus.STOP_REASON_PAUSED : Download.STOP_REASON_NONE, false);
        } catch (Throwable ignored) {
        }
    }

    @NonNull
    @Override
    protected DownloadManager getDownloadManager() {
        return OfflineDownloadManager.get().downloadManager();
    }

    /**
     * 不排队等充电/等 WiFi：用户是主动点「下载」的，默认立刻开始。
     * 返回 null 表示不用平台 JobScheduler 调度（项目里也没有调度基础设施）。
     */
    @Nullable
    @Override
    protected Scheduler getScheduler() {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        helper = new DownloadNotificationHelper(this, CHANNEL);
        createChannel();
    }

    @Override
    public void onDestroy() {
        // 让任务留在 DownloadManager 的索引里，进程回来后还能接着下；这里只是关掉前台通知。
        super.onDestroy();
    }

    @NonNull
    @Override
    protected Notification getForegroundNotification(@NonNull List<Download> downloads, int notMetRequirements) {
        // 文案里的「%1$d」是剩余条数，交给 Media3 的 helper 拼，避免自己数错。
        return helper.buildProgressNotification(this, R.drawable.ic_notification, null, getString(R.string.offline_download_notification_channel), downloads, notMetRequirements);
    }

    private void createChannel() {
        NotificationManagerCompat manager = NotificationManagerCompat.from(this);
        NotificationChannelCompat channel = new NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.offline_download_notification_channel))
                .setDescription(getString(R.string.offline_download_notification_channel_desc))
                .build();
        manager.createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        // 某些 ROM 在后台起前台服务时会抛 ForegroundServiceStartNotAllowedException，
        // 让基类去处理即可——这里只保证不因此崩掉整个下载流程。
        try {
            return super.onStartCommand(intent, flags, startId);
        } catch (Throwable e) {
            stopSelf(startId);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? START_NOT_STICKY : super.onStartCommand(intent, flags, startId);
        }
    }
}
