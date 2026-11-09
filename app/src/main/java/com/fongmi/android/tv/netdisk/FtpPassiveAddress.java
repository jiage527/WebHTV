package com.fongmi.android.tv.netdisk;

/**
 * 解析 FTP 的被动模式地址。纯逻辑、不依赖 Android，便于单测。
 *
 * <p><b>为什么必须用被动模式。</b>主动模式（{@code PORT}）要求服务器反连客户端的一个
 * 随机端口，在手机上这几乎必然失败：运营商与家用路由器的 NAT 都不会放行入站连接，
 * 而且安卓侧也没有可用的监听通道保证。所以只实现 {@code PASV}/{@code EPSV}。
 *
 * <p><b>PASV 的应答格式是有坑的。</b>它给的是
 * {@code 227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)}，端口要由两个字节算出来：
 * {@code p1 * 256 + p2}。直接取最后一个数字当端口是错的，会连到一个完全不同的端口上，
 * 表现为「登录成功但列目录卡死/超时」。IPv6 则要用 {@code EPSV}
 * （{@code 229 Entering Extended Passive Mode (|||port|)}），它只给端口不给地址。
 *
 * <p>括号里的数字还会被一些实现写成空格分隔、或被多余文本包住，所以这里用
 * 「扫描所有数字」的方式取，而不是按逗号 {@code split}。
 */
final class FtpPassiveAddress {

    private final String host;
    private final int port;

    private FtpPassiveAddress(String host, int port) {
        this.host = host == null ? "" : host;
        this.port = port;
    }

    String getHost() {
        return host;
    }

    int getPort() {
        return port;
    }

    boolean isValid() {
        return port > 0 && port <= 65535;
    }

    /**
     * 解析 227（PASV）应答。
     *
     * <p>只认形如 {@code (a,b,c,d,p1,p2)} 的六段；段数不对时返回无效结果，
     * 由调用方退回 EPSV 或报错——猜一个端口出来只会连到别人的服务上。
     *
     * <p><b>先取出括号里的内容再数数字。</b>直接在整条应答上扫数字会把三位码
     * （例如开头的 {@code 227}）也算进去：五段的残缺元组加上这个 {@code 227}
     * 正好凑成六个数，于是被当成合法地址，连到一个完全错误的端口上。
     */
    static FtpPassiveAddress fromPasv(String text) {
        java.util.List<Long> numbers = numbersOf(tupleBody(text));
        if (numbers.size() < 6) return new FtpPassiveAddress("", 0);
        java.util.List<Long> tail = numbers.subList(numbers.size() - 6, numbers.size());
        for (Long value : tail) if (value == null || value < 0 || value > 255) return new FtpPassiveAddress("", 0);
        String host = tail.get(0) + "." + tail.get(1) + "." + tail.get(2) + "." + tail.get(3);
        int port = (int) (tail.get(4) * 256 + tail.get(5));
        return new FtpPassiveAddress(host, port);
    }

    /**
     * 取应答里括号包住的那一段；没有括号时用原文。
     *
     * <p>括号是协议规定的分隔符，用它把「元组」与「三位码/说明文字」天然分开。
     */
    private static String tupleBody(String text) {
        if (text == null) return "";
        int open = text.indexOf('(');
        int close = open < 0 ? -1 : text.indexOf(')', open);
        if (open < 0 || close < 0) return text;
        return text.substring(open + 1, close);
    }

    /**
     * 解析 229（EPSV）应答：{@code 229 Entering Extended Passive Mode (|||6446|)}。
     *
     * <p>EPSV 不返回地址，要用原控制连接的主机；这样也天然绕过了服务器把 PASV 里
     * 的地址写成内网 IP 导致连不上的问题，所以在 IPv4 上也值得优先尝试。
     */
    static int portFromEpsv(String text) {
        if (text == null) return 0;
        int open = text.lastIndexOf('(');
        int close = text.lastIndexOf(')');
        String body = open >= 0 && close > open ? text.substring(open + 1, close) : text;
        // 形如 |||6446|：取被竖线包住的那一段。
        int first = body.indexOf('|');
        int last = body.lastIndexOf('|');
        String digits = first >= 0 && last > first ? body.substring(first + 1, last) : body;
        digits = digits.replace("|", "").trim();
        try {
            int port = Integer.parseInt(digits);
            return port > 0 && port <= 65535 ? port : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 扫描出文本里所有的连续数字段。 */
    private static java.util.List<Long> numbersOf(String text) {
        java.util.List<Long> numbers = new java.util.ArrayList<>();
        if (text == null) return numbers;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isDigit(ch)) {
                current.append(ch);
                continue;
            }
            if (current.length() > 0) {
                numbers.add(parse(current.toString()));
                current.setLength(0);
            }
        }
        if (current.length() > 0) numbers.add(parse(current.toString()));
        return numbers;
    }

    private static Long parse(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
