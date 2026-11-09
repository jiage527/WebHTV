package com.fongmi.android.tv.netdisk;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 基于原生 socket 的 FTP 客户端。
 *
 * <p><b>为什么手写、而不是引库。</b>FTP 控制连接就是一个文本协议（命令行 + 三位码应答），
 * 数据连接只是另一条 TCP 连接，不需要任何第三方库；而项目对新增依赖是克制的，多一个
 * 库就多一份体积、许可证与版本维护成本。仓库里也确实没有任何 FTP 库可用
 * （已核对：5 个 AAR 与整个 Gradle 缓存中 jsch/sshj/commons-net 命中均为 0）。
 *
 * <p><b>只实现被动模式。</b>主动模式要求服务器反连客户端端口，在手机与家用 NAT 后面
 * 基本必然失败，且安卓侧没有稳定可用的入站通道。所以只用 {@code PASV}/{@code EPSV}。
 *
 * <p><b>Range 用 {@code REST} 实现。</b>FTP 没有 HTTP 那样的 Range 头，恢复传输要靠
 * {@code REST n}（从第 n 字节开始）+ {@code RETR}。缺了这一步，拖动进度条就会从文件
 * 开头重下，播放器会认为该源不可 seek。需要说明的是：很多 FTP 服务器对 {@code REST}
 * 的支持并不完整，失败时这里会退化成从头传输再丢弃前 n 字节（与 WebDAV 客户端里
 * 服务端忽略 Range 时的做法一致），保证「能播」优先。
 *
 * <p><b>每次读文件都开一条自己的连接。</b>这条规则看着浪费，但它是唯一能同时满足
 * 两个要求的设计：FTP 的应答流是<b>有序且无标识</b>的——一条控制连接上并行两个传输时，
 * 传输结束后读到的可能是另一个传输的 {@code 226}，真实的结束应答就永久留在队列里，
 * 从那一刻起命令与应答全部错位。播放器 seek 时确实会并发发起多个 range 请求，
 * 所以要么把「打开+读完整段+读收尾应答」整段串行化（那会让 seek 排队等待，明显卡顿），
 * 要么每条传输独占一条连接。这里选后者：列目录与取长度共用一条短命令连接（有锁保护），
 * 而读文件走独立连接，互不干扰。
 *
 * <p><b>文本编码。</b>服务器声明的默认编码可能是 Latin-1，非 ASCII 文件名会乱码。
 * 登录后尝试 {@code OPTS UTF8 ON}（RFC 2640），并始终以 UTF-8 解码应答与控制命令。
 */
final class FtpNetDiskClient implements NetDiskClient {

    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    /** 每个目录最多读多少字节，防止异常服务器把内存吃满。 */
    private static final int MAX_LIST_BYTES = 8 * 1024 * 1024;

    private final NetDiskAccount account;
    /** 只保护「短命令连接」这一条共享连接；读文件的连接是每次新建的。 */
    private final Object shortCommandLock = new Object();

    private FtpConnection shared;

    FtpNetDiskClient(NetDiskAccount account) {
        this.account = account;
    }

    // ------------------------------------------------------------------ 接口实现

    @Override
    public void connect() throws Exception {
        synchronized (shortCommandLock) {
            FtpConnection connection = shared();
            // 登录本身即探活：连通但凭据错时，用户需要看到「认证失败」而不是「连接成功」。
            connection.connect();
            connection.login();
        }
    }

    @Override
    public List<NetDiskEntry> list(String path) throws Exception {
        synchronized (shortCommandLock) {
            FtpConnection connection = shared();
            connection.connect();
            connection.login();
            String target = path == null ? "" : path;
            // 先试标准结构化格式 MLSD；不支持时退回 LIST。
            // MLSD 的好处是格式统一，不必猜 Unix / DOS 两种写法。
            try {
                FtpReply mlsd = connection.command("MLSD " + quoted(target));
                if (mlsd.isPreliminary() || mlsd.isPositive()) {
                    return FtpListParser.parse(target, connection.readData(mlsd, MAX_LIST_BYTES));
                }
            } catch (Exception e) {
                // 不支持 MLSD 就换 LIST 重试。连接状态可能已受影响，重建一条干净的。
                connection.close();
                connection.connect();
                connection.login();
            }
            // 有些服务器（尤其是老 IIS）要先用 CWD 切过去，LIST 才回内容。
            try {
                connection.command("CWD " + quoted(target));
            } catch (Throwable ignored) {
            }
            FtpReply reply = connection.command("LIST");
            if (!reply.isPreliminary() && !reply.isPositive()) {
                throw new IllegalStateException("列目录失败：" + reply);
            }
            return FtpListParser.parse(target, connection.readData(reply, MAX_LIST_BYTES));
        }
    }

