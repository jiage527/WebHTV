package com.fongmi.android.tv.netdisk;

/**
 * 代理端点路径的常量。
 *
 * <p>单独放在这里，是为了让纯逻辑层（{@link NetDiskUrl}）与 Android 侧的
 * {@code server.process.NetDiskProxy} 引用同一个来源，避免两处各写一份字符串。
 */
public final class NetDiskProxyPath {

    /** 代理端点前缀。 */
    public static final String PREFIX = "/netdisk";

    private NetDiskProxyPath() {
    }
}
