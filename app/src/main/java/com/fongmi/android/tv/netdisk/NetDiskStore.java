package com.fongmi.android.tv.netdisk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 已保存的网络存储账号清单。
 *
 * <p><b>为什么不建 Room 表。</b>项目对新增数据库迁移有硬约束（迁移写错会让老用户
 * 升级后直接打不开），而账号数量是个位数量级，用应用私有目录里的 JSON 文件足够，
 * 且与既有的 {@code KeepPlaylistStore}、{@code PlayQueueStore} 做法一致。
 *
 * <p>本类<b>只</b>管清单本身，密码由 {@code NetDiskCredentialStore} 单独加密保存，
 * 所以这里的序列化天然不含密码。
 */
public final class NetDiskStore {

    private final List<NetDiskAccount> accounts = new ArrayList<>();

    public static NetDiskStore empty() {
        return new NetDiskStore();
    }

    public List<NetDiskAccount> getAccounts() {
        return Collections.unmodifiableList(accounts);
    }

    public boolean isEmpty() {
        return accounts.isEmpty();
    }

    public int size() {
        return accounts.size();
    }

    public NetDiskAccount get(String id) {
        if (id == null) return null;
        for (NetDiskAccount account : accounts) if (id.equals(account.getId())) return account;
        return null;
    }

    /** 新增；同 id 视为替换，避免重复保存产生两份配置。 */
    public void put(NetDiskAccount account) {
        if (account == null) return;
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).getId().equals(account.getId())) {
                accounts.set(i, account);
                return;
            }
        }
        accounts.add(account);
    }

    public boolean remove(String id) {
        if (id == null) return false;
        for (int i = 0; i < accounts.size(); i++) {
            if (id.equals(accounts.get(i).getId())) {
                accounts.remove(i);
                return true;
            }
        }
        return false;
    }

    /**
     * 按主机/共享/用户名找同一条配置。
     *
     * <p>用于处理「用户直接手输一个 smb:// 地址播放」的场景：地址里的主机若与某个
     * 已保存账号一致，就可以直接复用它的凭据，不必再让用户输一次密码。
     */
    public NetDiskAccount match(NetDiskUrl.Remote remote) {
        if (remote == null) return null;
        String host = remote.getHost().toLowerCase(java.util.Locale.ROOT);
        String share = remote.getShare().toLowerCase(java.util.Locale.ROOT);
        String user = remote.getUsername().toLowerCase(java.util.Locale.ROOT);
        for (NetDiskAccount account : accounts) {
            if (account.getType() != remote.getType()) continue;
            if (!account.getHost().toLowerCase(java.util.Locale.ROOT).equals(host)) continue;
            if (account.getType() == NetDiskType.SMB && !account.getShare().toLowerCase(java.util.Locale.ROOT).equals(share)) continue;
            if (!user.isEmpty() && !account.getUsername().toLowerCase(java.util.Locale.ROOT).equals(user)) continue;
            return account;
        }
        return null;
    }

    public String toJson() {
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        for (NetDiskAccount account : accounts) array.add(account.toJson());
        root.add("accounts", array);
        return root.toString();
    }

    public static NetDiskStore fromJson(String text) {
        NetDiskStore store = new NetDiskStore();
        if (text == null || text.isEmpty()) return store;
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) return store;
            JsonElement array = parsed.getAsJsonObject().get("accounts");
            if (array == null || !array.isJsonArray()) return store;
            for (JsonElement element : array.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                NetDiskAccount account = NetDiskAccount.fromJson(element.getAsJsonObject());
                if (account != null) store.accounts.add(account);
            }
        } catch (Throwable e) {
            return new NetDiskStore();
        }
        return store;
    }
}
