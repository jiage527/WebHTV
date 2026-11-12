package com.fongmi.android.tv.download;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 把 Media3 {@code Download} 的裸状态翻译成「面板上要显示什么」。
 *
 * <p><b>为什么单独抽一层。</b>Media3 给的是三个互不相干的 int：{@code state}、
 * {@code stopReason}、{@code failureReason}。直接用它们拼文案很容易漏分支，
 * 例如「已停止」既可能是用户按的暂停，也可能是等 WiFi 的临时挂起——两者给用户的
 * 提示完全不同。这里把三个 int 收敛成一个 {@link Phase}，面板只认 Phase。
 *
 * <p><b>状态常量为什么硬编码。</b>取的是 {@code androidx.media3.exoplayer.offline.Download}
 * 的常量值，已用 {@code javap -constants} 从随包
 * {@code media3-exoplayer-1.11.0-alpha01-fongmi} 里核对过：
 * {@code STATE_QUEUED=0, STATE_STOPPED=1, STATE_DOWNLOADING=2, STATE_COMPLETED=3,
 * STATE_FAILED=4, STATE_REMOVING=5, STATE_RESTARTING=7}。用字面量是为了让本类
 * 不依赖 Android 库、可以直接跑 JVM 单测；如果哪天常量变了，
 * {@code OfflineDownloadStatusTest} 里钉住的那组断言会先失败。
 *
 * <p>本类不碰 Android API。
 */
public final class OfflineDownloadStatus {

    public static final int STATE_QUEUED = 0;
    public static final int STATE_STOPPED = 1;
    public static final int STATE_DOWNLOADING = 2;
    public static final int STATE_COMPLETED = 3;
    public static final int STATE_FAILED = 4;
    public static final int STATE_REMOVING = 5;
    public static final int STATE_RESTARTING = 7;

    /** 面板上要显示的一档状态。顺序即「列表里排前面的程度」。 */
    public enum Phase {
        /** 正在下。 */
        DOWNLOADING,
        /** 排队等着（包括等网络条件）。 */
        QUEUED,
        /** 用户暂停了。 */
        PAUSED,
        /** 下完了，可以离线播。 */
        COMPLETED,
        /** 失败了，可以重试。 */
        FAILED,
        /** 正在删。 */
        REMOVING,
        /** 正在准备重启（换播放列表之后重新下一遍）。 */
        RESTARTING,
        /** 认不出来的状态，别猜。 */
        UNKNOWN
    }

    private OfflineDownloadStatus() {
    }

    /**
     * 三个 int 收敛成一个 Phase。
     *
     * <p>{@code STATE_STOPPED} 一律当「暂停」显示：Media3 里 {@code stopReason} 既可能是
     * 用户设的停因、也可能是 {@code STOP_REASON_NONE} 但全局暂停开关是开的，
     * 这两种从单个 {@code Download} 上分不开（全局暂停状态在
     * {@code DownloadManager#getDownloadsPaused()} 上）。对用户来说两种都是「停了、
     * 点继续可以接着下」，所以合并成一档；要精确区分得看全局开关，那是面板的活儿。
     */
    public static Phase phase(int state, int stopReason, int failureReason) {
        switch (state) {
            case STATE_DOWNLOADING:
                return Phase.DOWNLOADING;
            case STATE_QUEUED:
                // 失败原因非 NONE 却是排队态，说明是上次失败后被重新排进来的。
                return failureReason != 0 ? Phase.FAILED : Phase.QUEUED;
            case STATE_STOPPED:
                // 有失败原因且没有停因，说明是「因为出错停了」，要能重试。
                return strategy(state, stopReason, failureReason);
            case STATE_COMPLETED:
                return Phase.COMPLETED;
            case STATE_FAILED:
                return Phase.FAILED;
            case STATE_REMOVING:
                return Phase.REMOVING;
            case STATE_RESTARTING:
                return Phase.RESTARTING;
            default:
                return Phase.UNKNOWN;
        }
    }

