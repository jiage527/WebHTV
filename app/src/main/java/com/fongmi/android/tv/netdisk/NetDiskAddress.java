package com.fongmi.android.tv.netdisk;

import java.util.ArrayList;
import java.util.List;

/**
 * 把用户在「服务器地址」里填的内容拆成主机、端口、加密与路径。
 *
 * <p><b>为什么需要它。</b>这个字段以前只被当作纯主机名使用：{@code buildBase()} 直接
 * 把它的原样值拼进 {@code scheme://authority}。用户很自然地会把整条网址粘进来
 * （例如 {@code https://dav.jianguoyun.com/dav/}），拼出来的就是
 * {@code http://https://dav.jianguoyun.com/dav//影视}，其中主机名成了字面量
 * {@code https}（用 OkHttp 实测过：{@code HttpUrl.parse} 得到 host=https），
 * 于是「测试连接」永远失败，而用户看到的原因只是「无法访问」。
 *
 * <p><b>为什么不直接拒绝带 scheme 的输入。</b>粘贴整条地址是最自然的操作，而且地址里
 * 的 {@code https} 恰好是判断「要不要走 TLS」的唯一线索——旧代码里没有任何地方设置
 * {@code secure}，WebDAV 因此永远按 80 端口明文连（坚果云的 80 端口实测返回 308 跳转）。
 * 所以这里把它解析出来，而不是报错让用户自己删。
 *
 * <p>本类是纯逻辑，不依赖 Android，可用普通 JVM 单测覆盖。
 */
public final class NetDiskAddress {

    private NetDiskAddress() {
    }

    /**
     * 解析结果。
     *
     * @param host      主机名；解析不出时为空串
     * @param port      地址里写明的端口；没写时为 0，由调用方回退成协议默认端口
     * @param secure    地址是否要求 TLS
     * @param path      地址里的路径（不含首尾斜杠，已做规范化）；没有时为空串
     * @param hasScheme 输入里是否带协议前缀
     */
    public record Parsed(String host, int port, boolean secure, String path, boolean hasScheme) {

        public boolean isEmpty() {
            return host.isEmpty();
        }
    }

    /**
     * 解析一段用户输入。
     *
     * <p>接受这类写法（都是用户真会填的）：
     * <ul>
     *   <li>{@code 192.168.1.5} —— 纯主机</li>
     *   <li>{@code 192.168.1.5:4455} —— 带端口</li>
     *   <li>{@code https://dav.jianguoyun.com/dav/} —— 整条网址</li>
     *   <li>{@code webdavs://user:pass@nas:8443/media} —— 带凭据的网址（凭据在这里丢弃，
     *       账号密码有各自的输入框）</li>
     * </ul>
     */
    public static Parsed parse(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return new Parsed("", 0, false, "", false);

        String scheme = schemeOf(value);
        boolean hasScheme = !scheme.isEmpty();
        if (hasScheme) value = value.substring(value.indexOf("://") + 3).trim();
        // 凭据不属于主机；账号密码在表单里有独立输入框，这里丢掉避免混进主机名。
        int at = value.indexOf('@');
        int firstSlash = value.replace('\\', '/').indexOf('/');
        if (at >= 0 && (firstSlash < 0 || at < firstSlash)) value = value.substring(at + 1);

        String hostPort = value;
        String path = "";
        int slash = hostPort.replace('\\', '/').indexOf('/');
        if (slash >= 0) {
            path = hostPort.substring(slash + 1);
            hostPort = hostPort.substring(0, slash);
        }
        // 去掉查询串与片段，它们不属于路径。
        path = cut(path, '#');
        path = cut(path, '?');

        String host = stripPort(hostPort);
        int port = parsePort(hostPort);
        boolean secure = hasScheme && isSecureScheme(scheme);
        return new Parsed(host, port, secure, normalize(path), hasScheme);
    }

    /**
     * 把地址里的路径与用户单独填的「目录」合并成最终根路径。
     *
     * <p>两者都是根的一部分：{@code https://host/dav/} 里的 {@code dav} 是服务端给的
     * WebDAV 根前缀，用户填的「影视」是他自己的目录，合起来才是
     * {@code /dav/影视}。只取其中一个都会连错地方。
     *
     * <p>已经包含前缀时不重复拼接——用户第二次编辑账号、又把整条网址粘一次是很常见的，
     * 拼成 {@code dav/dav/影视} 就会找不到目录。
     */
    public static String mergeRoot(String parsedPath, String typedRoot) {
        String left = normalize(parsedPath);
        String right = normalize(typedRoot);
        if (left.isEmpty()) return right;
        if (right.isEmpty()) return left;
        if (right.equals(left) || right.startsWith(left + "/")) return right;
        return left + "/" + right;
    }

