package com.fongmi.android.tv.netdisk;

import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于 smbj 的 SMB2/3 客户端。
 *
 * <p><b>为什么用它。</b>smbj 早就在本仓库的依赖里（{@code catvod} 的 {@code api} 依赖，
 * 一直零引用），是纯 Java 实现，不引入新的 native 库；随包 {@code libmpv.so} 没有
 * libsmbclient，所以 SMB 只能走应用层。
 *
 * <p><b>生命周期。</b>smbj 的 {@code Connection}/{@code Session}/{@code DiskShare} 是
 * 有状态且线程敏感的，一次列举失败后必须整条链重建，否则后续调用会复用已断的会话。
 * 因此这里不做连接池：{@code NetDiskSession} 按账号缓存客户端，而客户端内部按需
 * 重新 {@link #connect()}。
 */
public final class SmbNetDiskClient implements NetDiskClient {

    /** 单次列举的上限，防止巨目录把内存吃满。 */
    private static final int MAX_ENTRIES = 5000;
    private static final int TIMEOUT_SECONDS = 20;
    private static final int SO_TIMEOUT_SECONDS = 30;

    private final NetDiskAccount account;
    private final Object lock = new Object();

    private SMBClient client;
    private Connection connection;
    private Session session;
    private DiskShare share;

    public SmbNetDiskClient(NetDiskAccount account) {
        this.account = account;
    }

    @Override
    public void connect() throws Exception {
        synchronized (lock) {
            closeLocked();
            SmbConfig config = SmbConfig.builder()
                    .withTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .withSoTimeout(SO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .withMultiProtocolNegotiate(true)
                    .build();
            client = new SMBClient(config);
            connection = client.connect(account.getHost(), account.getPort());
            session = connection.authenticate(authentication());
            share = (DiskShare) session.connectShare(account.getShare());
        }
    }

    /**
     * 构造认证信息。
     *
     * <p>没填用户名时走匿名（部分 NAS 的公开共享允许），而不是传空用户名 ——
     * 后者在 Windows 服务端会被当成一个真实的名字、直接返回拒绝，让人误以为密码错了。
     */
    private AuthenticationContext authentication() {
        if (account.getUsername().isEmpty()) return AuthenticationContext.anonymous();
        String domain = account.getDomain().isEmpty() ? null : account.getDomain();
        return new AuthenticationContext(account.getUsername(), account.getPassword().toCharArray(), domain);
    }

    @Override
    public List<NetDiskEntry> list(String path) throws Exception {
        ensureConnected();
        synchronized (lock) {
            List<NetDiskEntry> entries = new ArrayList<>();
            String prefix = account.getRoot();
            String target = path == null || path.isEmpty() ? prefix : NetDiskCodec.join(prefix, path, NetDiskType.SMB.separator());
            for (FileIdBothDirectoryInformation info : share.list(target)) {
                String name = info.getFileName();
                if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) continue;
                boolean directory = (info.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
                String entryPath = NetDiskCodec.join(path, name, NetDiskType.SMB.separator());
                long modified = info.getLastWriteTime() == null ? 0 : info.getLastWriteTime().toEpochMillis();
                entries.add(new NetDiskEntry(name, entryPath, directory, directory ? 0 : info.getEndOfFile(), modified));
                if (entries.size() >= MAX_ENTRIES) break;
            }
            entries.sort(NetDiskEntry::compareTo);
            return entries;
        }
    }

    @Override
    public InputStream open(String path, long start, long end) throws Exception {
        ensureConnected();
        synchronized (lock) {
            com.hierynomus.smbj.share.File file = share.openFile(
                    resolve(path),
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
            InputStream stream = file.getInputStream();
            try {
                // smbj 的 getInputStream 不支持带偏移，只能顺序跳过。
                long skipped = skipFully(stream, start);
                if (skipped < start) throw new IllegalStateException("无法定位到请求的位置");
            } catch (Exception e) {
                closeQuietly(stream);
                throw e;
            }
            return stream;
        }
    }

    @Override
    public long length(String path) throws Exception {
        ensureConnected();
        synchronized (lock) {
            return share.getFileInformation(resolve(path)).getStandardInformation().getEndOfFile();
        }
    }

    private String resolve(String path) {
        String root = account.getRoot();
        return path == null || path.isEmpty() ? root : NetDiskCodec.join(root, path, NetDiskType.SMB.separator());
    }

    private void ensureConnected() throws Exception {
        synchronized (lock) {
            if (share != null) return;
        }
        connect();
    }

    private static long skipFully(InputStream stream, long count) throws Exception {
        long remaining = count;
        while (remaining > 0) {
            long skipped = stream.skip(remaining);
            if (skipped <= 0) {
                // 有的实现 skip 到流尾就返回 0，此处退化成读一个字节来判断是否真到头。
                if (stream.read() < 0) return count - remaining;
                remaining--;
                continue;
            }
            remaining -= skipped;
        }
        return count;
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closeLocked();
        }
    }

    private void closeLocked() {
        // 逐层关闭并吞掉异常：任一层关闭失败不应妨碍其余层，否则会留下半死连接。
        try {
            if (share != null) share.close();
        } catch (Throwable ignored) {
        }
        try {
            if (session != null) session.close();
        } catch (Throwable ignored) {
        }
        try {
            if (connection != null) connection.close();
        } catch (Throwable ignored) {
        }
        try {
            if (client != null) client.close();
        } catch (Throwable ignored) {
        }
        share = null;
        session = null;
        connection = null;
        client = null;
    }
}