    private static Phase strategy(int state, int stopReason, int failureReason) {
        // 出错停下来的（stopReason 为 NONE 却带着失败原因）显示成失败，用户才知道该重试；
        // 纯粹被暂停/等条件的显示成暂停，用户点「继续」即可。
        return failureReason != 0 ? Phase.FAILED : Phase.PAUSED;
    }

    /**
     * 用户手动暂停某一条时用的停因。
     *
     * <p>Media3 的 {@code Download} 上只有一个 {@code STOP_REASON_NONE = 0}，其余取值由 App
     * 自己定；0 表示「继续」，非 0 表示「停住」。取 1 是因为它没有别的含义，不会和
     * 「等 WiFi 自动挂起」之类的内部停因撞上。
     */
    public static final int STOP_REASON_PAUSED = 1;

    /** 这个状态还要不要继续显示进度条。 */
    public static boolean showsProgress(Phase phase) {
        return phase == Phase.DOWNLOADING || phase == Phase.QUEUED || phase == Phase.PAUSED;
    }

    /** 「下载中」分页：还没下完的都算，包括排队、暂停和失败的。 */
    public static boolean active(Phase phase) {
        return phase != null && phase != Phase.COMPLETED;
    }

    /** 「已完成」分页：只有真的下完了才算。 */
    public static boolean finished(Phase phase) {
        return phase == Phase.COMPLETED;
    }

    /** 这个状态能不能删。正在删的不再重复删。 */
    public static boolean removable(Phase phase) {
        return phase != Phase.REMOVING;
    }

    /** 这个状态能不能点开离线播放。 */
    public static boolean playable(Phase phase) {
        return phase == Phase.COMPLETED;
    }

    /**
     * 百分比，进不到就返回 -1。
     *
     * <p>总长未知时（HLS 尚未解析出分片清单、或服务端不给 Content-Length）不能拿 0
     * 当进度，否则界面会一直显示 0% 像是卡住了；返回 -1 让面板显示不确定进度条。
     */
    public static int percent(long bytes, long total) {
        if (bytes <= 0 || total <= 0) return -1;
        if (bytes >= total) return 100;
        long value = bytes * 100 / total;
        if (value < 0) return -1;
        return (int) Math.min(100, value);
    }

    /** 面板上的体量文字，1 位小数。 */
    public static String sizeText(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024d;
        if (kb < 1024) return String.format(java.util.Locale.ROOT, "%.0f KB", kb);
        double mb = kb / 1024d;
        if (mb < 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", mb);
        double gb = mb / 1024d;
        return String.format(java.util.Locale.ROOT, "%.2f GB", gb);
    }

    /**
     * 列表排序：能看/正在动的排前面，然后按加入时间倒序。
     *
     * <p>顺序是按用户心里的事排的——正在下载的最关心，其次是下好的，最后才是停着的和
     * 出错的；同档内新的在前，这样刚点的那一条一定在顶上。
     */
    public static <T extends OfflineDownloadRow> void sort(List<T> rows) {
        if (rows == null || rows.size() < 2) return;
        Collections.sort(rows, (left, right) -> {
            int byPhase = rank(left.phase()) - rank(right.phase());
            if (byPhase != 0) return byPhase;
            int byTime = Long.compare(right.createdAt(), left.createdAt());
            if (byTime != 0) return byTime;
            return left.id().compareTo(right.id());
        });
    }

    private static int rank(Phase phase) {
        if (phase == null) return 9;
        switch (phase) {
            case DOWNLOADING: return 0;
            case QUEUED: return 1;
            case PAUSED: return 2;
            case COMPLETED: return 3;
            case FAILED: return 4;
            case RESTARTING: return 5;
            case REMOVING: return 6;
            default: return 7;
        }
    }

    /** 排序用的一行；面板拿真实对象自己实现这个接口。 */
    public interface OfflineDownloadRow {
        String id();

        Phase phase();

        long createdAt();
    }

    /** 便捷方法：把一批行排序后返回副本，不改入参。 */
    public static <T extends OfflineDownloadRow> List<T> sorted(List<T> rows) {
        if (rows == null) return Collections.emptyList();
        List<T> copy = new ArrayList<>(rows);
        sort(copy);
        return copy;
    }
}
