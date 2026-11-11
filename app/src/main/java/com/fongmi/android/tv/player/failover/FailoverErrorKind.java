package com.fongmi.android.tv.player.failover;

import java.util.Locale;

/**
 * Error classification for the multi-source failover watchdog.
 *
 * <p>Kept free of Android types so the watchdog scoring stays unit-testable on the JVM.
 */
public enum FailoverErrorKind {

    NONE(0, false),
    /** 401 / 403 / "forbidden" / signature rejected: the line will not recover by waiting. */
    AUTH(100, true),
    /** 404 / 410 / "not found": the media is gone from this line. */
    NOT_FOUND(100, true),
    /** Decoder or container failure: switching lines is the only recovery. */
    DECODE(100, true),
    TIMEOUT(35, false),
    NETWORK(35, false),
    SERVER(35, false),
    UNKNOWN(20, false);

    private final double penalty;
    private final boolean fatal;

    FailoverErrorKind(double penalty, boolean fatal) {
        this.penalty = penalty;
        this.fatal = fatal;
    }

    /** Score points removed from a perfect 100 when this error dominates the window. */
    public double penalty() {
        return penalty;
    }

    /** Fatal errors make a line unhealthy from a single observation. */
    public boolean fatal() {
        return fatal;
    }

    public boolean isError() {
        return this != NONE;
    }

    /**
     * Classify an error message / HTTP status into a coarse category.
     *
     * <p>Order matters: a fatal status wins over a transitive network word, so
     * {@code "403 timeout"} is treated as AUTH rather than TIMEOUT.
     */
    public static FailoverErrorKind classify(String message, int httpStatus) {
        if (httpStatus == 401 || httpStatus == 403) return AUTH;
        if (httpStatus == 404 || httpStatus == 410) return NOT_FOUND;
        if (httpStatus >= 500 && httpStatus <= 599) return SERVER;
        String text = message == null ? "" : message.toLowerCase(Locale.US);
        if (text.isEmpty()) return httpStatus > 0 ? UNKNOWN : NONE;
        if (contains(text, "403", "forbidden", "unauthorized", "401", "signature", "invalid token", "鉴权")) return AUTH;
        if (contains(text, "404", "410", "not found", "gone", "已删除", "不存在")) return NOT_FOUND;
        if (contains(text, "decode", "decoder", "codec", "format error", "解封装", "解码")) return DECODE;
        if (contains(text, "timeout", "timed out", "time out", "超时")) return TIMEOUT;
        if (contains(text, "unknownhost", "unable to resolve", "no address associated", "dns")) return NETWORK;
        if (contains(text, "connect", "socket", "reset by peer", "broken pipe", "econn", "network", "网络")) return NETWORK;
        if (contains(text, "server", "502", "503", "504", "bad gateway", "unavailable")) return SERVER;
        return UNKNOWN;
    }

    private static boolean contains(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }
}
