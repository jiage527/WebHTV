package com.fongmi.android.tv.netdisk;

import com.thegrizzlylabs.sardineandroid.impl.SardineException;

import java.io.FileNotFoundException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * WebDAV 云备份的失败分区。纯逻辑，便于单测。
 *
 * <p><b>为什么非要分区。</b>「点了没反应」是这类功能最常见的投诉：上传失败、目录不存在、
 * 密码错了，在用户眼里都是同一句「备份失败」。这里把 {@link Throwable} 归到有限的几类，
 * 由界面给出各自的中文文案，用户才知道该改密码、换目录，还是换网络。
 */
public enum WebDavBackupError {

    NETWORK,
    AUTH,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    UNSUPPORTED,
    SERVER,
    QUOTA,
    UNKNOWN;

    /** 按 HTTP 状态码分区。 */
    public static WebDavBackupError ofStatus(int status) {
        switch (status) {
            case 401:
                return AUTH;
            case 403:
                return FORBIDDEN;
            case 404:
                return NOT_FOUND;
            case 405:
            case 501:
                return UNSUPPORTED;
            case 409:
                return CONFLICT;
            case 507:
                return QUOTA;
            default:
                return status >= 500 ? SERVER : UNKNOWN;
        }
    }

    public static WebDavBackupError of(Throwable error) {
        if (error == null) return UNKNOWN;
        if (error instanceof SardineException) return ofStatus(((SardineException) error).getStatusCode());
        if (error instanceof UnknownHostException) return NETWORK;
        if (error instanceof ConnectException) return NETWORK;
        if (error instanceof SocketTimeoutException) return NETWORK;
        if (error instanceof javax.net.ssl.SSLException) return NETWORK;
        if (error instanceof FileNotFoundException) return NOT_FOUND;
        if (error instanceof SecurityException) return FORBIDDEN;
        Throwable cause = error.getCause();
        if (cause != null && cause != error) {
            WebDavBackupError nested = of(cause);
            if (nested != UNKNOWN) return nested;
        }
        String message = error.getMessage();
        if (message != null) {
            String lower = message.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("unable to resolve host") || lower.contains("failed to connect")
                    || lower.contains("timeout") || lower.contains("timed out")) return NETWORK;
            if (lower.contains("401")) return AUTH;
            if (lower.contains("403")) return FORBIDDEN;
            if (lower.contains("404")) return NOT_FOUND;
            if (lower.contains("507")) return QUOTA;
        }
        return UNKNOWN;
    }

    /**
     * 这类失败是否值得重试。
     *
     * <p>网络抖动重试有意义；认证失败重试只会让部分服务端把账号锁掉，
     * 所以必须区分开。
     */
    public boolean retryable() {
        return this == NETWORK || this == SERVER;
    }
}
