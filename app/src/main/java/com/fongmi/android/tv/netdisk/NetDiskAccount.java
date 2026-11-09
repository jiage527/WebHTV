package com.fongmi.android.tv.netdisk;

import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.UUID;

/**
 * 一个已挂载的网络存储账号。
 *
 * <p>{@link #password} 刻意<b>不</b>参与 {@link #toJson()}：账号清单以明文 JSON 落在
 * 应用私有目录，密码必须单独走加密存储（见 {@code NetDiskCredentialStore}）。
 * 序列化时漏写密码，比在每处调用点都记得删掉它更不容易出错。
 */
public final class NetDiskAccount {

    private String id = "";
    private String name = "";
    private NetDiskType type = NetDiskType.SMB;
    private String host = "";
    private int port;
    private boolean secure;
    private String share = "";
    private String root = "";
    private String username = "";
    private String domain = "";
    private String password = "";

    public static NetDiskAccount create(NetDiskType type) {
        NetDiskAccount account = new NetDiskAccount();
        account.id = UUID.randomUUID().toString();
        account.type = type == null ? NetDiskType.SMB : type;
        return account;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id == null ? "" : id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name.trim();
    }

    public NetDiskType getType() {
        return type == null ? NetDiskType.SMB : type;
    }

    public void setType(NetDiskType type) {
        this.type = type == null ? NetDiskType.SMB : type;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host == null ? "" : host.trim();
    }

    public int getPort() {
        return port > 0 ? port : getType().defaultPort(secure);
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isSecure() {
        return secure;
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    /** SMB 的共享名；WebDAV 不用，恒为空。 */
    public String getShare() {
        return share;
    }

    public void setShare(String share) {
        this.share = share == null ? "" : share.trim();
    }

    /** 账号内的起始路径（SMB 是共享内路径，WebDAV 是服务器上路径）。 */
    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root == null ? "" : root.trim();
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username == null ? "" : username.trim();
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain == null ? "" : domain.trim();
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password == null ? "" : password;
    }

    /** 列表里显示的标题；没填名称时退回主机名。 */
    public String getDisplayName() {
        if (!name.isEmpty()) return name;
        if (getType() == NetDiskType.SMB && !share.isEmpty()) return host + "/" + share;
        return host;
    }

    /** 缺主机或缺共享名都不算可用。 */
    public boolean isUsable() {
        if (host.isEmpty()) return false;
        return getType() != NetDiskType.SMB || !share.isEmpty();
    }

    /** 主机+端口+共享+帐号，用于把外部 URL 对应到已保存的账号。 */
    public String key() {
        String left = host.toLowerCase(Locale.ROOT) + ":" + getPort() + "/" + share.toLowerCase(Locale.ROOT);
        return getType().id() + "|" + username.toLowerCase(Locale.ROOT) + "@" + left;
    }

    /** 不含凭据的展示地址，可安全写日志。 */
    public String describe() {
        return getType().id() + "://" + host + ":" + getPort() + (share.isEmpty() ? "" : "/" + share);
    }

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("id", id);
        object.addProperty("name", name);
        object.addProperty("type", getType().id());
        object.addProperty("host", host);
        object.addProperty("port", port);
        object.addProperty("secure", secure);
        object.addProperty("share", share);
        object.addProperty("root", root);
        object.addProperty("username", username);
        object.addProperty("domain", domain);
        // 密码不写在这里，见类注释。
        return object;
    }

    public static NetDiskAccount fromJson(JsonObject object) {
        if (object == null) return null;
        NetDiskAccount account = new NetDiskAccount();
        account.id = string(object, "id");
        if (account.id.isEmpty()) account.id = UUID.randomUUID().toString();
        account.name = string(object, "name");
        account.type = NetDiskType.of(string(object, "type"));
        if (account.type == null) account.type = NetDiskType.SMB;
        account.host = string(object, "host");
        account.port = integer(object, "port");
        account.secure = bool(object, "secure");
        account.share = string(object, "share");
        account.root = string(object, "root");
        account.username = string(object, "username");
        account.domain = string(object, "domain");
        return account;
    }

    private static String string(JsonObject object, String key) {
        try {
            return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
        } catch (Throwable e) {
            return "";
        }
    }

    private static int integer(JsonObject object, String key) {
        try {
            return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    private static boolean bool(JsonObject object, String key) {
        try {
            return object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean();
        } catch (Throwable e) {
            return false;
        }
    }
}
