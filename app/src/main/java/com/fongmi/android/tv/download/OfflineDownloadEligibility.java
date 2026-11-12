package com.fongmi.android.tv.download;

/**
 * 播放页「下载」按钮的可用性判定。
 *
 * <p><b>为什么不复用 {@code PlayerManager#isVod()}。</b>它在 Exo 与 MPV 两个内核里都写成
 * 「时长 &gt; 1 分钟」（{@code ExoPlayerEngine.java:574}、{@code MpvPlayerEngine.java:226}），
 * IJK 的旧式路径同样拿 1 分钟切分。于是 40 秒的短片、以及时长还没探到的 MP4 一律被判成
 * 直播，下载按钮根本不出现——用户实测反馈的就是这件事。它要回答的问题其实是
 * 「这条流是不是能播完的点播内容」，而不是「够不够长」。
 *
 * <p><b>为什么这三个信号够用。</b>三条内核给出的「直播」证据不一样，合起来才完整：
 * <ul>
 *   <li>{@code live}（{@code Player#isCurrentMediaItemLive()}）：Exo 由媒体源写实，
 *       最权威；MPV 从不设置直播配置，恒为 false；IJK 只在清单判定权威且为直播时才设。</li>
 *   <li>{@code dynamic}（{@code Player#isCurrentMediaItemDynamic()}）：三条内核都设。
 *       MPV 与 IJK 都写成「时长未知即 dynamic」。</li>
 *   <li>{@code durationKnown}：配合 dynamic 用来区分「直播（时长永远未知）」与
 *       「只是还没探到时长」。</li>
 * </ul>
 *
 * <p>所以规则是：只要不是直播就放行；只有「dynamic 且时长未知」才当成直播拦下。
 * 这条规则不放行直播，但**不再因为时长短而拦掉短片**——正是本次要修的点。
 *
 * <p>本类不碰 Android API，可直接跑 JVM 单测。
 */
public final class OfflineDownloadEligibility {

    private OfflineDownloadEligibility() {
    }

    /**
     * 这条流能不能下载。
     *
     * @param live          播放器是否认为当前媒体项是直播
     * @param dynamic       播放器是否认为当前媒体项是动态（时长会变的）流
     * @param durationKnown 播放器是否已经知道了时长
     */
    public static boolean allows(boolean live, boolean dynamic, boolean durationKnown) {
        // 明确是直播：不给下载。
        if (live) return false;
        // 动态且时长未知：这是直播的第二种表现（MPV/IJK 就是这么标直播的）。
        if (dynamic && !durationKnown) return false;
        return true;
    }
}
