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

    /**
     * 实际要连的端口。
     *
     * <p><b>为什么要丢掉一部分存下来的端口值。</b>表单会在切换协议类型时把默认端口
     * 自动填进端口框，而填的那一刻还不知道服务器地址里写的是 http 还是 https——
     * 用户通常是先切类型、后粘地址。切到 WebDAV 时按明文算出 80 填进去，等地址里的
     * {@code https} 被解析出来、{@code secure} 变成 true 时，这个 80 已经存进账号了。
     * 于是拼出 {@code https://dav.jianguoyun.com:80/...}：把 TLS 建在明文端口上，
     * 实测直接连不上（curl 返回 000），用户看到的只是「无法访问」。
     *
     * <p>因此「已启用加密、端口却恰好是明文默认端口」一律判为自动填充的残留，回落到
     * 加密的默认端口。反方向（明文 + 加密默认端口）不处理：把明文服务放在 443 上是
     * 真实存在的自建配置，不能替用户改写他明确写下的端口。SMB 与 FTP 的默认端口
     * 不随加密变化，两个方向都相等，不会命中这条规则。
     */
    public int getPort() {
        if (port > 0 && !isStalePlaintextDefault(port)) return port;
        return getType().defaultPort(secure);
    }

    /**
     * 这个端口是不是「切类型时按明文算出来的默认值」。
     *
     * <p>条件是三者同时成立：当前这一档要求加密、两端默认端口确实不同（只有 WebDAV
     * 满足）、存下来的值正好是明文那一档的默认端口。
     */
    private boolean isStalePlaintextDefault(int value) {
        if (!secure) return false;
        int plaintext = getType().defaultPort(false);
        return plaintext != getType().defaultPort(true) && value == plaintext;
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
        // 只有 SMB 把「共享名」当作必填：它是 SMB 的访问边界。
        // WebDAV 与 FTP 的整条路径都在服务器根之下，没有共享名这一层。
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
