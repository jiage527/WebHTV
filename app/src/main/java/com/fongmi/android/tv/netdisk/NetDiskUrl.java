package com.fongmi.android.tv.netdisk;

import java.util.ArrayList;
import java.util.List;

/**
 * 网络存储地址的解析与改写。纯逻辑、不依赖 Android，可由单测覆盖。
 *
 * <p><b>为什么需要这一层。</b>播放器读不了 {@code smb://}：随包 {@code libmpv.so} 里
 * 既没有 libsmbclient 也没有 {@code smb://} 字样（已用 {@code grep -c} 在双 ABI 上核对），
 * Exo 也没有 SMB 的 DataSource。所以这类地址必须先换成指向本机 HTTP 服务的地址，
 * 由 {@code server.process.NetDiskProxy} 去真的读字节。
 *
 * <p><b>刻意不用 {@code android.net.Uri}。</b>它在单元测试里是打桩的空壳，
 * 而这层的解析规则（userinfo 里可能带域、SMB 的 share 与路径要分开）需要真正被测试。
 * 另一处风险是 {@code Uri.getPath()} 会自动解码百分号转义，那样带 {@code %} 或
 * {@code #} 的真实文件名会被解成另一条路径。
 */
public final class NetDiskUrl {

    private NetDiskUrl() {
    }

    /**
     * 解析出的远端地址。
     *
     * <p><b>FTP 没有「共享名」这个概念。</b>它的整条路径都在服务器根之下，
     * 所以不像 SMB 那样先切出 share，而是整段当 {@link #path} 处理。
     */
    public static final class Remote {

        private final NetDiskType type;
        private final String scheme;
        private final String host;
        private final int port;
        private final boolean secure;
        private final String share;
        private final String path;
        private final String username;
        private final String password;

        Remote(NetDiskType type, String scheme, String host, int port, boolean secure, String share, String path, String username, String password) {
            this.type = type;
            this.scheme = scheme;
            this.host = host;
            this.port = port;
            this.secure = secure;
            this.share = share;
            this.path = path;
            this.username = username;
            this.password = password;
        }

        public NetDiskType getType() {
            return type;
        }

        public String getScheme() {
            return scheme;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port > 0 ? port : type.defaultPort(secure);
        }

        public boolean isSecure() {
            return secure;
        }

        public String getShare() {
            return share;
        }

        public String getPath() {
            return path;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }

        /** 不含凭据的描述，可安全写日志或显示。 */
        public String describe() {
            return type.id() + "://" + host + ":" + getPort() + (share.isEmpty() ? "" : "/" + share)
                    + (path.isEmpty() ? "" : type.separator() + path);
        }
    }

    /** 该 URL 是否属于本层会接管的网络存储协议。 */
    public static boolean isNetDiskUrl(String url) {
        return NetDiskType.fromScheme(schemeOf(url)) != null;
    }

    /** URL 的 scheme，小写；没有 scheme 时返回空串。 */
    public static String schemeOf(String url) {
        if (url == null) return "";
        int index = url.indexOf("://");
        if (index <= 0) return "";
        String scheme = url.substring(0, index).trim().toLowerCase(java.util.Locale.ROOT);
        // 只认纯字母数字 + . + -，避免把 "C:/x" 或 "a b://c" 当成 scheme。
        for (int i = 0; i < scheme.length(); i++) {
            char ch = scheme.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '.' || ch == '+' || ch == '-';
            if (!ok) return "";
        }
        return scheme;
    }

    /**
     * 解析网络存储地址；不是受支持的协议、或缺少主机时返回 {@code null}。
     *
     * <p>支持 {@code smb://[域;]用户:密码@主机[:端口]/共享/路径}、
     * {@code webdav[s]://用户:密码@主机[:端口]/路径} 与
     * {@code ftp://用户:密码@主机[:端口]/路径}。
     */
    public static Remote parse(String url) {
        String scheme = schemeOf(url);
        NetDiskType type = NetDiskType.fromScheme(scheme);
        if (type == null) return null;
        String rest = url.substring(url.indexOf("://") + 3);
        // 查询串与片段不属于路径，先摘掉，否则会变成文件名的一部分。
        rest = cut(rest, '#');
        rest = cut(rest, '?');

        String userinfo = "";
        int at = rest.indexOf('@');
        int slash = rest.replace('\\', '/').indexOf('/');
        if (at >= 0 && (slash < 0 || at < slash)) {
            userinfo = rest.substring(0, at);
            rest = rest.substring(at + 1);
        }

        String hostPort = rest;
        String rawPath = "";
        int firstSlash = hostPort.replace('\\', '/').indexOf('/');
        if (firstSlash >= 0) {
            rawPath = hostPort.substring(firstSlash + 1);
            hostPort = hostPort.substring(0, firstSlash);
        }
        String host = stripPort(hostPort);
        if (host.isEmpty()) return null;

        int port = parsePort(rest);

        List<String> segments = decodeSegments(rawPath);
        String share = "";
        String path = "";
        if (type == NetDiskType.SMB) {
            // 先切出共享名，再让剩余的「..」生效。
            //
            // 顺序很关键：若把共享名留在列表里一起做「..」，那么
            // smb://nas/media/../../etc 会把「media」自身弹掉，变成访问
            // 共享外的 etc。共享名是账号的边界，不能被路径爬出去。
            if (!segments.isEmpty()) {
                share = segments.remove(0);
            }
            path = NetDiskCodec.join(resolve(segments), NetDiskType.SMB.separator());
        } else {
            // WebDAV 与 FTP 都没有「共享名」，整条路径都在服务器根之下。
            path = NetDiskCodec.join(resolve(segments), type.separator());
        }

        String username = "";
        String password = "";
        if (!userinfo.isEmpty()) {
            int colon = userinfo.indexOf(':');
            String name = colon < 0 ? userinfo : userinfo.substring(0, colon);
            password = colon < 0 ? "" : userinfo.substring(colon + 1);
            username = NetDiskCodec.decodeSegment(name);
            password = NetDiskCodec.decodeSegment(password);
        }
        return new Remote(type, scheme, host, port, NetDiskType.isSecureScheme(scheme), share, path, username, password);
    }

