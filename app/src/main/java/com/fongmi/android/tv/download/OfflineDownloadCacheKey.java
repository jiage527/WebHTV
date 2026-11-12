package com.fongmi.android.tv.download;

import androidx.media3.common.C;

/**
 * 「这条下载能不能带自定义 cacheKey」的判定。
 *
 * <p><b>为什么需要单独一个类。</b>Media3 的 {@code DownloadRequest} 有一条硬性规则：内容类型
 * 是 DASH（{@link C#CONTENT_TYPE_DASH}）、HLS（{@link C#CONTENT_TYPE_HLS}）或
 * SmoothStreaming（{@link C#CONTENT_TYPE_SS}）时，{@code customCacheKey} 必须为 null，否则
 * 构造就抛 {@code IllegalArgumentException: customCacheKey must be null for type: 2}。
 *
 * <p>这条规则是从 {@code DownloadRequest} 私有构造的字节码里核出来的，不是猜的。字节码分支为
 * {@code ifeq 28 / if_icmpeq 28 / if_icmpne 59}——只有 type 不等于 0、2、1 时才跳过检查，
 * 也就是说 <b>DASH 同样在限制之内</b>（这一点容易漏，最初就漏了）；检查体本身是
 * {@code Preconditions.checkArgument(customCacheKey == null, "...", type)}。
 *
 * <p>真机上「播放页点下载就直接闪退」正是这个原因：m3u8 被 Media3 判成 HLS，而调用方
 * 无条件设了 key。所以在把 key 交给 {@code DownloadRequest} 之前必须先问一句。
 *
 * <p><b>为什么不自己按后缀判。</b>「是不是 HLS」Media3 有自己的口径（先看 MIME、再看后缀），
 * 任何自己重写一遍的判断都会在某个边界上与它分叉，而这里分叉的代价是一次崩溃。调用方把
 * {@code Util.inferContentTypeForUriAndMimeType} 的结果传进来，本类只负责那条规则本身——
 * 这样规则可以被纯 JVM 单测钉住，而不是只能靠真机点一遍。
 *
 * <p>本类不碰 Android API，可直接 JVM 单测。
 */
public final class OfflineDownloadCacheKey {

    private OfflineDownloadCacheKey() {
    }

    /**
     * 这个内容类型下，{@code DownloadRequest} 允不允许带 {@code customCacheKey}。
     *
     * @param contentType Media3 的 {@code C.CONTENT_TYPE_*} 取值
     */
    public static boolean allows(int contentType) {
        return contentType != C.CONTENT_TYPE_DASH && contentType != C.CONTENT_TYPE_HLS && contentType != C.CONTENT_TYPE_SS;
    }
}
