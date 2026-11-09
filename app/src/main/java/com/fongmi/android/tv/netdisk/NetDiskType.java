package com.fongmi.android.tv.netdisk;

import java.util.Locale;

/**
 * 网络存储协议类型。
 *
 * <p>播放器本身读不了 {@code smb://}（随包 {@code libmpv.so} 里没有 libsmbclient，
 * Exo 也没有 SMB DataSource），所以这两种协议都必须先经本机 HTTP 服务中转，
 * 见 {@link NetDiskUrl#toProxyUrl(String)} 与 {@code server.process.NetDiskProxy}。
 */
public enum NetDiskType {

    SMB("smb"),
    WEBDAV("webdav"),
    FTP("ftp");

    private final String id;

    NetDiskType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** 目录分隔符：SMB 是反斜杠，WebDAV 与 FTP 都是正斜杠。 */
    public char separator() {
        return this == SMB ? '\\' : '/';
    }

    public static NetDiskType of(String id) {
        if (id == null) return null;
        String value = id.trim().toLowerCase(Locale.ROOT);
        for (NetDiskType type : values()) if (type.id.equals(value)) return type;
        return null;
    }

    /**
     * 按 URL 的 scheme 判定协议。刻意只认显式的网络存储 scheme：
     * 普通 {@code http}/{@code https} 不能被当作网盘，否则会破坏正常播放地址。
     */
    public static NetDiskType fromScheme(String scheme) {
        if (scheme == null) return null;
        switch (scheme.trim().toLowerCase(Locale.ROOT)) {
            case "smb":
            case "smb2":
                return SMB;
            case "webdav":
            case "dav":
            case "webdavs":
            case "davs":
                return WEBDAV;
            case "ftp":
                return FTP;
            default:
                return null;
        }
    }

    /** 该 scheme 是否要求 TLS。 */
    public static boolean isSecureScheme(String scheme) {
        if (scheme == null) return false;
        String value = scheme.trim().toLowerCase(Locale.ROOT);
        return "webdavs".equals(value) || "davs".equals(value);
    }

    /** 默认端口，仅在地址里没写端口时使用。 */
    public int defaultPort(boolean secure) {
        if (this == SMB) return 445;
        if (this == FTP) return 21;
        return secure ? 443 : 80;
    }
}