    /**
     * 定出最终端口。
     *
     * <p><b>为什么不能直接用表单里那个数字。</b>端口框会被自动填上一个默认值，
     * 而填它的那一刻还不知道地址里写的是 http 还是 https——用户往往是先切类型、
     * 后粘地址。切类型时按「明文 WebDAV」算出 80 填进去，等地址里的 {@code https}
     * 解析出来时这个 80 已经躺在框里了，于是拼出
     * {@code https://dav.jianguoyun.com:80/dav/影视}：TLS 建在明文端口上，
     * 实测直接连不上（curl 返回 000），而用户看到的只是「无法访问」。
     *
     * <p>所以「地址没写端口、但框里的值恰好是另一套传输方式的默认端口」时，
     * 判定为自动填充的残留，返回 0 让调用方回落到当前 scheme 的默认端口。
     * 用户手填的其它任何端口都原样保留。
     *
     * <p>SMB（445）与 FTP（21）两种传输的默认端口不随加密变化，
     * {@code stale == wanted}，不会命中这条规则。
     *
     * @param typedPort   端口框里的值；空或非法时为 0
     * @param addressPort 地址里写明的端口；没写时为 0
     */
    public static int resolvePort(NetDiskType type, int typedPort, int addressPort, boolean hasScheme, boolean secure) {
        // 地址里写明的端口最权威：用户在那串网址里敲的端口就是他要连的端口。
        if (addressPort > 0) return addressPort;
        if (typedPort <= 0 || !hasScheme) return typedPort;
        int wanted = type.defaultPort(secure);
        int stale = type.defaultPort(!secure);
        if (stale != wanted && typedPort == stale) return 0;
        return typedPort;
    }

    /**
     * 把「可能已经带上根目录」的路径还原成相对根目录的路径。
     *
     * <p><b>为什么需要它。</b>两个 WebDAV 客户端都把账号根目录拼进了 {@code base}
     * （例如 {@code https://dav.jianguoyun.com/dav/影视}），而调用方传进来的路径
     * 又往往是从账号根算起的完整路径（面板打开账号时传的正是 {@code account.getRoot()}，
     * 即 {@code dav/影视}）。两者一叠加就变成 {@code /dav/影视/dav/影视/}，
     * 服务端回 404，用户看到的是「点进去显示无法访问」——实测过这一组对照：
     * {@code /dav/影视/} 返回 207，而 {@code /dav/影视/影视/} 返回 404。
     *
     * <p>这里在拼 URL 之前把重复的那一段剥掉，让「传相对路径」与「传根开始的全路径」
     * 两种调用方式都能得到同一个正确地址。剥不掉就原样返回，不影响本来就传对的地方。
     */
    public static String stripRoot(String path, String root) {
        String value = normalize(path);
        String prefix = normalize(root);
        if (prefix.isEmpty()) return value;
        if (value.equals(prefix)) return "";
        if (value.startsWith(prefix + "/")) return value.substring(prefix.length() + 1);
        return value;
    }

    /** URL 的 scheme，小写；没有或不像 scheme 时返回空串。 */
    static String schemeOf(String url) {
        if (url == null) return "";
        int index = url.indexOf("://");
        if (index <= 0) return "";
        String scheme = url.substring(0, index).trim().toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < scheme.length(); i++) {
            char ch = scheme.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '.' || ch == '+' || ch == '-';
            // 只认纯字母数字与 .+-，避免把 "C:/x" 或 "a b://c" 当成 scheme。
            if (!ok) return "";
        }
        return scheme;
    }

    /**
     * 该 scheme 是否要求加密。
     *
     * <p>除了网络存储自己的 {@code webdavs}/{@code davs}，还要认 {@code https}：
     * 用户从浏览器地址栏复制来的就是 {@code https://}。
     */
    static boolean isSecureScheme(String scheme) {
        if ("https".equals(scheme)) return true;
        if ("http".equals(scheme)) return false;
        return NetDiskType.isSecureScheme(scheme);
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

    /** 取端口；没写或写得不像端口（非纯数字、越界）时返回 0。 */
    private static int parsePort(String hostPort) {
        String value = hostPort.trim();
        int colon;
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 0) return 0;
            String remain = value.substring(close + 1);
            if (!remain.startsWith(":")) return 0;
            colon = close + 1;
        } else {
            colon = value.indexOf(':');
        }
        if (colon < 0) return 0;
        String port = value.substring(colon + 1).trim();
        if (port.isEmpty()) return 0;
        for (int i = 0; i < port.length(); i++) if (!Character.isDigit(port.charAt(i))) return 0;
        try {
            long parsed = Long.parseLong(port);
            return parsed > 0 && parsed <= 65535 ? (int) parsed : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 统一斜杠、去掉空段与首尾斜杠；保留 {@code ..} 交给上层处理，这里不动。 */
    private static String normalize(String path) {
        if (path == null) return "";
        String value = path.replace('\\', '/').trim();
        List<String> segments = new ArrayList<>();
        for (String segment : value.split("/")) {
            String item = segment.trim();
            if (!item.isEmpty()) segments.add(item);
        }
        return String.join("/", segments);
    }

    private static String cut(String value, char token) {
        int index = value.indexOf(token);
        return index < 0 ? value : value.substring(0, index);
    }
}