    /** 去掉端口部分，保留 IPv6 字面量里的冒号。 */
    private static String stripPort(String hostPort) {
        String value = hostPort.trim();
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            return close < 0 ? value : value.substring(0, close + 1);
        }
        int colon = value.indexOf(':');
        return colon < 0 ? value : value.substring(0, colon).trim();
    }

    /** 取端口；没写或写得不像端口（不是纯数字、越界）时返回 0，由调用方回退成默认端口。 */
    private static int parsePort(String rest) {
        int slash = rest.replace('\\', '/').indexOf('/');
        String hostPort = slash < 0 ? rest : rest.substring(0, slash);
        int at = hostPort.indexOf('@');
        if (at >= 0) hostPort = hostPort.substring(at + 1);
        int colon;
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            if (close < 0) return 0;
            String remain = hostPort.substring(close + 1);
            if (!remain.startsWith(":")) return 0;
            colon = close + 1;
        } else {
            colon = hostPort.indexOf(':');
        }
        if (colon < 0) return 0;
        String port = hostPort.substring(colon + 1);
        if (port.isEmpty()) return 0;
        for (int i = 0; i < port.length(); i++) if (!Character.isDigit(port.charAt(i))) return 0;
        return parseInt(port);
    }

    private static int parseInt(String text) {
        try {
            long value = Long.parseLong(text);
            return value > 0 && value <= 65535 ? (int) value : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<String> decodeSegments(String rawPath) {
        List<String> segments = new ArrayList<>();
        if (rawPath == null || rawPath.isEmpty()) return segments;
        for (String raw : rawPath.replace('\\', '/').split("/")) {
            if (raw.isEmpty() || ".".equals(raw)) continue;
            segments.add(NetDiskCodec.decodeSegment(raw));
        }
        return segments;
    }

    /**
     * 就地展开「..」。
     *
     * <p>解到空栈时丢掉多余的「..」而不是报错：这样 {@code ../a} 这类地址会退化成
     * {@code a}，停在根目录，而不是跑到上层去。调用方要先把共享名摘掉再调这里。
     */
    private static List<String> resolve(List<String> segments) {
        List<String> resolved = new ArrayList<>();
        for (String segment : segments) {
            if ("..".equals(segment)) {
                if (!resolved.isEmpty()) resolved.remove(resolved.size() - 1);
                continue;
            }
            resolved.add(segment);
        }
        return resolved;
    }

    private static String cut(String text, char delimiter) {
        int index = text.indexOf(delimiter);
        return index < 0 ? text : text.substring(0, index);
    }

    // ------------------------------------------------------------------ 改写为本地代理地址

    /**
     * 把账号内的相对路径拼成代理端点地址。
     *
     * <p>返回的是普通 {@code http://127.0.0.1:port/...} 地址，播放器无需任何改动即可播放；
     * 这也是既有 {@code file://} 转 {@code /file/} 的同一套做法。
     */
    public static String toProxyUrl(String baseAddress, String accountId, String path) {
        if (baseAddress == null || baseAddress.isEmpty() || accountId == null || accountId.isEmpty()) return "";
        StringBuilder builder = new StringBuilder(baseAddress);
        if (!baseAddress.endsWith("/")) builder.append('/');
        builder.append(NetDiskProxyPath.PREFIX.substring(1)).append("?account=").append(NetDiskCodec.encodeSegment(accountId));
        if (path != null && !path.isEmpty()) builder.append("&path=").append(NetDiskCodec.encodeSegment(path));
        return builder.toString();
    }

    /** 从代理地址里取回账号内相对路径；不是本层的代理地址时返回空串。 */
    public static String pathOfProxyUrl(String url) {
        if (url == null) return "";
        int marker = url.indexOf(NetDiskProxyPath.PREFIX);
        if (marker < 0) return "";
        String query = queryOf(url);
        if (query == null) return "";
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            if ("path".equals(pair.substring(0, eq))) return NetDiskCodec.decodeSegment(pair.substring(eq + 1));
        }
        return "";
    }

    /** 从代理地址里取回账号 id；不是本层的代理地址时返回空串。 */
    public static String accountOfProxyUrl(String url) {
        if (url == null) return "";
        int marker = url.indexOf(NetDiskProxyPath.PREFIX);
        if (marker < 0) return "";
        String query = queryOf(url);
        if (query == null) return "";
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            if ("account".equals(pair.substring(0, eq))) return NetDiskCodec.decodeSegment(pair.substring(eq + 1));
        }
        return "";
    }

    /** 取出本层端点之后的查询串（不含开头的 {@code ?}）；不是本层地址时返回 {@code null}。 */
    private static String queryOf(String url) {
        if (url == null) return null;
        int marker = url.indexOf(NetDiskProxyPath.PREFIX);
        if (marker < 0) return null;
        String query = url.substring(marker + NetDiskProxyPath.PREFIX.length());
        // 端点后直接跟的就是「?」，不摘掉的话首个键名会变成「?account」，整个查询都读不出来。
        if (query.startsWith("?")) return query.substring(1);
        if (query.startsWith("&")) return query.substring(1);
        return "";
    }
}
