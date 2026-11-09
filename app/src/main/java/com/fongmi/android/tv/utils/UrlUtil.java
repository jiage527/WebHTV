package com.fongmi.android.tv.utils;

import android.net.Uri;

import com.fongmi.android.tv.netdisk.NetDiskAccount;
import com.fongmi.android.tv.netdisk.NetDiskCodec;
import com.fongmi.android.tv.netdisk.NetDiskCredentialStore;
import com.fongmi.android.tv.netdisk.NetDiskStore;
import com.fongmi.android.tv.netdisk.NetDiskType;
import com.fongmi.android.tv.netdisk.NetDiskUrl;
import com.fongmi.android.tv.server.Server;
import com.github.catvod.utils.UriUtil;
import com.google.common.net.HttpHeaders;

public class UrlUtil {

    public static Uri uri(String url) {
        return Uri.parse(url.trim().replace("\\", ""));
    }

    public static String scheme(String url) {
        return url == null ? "" : scheme(Uri.parse(url));
    }

    public static String scheme(Uri uri) {
        String scheme = uri.getScheme();
        return scheme == null ? "" : scheme.toLowerCase().trim();
    }

    public static String host(String url) {
        return url == null ? "" : host(Uri.parse(url));
    }

    public static String host(Uri uri) {
        String host = uri.getHost();
        return host == null ? "" : host.toLowerCase().trim();
    }

    public static String path(String url) {
        return url == null ? "" : path(Uri.parse(url));
    }

    public static String path(Uri uri) {
        String path = uri.getLastPathSegment();
        return path == null ? "" : path.trim();
    }

    public static String resolve(String baseUri, String referenceUri) {
        return UriUtil.resolve(baseUri, referenceUri);
    }

    public static String convert(String url) {
        String scheme = scheme(url);
        String path = null;
        if ("assets".equals(scheme)) path = "/";
        else if ("file".equals(scheme)) path = "/file/";
        else if ("proxy".equals(scheme)) path = "/proxy?";
        return path != null ? url.replace(scheme + "://", Server.get().getAddress(path)) : url;
    }

    /**
     * 把网络存储地址（{@code smb://}、{@code webdav://}）换成播放器能读的本机地址。
     *
     * <p>播放器读不了 {@code smb://}：随包 {@code libmpv.so} 既无 libsmbclient 也无
     * {@code smb://} 字样（双 ABI 核对过），Exo 也没有 SMB 数据源。所以这里把它换成
     * 指向本机 {@code /netdisk} 端点的 http 地址，由该端点去真的读字节。
     *
     * <p>地址里已写明用户名与密码时优先用它；否则按主机与共享名去找已保存的账号，
     * 这样用户粘贴一个 NAS 地址也能直接播，不必先手动建一条配置。
     *
     * @return 可播放地址；不是网络存储地址、或找不到可用账号时原样返回
     */
    public static String convertNetDisk(String url) {
        NetDiskUrl.Remote remote = NetDiskUrl.parse(url);
        if (remote == null || remote.getHost().isEmpty()) return url;
        NetDiskStore store = NetDiskCredentialStore.get();
        NetDiskAccount account = store.match(remote);
        if (account != null) {
            String path = remote.getType() == NetDiskType.SMB
                    ? NetDiskCodec.join(remote.getShare(), remote.getPath(), NetDiskType.SMB.separator())
                    : remote.getPath();
            return NetDiskUrl.toProxyUrl(Server.get().getAddress(true), account.getId(), path);
        }
        // 地址自带凭据：临时建一条账号直接用于播放，不落盘，也不覆盖任何已有配置。
        if (remote.getUsername().isEmpty()) return url;
        NetDiskAccount temp = NetDiskAccount.create(remote.getType());
        temp.setHost(remote.getHost());
        temp.setPort(remote.getPort());
        temp.setSecure(remote.isSecure());
        temp.setShare(remote.getShare());
        temp.setUsername(remote.getUsername());
        temp.setPassword(remote.getPassword());
        if (!temp.isUsable()) return url;
        NetDiskCredentialStore.get().put(temp);
        NetDiskCredentialStore.putPassword(temp.getId(), temp.getPassword());
        NetDiskCredentialStore.save();
        String path = remote.getType() == NetDiskType.SMB
                ? NetDiskCodec.join(remote.getShare(), remote.getPath(), NetDiskType.SMB.separator())
                : remote.getPath();
        return NetDiskUrl.toProxyUrl(Server.get().getAddress(true), temp.getId(), path);
    }

    public static String getName(String url) {
        Uri uri = Uri.parse(url);
        String path = path(uri);
        String host = host(uri);
        return !path.isEmpty() ? path : !host.isEmpty() ? host : url;
    }

    public static String fixHeader(String key) {
        if (HttpHeaders.USER_AGENT.equalsIgnoreCase(key)) return HttpHeaders.USER_AGENT;
        if (HttpHeaders.REFERER.equalsIgnoreCase(key)) return HttpHeaders.REFERER;
        if (HttpHeaders.COOKIE.equalsIgnoreCase(key)) return HttpHeaders.COOKIE;
        return key;
    }
}