    @Override
    public long length(String path) throws Exception {
        synchronized (shortCommandLock) {
            FtpConnection connection = shared();
            connection.connect();
            connection.login();
            FtpReply reply = connection.command("SIZE " + quoted(path));
            if (!reply.isPositive()) return -1;
            try {
                return Long.parseLong(reply.getText().trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    @Override
    public InputStream open(String path, long start, long end) throws Exception {
        // 独立连接：不与上面的短命令连接、也不与其它 range 请求争用同一条控制通道。
        FtpConnection connection = new FtpConnection();
        try {
            connection.connect();
            connection.login();
            return connection.retrieve(path, start, end);
        } catch (Exception e) {
            connection.close();
            throw e;
        }
    }

    private FtpConnection shared() {
        if (shared == null) shared = new FtpConnection();
        return shared;
    }

    @Override
    public void close() {
        synchronized (shortCommandLock) {
            if (shared != null) {
                shared.close();
                shared = null;
            }
        }
    }

    /**
     * FTP 路径要按段拼成 {@code /a/b} 形式。
     *
     * <p>不在整串上做百分号转义：段内的 {@code #} 与 {@code ?} 在 FTP 里没有特殊含义，
     * 转义它们反而会让服务器去找一个带百分号的文件名。
     */
    private static String quoted(String path) {
        if (path == null || path.isEmpty()) return "/";
        StringBuilder builder = new StringBuilder();
        for (String segment : NetDiskCodec.split(path, NetDiskType.FTP.separator())) {
            builder.append('/').append(segment);
        }
        return builder.length() == 0 ? "/" : builder.toString();
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 控制连接

    /**
     * 一条 FTP 控制连接及其数据连接。
     *
     * <p>把 socket、读写、登录状态、被动模式与收尾应答都收在这里，是为了让「短命令」
     * 与「读文件」两条路径共用同一套协议实现，同时各用各的实例互不干扰。
     *
     * <p>本类<b>不做内部同步</b>：短命令路径由客户端的锁保护，读文件路径每条连接
     * 只有一个使用者。把锁放在外面才能让「一条连接一次只跑一个传输」这条约束清晰可见。
     */
    private final class FtpConnection {

        private Socket control;
        private InputStream in;
        private OutputStream out;
        private boolean loggedIn;

        void connect() throws Exception {
            if (isAlive()) return;
            close();
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(account.getHost(), account.getPort()), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(READ_TIMEOUT_MS);
                control = socket;
                in = new BufferedInputStream(socket.getInputStream());
                out = socket.getOutputStream();
                loggedIn = false;
                // 连上后服务器先发一条 220 欢迎语，必须先读掉，否则后续读到的都是错位的应答。
                FtpReply greeting = readReply();
                if (!greeting.isPositive() && !greeting.isPreliminary()) {
                    throw new IllegalStateException("服务器拒绝连接：" + greeting);
                }
            } catch (Exception e) {
                close();
                throw e;
            }
        }

        void login() throws Exception {
            if (loggedIn) return;
            String user = account.getUsername().isEmpty() ? "anonymous" : account.getUsername();
            FtpReply userReply = command("USER " + user);
            // 331 表示需要密码；有的服务器对 anonymous 直接回 230。
            if (userReply.isType(3)) {
                FtpReply passReply = command("PASS " + account.getPassword());
                if (!passReply.isPositive()) throw new IllegalStateException("登录失败：" + passReply);
            } else if (!userReply.isPositive()) {
                throw new IllegalStateException("登录失败：" + userReply);
            }
            // 只是尝试，服务器可以拒绝；失败不影响功能，只是文件名可能非 UTF-8。
            try {
                command("OPTS UTF8 ON");
            } catch (Throwable ignored) {
            }
            // 二进制模式：视频是二进制流，ASCII 模式会改写换行字节，破坏文件。
            FtpReply typeReply = command("TYPE I");
            if (!typeReply.isPositive()) throw new IllegalStateException("无法切换到二进制模式：" + typeReply);
            loggedIn = true;
        }

        boolean isAlive() {
            return control != null && control.isConnected() && !control.isClosed();
        }

        FtpReply command(String line) throws Exception {
            send(line);
            return readReply();
        }

        void send(String line) throws Exception {
            if (out == null) throw new IllegalStateException("控制连接未建立");
            out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        /**
         * 读一条完整应答，含多行续行。
         *
         * <p>续行必须读完：多行应答只读首行的话，剩下的行会被当成下一条命令的应答，
         * 从这里开始命令与应答就永久错位了。
         */
        FtpReply readReply() throws Exception {
            List<String> lines = new ArrayList<>();
            String first = readLine();
            if (first == null) throw new IllegalStateException("服务器关闭了连接");
            lines.add(first);
            if (FtpReply.isMultilineStart(first)) {
                String code = FtpReply.codeOf(first);
                for (int i = 0; i < 1000; i++) {
                    String line = readLine();
                    if (line == null) break;
                    lines.add(line);
                    if (FtpReply.isMultilineEnd(code, line)) break;
                }
            }
            return FtpReply.parse(lines);
        }

        /** 按 CRLF 读一行；同时容忍只发 LF 的实现。 */
        private String readLine() throws Exception {
            if (in == null) return null;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
            int value;
            while ((value = in.read()) >= 0) {
                if (value == '\n') break;
                if (value == '\r') continue;
                buffer.write(value);
            }
            if (value < 0 && buffer.size() == 0) return null;
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }

        /** 把数据连接上的字节读成文本（目录列举用），并读掉收尾应答。 */
        String readData(FtpReply reply, int maxBytes) throws Exception {
            Socket data = openData();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
            try (InputStream stream = data.getInputStream()) {
                byte[] chunk = new byte[8192];
                int read;
                int total = 0;
                while ((read = stream.read(chunk)) > 0) {
                    total += read;
                    if (total > maxBytes) break;
                    buffer.write(chunk, 0, read);
                }
            } finally {
                closeQuietly(data);
            }
            // 传完后服务器会再回一条 226；不读掉它，下一条命令就会拿到这条旧应答。
            FtpReply done = readReply();
            if (!done.isPositive() && !done.isPreliminary()) {
                throw new IllegalStateException("传输未正常结束：" + done);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }

        /** 建立数据连接并发起读取；返回的流负责在关闭时收尾。 */
        InputStream retrieve(String path, long start, long end) throws Exception {
            // REST 必须在打开数据连接之前发。支持时服务器直接跳到 start，
            // 不支持时退化为全量传输再丢弃前 start 字节。
            boolean restApplied = false;
            if (start > 0) {
                FtpReply rest = command("REST " + start);
                if (rest.isType(3) || rest.isPositive()) {
                    restApplied = true;
                } else {
                    // 服务器拒绝了 REST；连接状态可能已被影响，重建一条干净的再试。
                    // 注意这里不再重试 REST，直接按「不支持」处理。
                    close();
                    connect();
                    login();
                }
            }
            Socket data = openData();
            send("RETR " + quoted(path));
            FtpReply reply = readReply();
            if (!reply.isPreliminary() && !reply.isPositive()) {
                closeQuietly(data);
                throw new IllegalStateException("读取文件失败：" + reply);
            }
            InputStream stream = data.getInputStream();
            if (!restApplied && start > 0) {
                // 没有 REST 支持：把前面的字节读掉，保证播放器拿到的是从 start 开始的内容。
                // 否则 seek 之后画面对不上，用户会以为文件坏了。
                skipFully(stream, start);
            }
            return new FtpDataStream(this, data, stream, end - start + 1);
        }

        /** 打开被动模式的数据连接；优先 EPSV（在 IPv4 上也更稳），退回 PASV。 */
        private Socket openData() throws Exception {
            FtpReply epsv = command("EPSV");
            int port = epsv.isPositive() ? FtpPassiveAddress.portFromEpsv(epsv.getText()) : 0;
            String host = account.getHost();
            if (port <= 0) {
                FtpReply pasv = command("PASV");
                if (!pasv.isPositive()) throw new IllegalStateException("服务器不支持被动模式：" + pasv);
                FtpPassiveAddress address = FtpPassiveAddress.fromPasv(pasv.getText());
                if (!address.isValid()) throw new IllegalStateException("被动模式地址不合法：" + pasv);
                port = address.getPort();
                // 服务器在 PASV 里回的常是它自己的内网 IP，客户端连不上；
                // 这时改用控制连接的主机更可靠（主流 FTP 客户端也是这么做的）。
                if (!address.getHost().isEmpty() && !isPrivateOrUnreachable(address.getHost())) host = address.getHost();
            }
            Socket data = new Socket();
            data.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            data.setSoTimeout(READ_TIMEOUT_MS);
            return data;
        }

        /**
         * PASV 返回内网地址时要忽略它。
         *
         * <p>服务器在 NAT 后面时 {@code PASV} 常常回它自己的私网 IP（如 192.168.x.x），
         * 客户端连过去必然超时。此时用控制连接的主机地址才是对的。
         */
        private boolean isPrivateOrUnreachable(String host) {
            if (host == null || host.isEmpty()) return true;
            String value = host.trim();
            if ("0.0.0.0".equals(value)) return true;
            if (value.startsWith("10.") || value.startsWith("192.168.") || value.startsWith("127.")) return true;
            if (value.startsWith("172.")) {
                try {
                    int second = Integer.parseInt(value.split("\\.")[1]);
                    return second >= 16 && second <= 31;
                } catch (Throwable ignored) {
                    return false;
                }
            }
            return false;
        }

        /** 传输收尾：读掉 226，让这条连接回到可继续发命令的状态。 */
        void finishTransfer() {
            try {
                if (isAlive()) readReply();
            } catch (Throwable ignored) {
            }
        }

        void close() {
            closeQuietly(control);
            control = null;
            in = null;
            out = null;
            loggedIn = false;
        }
    }

    private static void skipFully(InputStream stream, long count) throws Exception {
        long remaining = count;
        while (remaining > 0) {
            long skipped = stream.skip(remaining);
            if (skipped <= 0) {
                if (stream.read() < 0) return;
                remaining--;
                continue;
            }
            remaining -= skipped;
        }
    }

    /**
     * 读文件的流：限定在请求的区间内，关闭时收尾并释放它独占的那条控制连接。
     *
     * <p>必须限定上限：HTTP 端已经按 Range 算好了要多少字节，多读只会浪费带宽，
     * 而且不设上限时流会一直读到服务器断开，seek 之后会白传整个文件。
     */
    private static final class FtpDataStream extends InputStream implements Closeable {

        private final FtpConnection connection;
        private final Socket data;
        private final InputStream stream;
        private final long limit;
        private long read;

        FtpDataStream(FtpConnection connection, Socket data, InputStream stream, long limit) {
            this.connection = connection;
            this.data = data;
            this.stream = stream;
            this.limit = limit;
        }

        @Override
        public int read() throws java.io.IOException {
            if (read >= limit) return -1;
            int value = stream.read();
            if (value >= 0) read++;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws java.io.IOException {
            if (read >= limit) return -1;
            int allowed = (int) Math.min(length, limit - read);
            int count = stream.read(buffer, offset, allowed);
            if (count > 0) read += count;
            return count;
        }

        @Override
        public int available() throws java.io.IOException {
            return stream.available();
        }

        @Override
        public void close() {
            closeQuietly(stream);
            closeQuietly(data);
            // 这条控制连接专供本次传输，读完就关掉：既能及时收尾，也不会把
            // 未读的 226 留给下一条命令（下一条命令走的是另一条连接）。
            connection.finishTransfer();
            connection.close();
        }
    }

    /** 供单测观察。 */
    String schemeLabel() {
        return NetDiskType.FTP.id().toUpperCase(Locale.ROOT);
    }
}
