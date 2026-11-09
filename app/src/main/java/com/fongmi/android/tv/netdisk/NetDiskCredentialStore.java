package com.fongmi.android.tv.netdisk;

import android.content.Context;
import android.content.SharedPreferences;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Path;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * 网络存储账号清单的落盘与缓存，以及密码的加密保存。
 *
 * <p>清单走应用私有目录的 JSON 文件，密码走 AndroidKeyStore（不可用时回退到本地密钥），
 * 复用与 {@code GitCloudTokenStore} 相同的方案 —— 后者已经在本项目里处理过
 * 「部分 OEM 没有 AndroidKeyStore」的情况，不另起一套。
 */
public final class NetDiskCredentialStore {

    private static final String FILE_NAME = "net_disk_accounts.json";
    private static final String PREF = "net_disk_credentials";
    private static final String ALIAS_PREFIX = "webhtv_net_disk_";

    private static NetDiskStore cache;

    private NetDiskCredentialStore() {
    }

    // ------------------------------------------------------------------ 清单

    public static synchronized NetDiskStore get() {
        if (cache == null) cache = NetDiskStore.fromJson(Path.read(file()));
        return cache;
    }

    public static synchronized void save() {
        NetDiskStore store = cache;
        if (store == null) return;
        try {
            Path.write(file(), store.toJson().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 落盘失败不阻断使用：内存里仍是准的，下次改动会再试。
        }
    }

    public static synchronized void reload() {
        cache = NetDiskStore.fromJson(Path.read(file()));
    }

    private static File file() {
        return Path.files(FILE_NAME);
    }

    // ------------------------------------------------------------------ 密码

    public static void putPassword(String accountId, String password) {
        if (accountId == null || accountId.isEmpty()) return;
        String value = password == null ? "" : password;
        try {
            prefs().edit().putString(key(accountId), NetDiskCrypto.encrypt(value)).apply();
        } catch (Throwable e) {
            // 加密不可用时绝不明文落盘，宁可本次会话内存有效。
        }
    }

    public static String getPassword(String accountId) {
        if (accountId == null || accountId.isEmpty()) return "";
        String stored = prefs().getString(key(accountId), "");
        if (stored == null || stored.isEmpty()) return "";
        try {
            return NetDiskCrypto.decrypt(stored);
        } catch (Throwable e) {
            return "";
        }
    }

    public static void removePassword(String accountId) {
        if (accountId == null || accountId.isEmpty()) return;
        prefs().edit().remove(key(accountId)).apply();
    }

    /** 把密码装进账号对象，供客户端使用。 */
    public static NetDiskAccount withPassword(NetDiskAccount account) {
        if (account == null) return null;
        account.setPassword(getPassword(account.getId()));
        return account;
    }

    private static String key(String accountId) {
        return ALIAS_PREFIX + accountId;
    }

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 仅用于测试：清掉内存缓存。 */
    static synchronized void resetCache() {
        cache = null;
    }
}
