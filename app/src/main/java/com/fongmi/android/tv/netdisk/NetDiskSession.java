package com.fongmi.android.tv.netdisk;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 已连接账号的客户端缓存。
 *
 * <p>建连（SMB 的 TCP+协商+认证，WebDAV 的 PROPFIND）比列目录本身贵得多，
 * 所以按账号缓存客户端。缓存的是<b>失败也会被清掉</b>的条目：一次连接失败后
 * 如果留下坏对象，用户改完密码再试仍会连到旧会话上，看起来像「改了没用」。
 *
 * <p>所有方法都是线程安全的：目录列举跑在后台线程，而页面可能同时被销毁。
 */
public final class NetDiskSession implements Closeable {

    private static final NetDiskSession INSTANCE = new NetDiskSession();

    private final Map<String, NetDiskClient> clients = new HashMap<>();

    private NetDiskSession() {
    }

    public static NetDiskSession get() {
        return INSTANCE;
    }

    /**
     * 取得（必要时建立）账号的客户端。抛出异常时不会缓存半成品。
     *
     * <p>口令只在账号自己没带的情况下才从加密存储里取。用户直接粘一个带凭据的
     * {@code smb://} 地址时，口令就在地址里，不该被存储里的空值覆盖掉。
     */
    public NetDiskClient client(NetDiskAccount account) throws Exception {
        if (account == null) throw new IllegalArgumentException("账号为空");
        if (!account.isUsable()) throw new IllegalArgumentException("账号信息不完整");
        NetDiskAccount ready = account;
        if (ready.getPassword().isEmpty()) ready = NetDiskCredentialStore.withPassword(account);
        synchronized (this) {
            NetDiskClient cached = clients.get(ready.getId());
            if (cached != null) return cached;
        }
        NetDiskClient created = create(ready);
        try {
            created.connect();
        } catch (Exception e) {
            // 连不上就不要留下这个对象，否则下次会直接复用它。
            closeQuietly(created);
            throw e;
        }
        synchronized (this) {
            NetDiskClient existing = clients.putIfAbsent(ready.getId(), created);
            if (existing != null) {
                // 并发情况下别人先放进去了，关掉自己这份，免得泄漏连接。
                closeQuietly(created);
                return existing;
            }
        }
        return created;
    }

    /** 丢弃某个账号的连接，改配置或断开时调用。 */
    public void invalidate(String accountId) {
        if (accountId == null) return;
        NetDiskClient client;
        synchronized (this) {
            client = clients.remove(accountId);
        }
        closeQuietly(client);
    }

    /** 列目录，并在失败时清掉缓存以便下次重连。 */
    public List<NetDiskEntry> list(NetDiskAccount account, String path) throws Exception {
        try {
            return client(account).list(path);
        } catch (Exception e) {
            invalidate(account == null ? null : account.getId());
            throw e;
        }
    }

    private static NetDiskClient create(NetDiskAccount account) {
        if (account.getType() == NetDiskType.SMB) return new SmbNetDiskClient(account);
        return new WebDavNetDiskClient(account);
    }

    private static void closeQuietly(NetDiskClient client) {
        if (client == null) return;
        try {
            client.close();
        } catch (Throwable ignored) {
        }
    }

    /** 只用于测试。 */
    synchronized int cachedCount() {
        return clients.size();
    }

    @Override
    public void close() {
        List<NetDiskClient> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(clients.values());
            clients.clear();
        }
        for (NetDiskClient client : snapshot) closeQuietly(client);
    }
}
