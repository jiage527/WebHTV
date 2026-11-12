package com.fongmi.android.tv.netdisk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link NetDiskStore} 与 {@link NetDiskAccount} 的模型行为。
 *
 * <p>重点盯两件事：账号清单的 JSON 往返必须不丢配置（否则重启后连不上），
 * 以及 JSON 里<b>绝不能</b>出现密码（清单是明文文件）。
 */
public class NetDiskStoreTest {

    private static NetDiskAccount smb(String id, String host, String share, String user) {
        NetDiskAccount account = NetDiskAccount.create(NetDiskType.SMB);
        account.setId(id);
        account.setName("我的 NAS");
        account.setHost(host);
        account.setShare(share);
        account.setUsername(user);
        account.setRoot("media");
        return account;
    }

    @Test
    public void jsonRoundTripKeepsEveryFieldExceptPassword() {
        NetDiskAccount account = smb("a1", "192.168.1.10", "video", "tom");
        account.setDomain("WORKGROUP");
        account.setPort(1445);
        account.setPassword("secret");

        NetDiskStore store = NetDiskStore.empty();
        store.put(account);
        NetDiskStore restored = NetDiskStore.fromJson(store.toJson());

        assertEquals(1, restored.size());
        NetDiskAccount back = restored.get("a1");
        assertNotNull(back);
        assertEquals("我的 NAS", back.getName());
        assertEquals(NetDiskType.SMB, back.getType());
        assertEquals("192.168.1.10", back.getHost());
        assertEquals(1445, back.getPort());
        assertEquals("video", back.getShare());
        assertEquals("media", back.getRoot());
        assertEquals("tom", back.getUsername());
        assertEquals("WORKGROUP", back.getDomain());
        // 密码走单独的加密存储，绝不出现在清单 JSON 里。
        assertEquals("", back.getPassword());
    }

    @Test
    public void serialisedJsonNeverContainsPassword() {
        NetDiskAccount account = smb("a1", "nas", "video", "tom");
        account.setPassword("topsecret");
        NetDiskStore store = NetDiskStore.empty();
        store.put(account);
        String json = store.toJson();
        assertFalse(json.contains("topsecret"));
        // 连字段名都不该出现，避免以后有人顺手把它加回来。
        assertFalse(json.contains("password"));
    }

    @Test
    public void putWithSameIdReplacesInsteadOfDuplicating() {
        NetDiskStore store = NetDiskStore.empty();
        NetDiskAccount account = smb("a1", "nas1", "video", "tom");
        store.put(account);
        account.setHost("nas2");
        store.put(account);
        assertEquals(1, store.size());
        assertEquals("nas2", store.get("a1").getHost());
    }

    @Test
    public void removeDropsOnlyNamedAccount() {
        NetDiskStore store = NetDiskStore.empty();
        store.put(smb("a1", "nas1", "video", "tom"));
        store.put(smb("a2", "nas2", "video", "tom"));
        assertTrue(store.remove("a1"));
        assertEquals(1, store.size());
        assertNull(store.get("a1"));
        assertNotNull(store.get("a2"));
        assertFalse(store.remove("missing"));
    }

    @Test
    public void damagedJsonYieldsEmptyStoreRatherThanThrowing() {
        // 文件损坏时必须能启动，否则用户连设置页都进不去、无法修复。
        for (String text : new String[]{"", "not json", "[1,2,3]", "{\"accounts\":{}}", "{}"}) {
            NetDiskStore store = NetDiskStore.fromJson(text);
            assertNotNull(store);
            assertEquals(0, store.size());
        }
        // null 同样不能抛异常。
        assertEquals(0, NetDiskStore.fromJson(null).size());
    }

    @Test
    public void malformedEntriesAreSkippedButValidOnesSurvive() {
        NetDiskStore store = NetDiskStore.fromJson("{\"accounts\":[{\"host\":\"nas\"},123,\"x\"]}");
        assertEquals(1, store.size());
        assertEquals("nas", store.getAccounts().get(0).getHost());
    }

    @Test
    public void missingIdGetsGeneratedSoEntryStaysAddressable() {
        NetDiskStore store = NetDiskStore.fromJson("{\"accounts\":[{\"host\":\"nas\",\"type\":\"smb\"}]}");
        assertEquals(1, store.size());
        assertFalse(store.getAccounts().get(0).getId().isEmpty());
    }

    // ------------------------------------------------------------------ 匹配

    @Test
    public void matchFindsAccountByHostShareAndUser() {
        NetDiskStore store = NetDiskStore.empty();
        store.put(smb("a1", "nas", "video", "tom"));
        NetDiskUrl.Remote remote = NetDiskUrl.parse("smb://tom@nas/video/a.mkv");
        assertNotNull(store.match(remote));
        assertEquals("a1", store.match(remote).getId());
    }

    @Test
    public void matchIgnoresHostCase() {
        NetDiskStore store = NetDiskStore.empty();
        store.put(smb("a1", "NAS", "video", "tom"));
        assertNotNull(store.match(NetDiskUrl.parse("smb://tom@nas/video/a.mkv")));
    }

    @Test
    public void matchRejectsDifferentShare() {
        NetDiskStore store = NetDiskStore.empty();
        store.put(smb("a1", "nas", "video", "tom"));
        // 共享不同就是另一处内容，不能拿这条配置的凭据去连。
        assertNull(store.match(NetDiskUrl.parse("smb://tom@nas/other/a.mkv")));
    }

    @Test
    public void matchRejectsDifferentProtocolEvenOnSameHost() {
        NetDiskStore store = NetDiskStore.empty();
        store.put(smb("a1", "nas", "video", "tom"));
        assertNull(store.match(NetDiskUrl.parse("webdav://tom@nas/video/a.mkv")));
    }

    @Test
    public void matchWithoutUserAcceptsAnyUserOnSameShare() {
        NetDiskStore store = NetDiskStore.empty();
        store.put(smb("a1", "nas", "video", "tom"));
        // 地址里没写用户名时按主机+共享找，这样粘贴「smb://nas/video/a.mkv」也能播。
        assertNotNull(store.match(NetDiskUrl.parse("smb://nas/video/a.mkv")));
    }

    // ------------------------------------------------------------------ 账号

    @Test
    public void accountNeedsHostAndShareToBeUsable() {
        assertFalse(NetDiskAccount.create(NetDiskType.SMB).isUsable());
        NetDiskAccount account = NetDiskAccount.create(NetDiskType.SMB);
        account.setHost("nas");
        assertFalse(account.isUsable());
        account.setShare("video");
        assertTrue(account.isUsable());
    }

    @Test
    public void webDavDoesNotNeedShare() {
        NetDiskAccount account = NetDiskAccount.create(NetDiskType.WEBDAV);
        account.setHost("nas");
        assertTrue(account.isUsable());
    }

    @Test
    public void portFallsBackToProtocolDefault() {
        NetDiskAccount account = NetDiskAccount.create(NetDiskType.SMB);
        assertEquals(445, account.getPort());
        account.setType(NetDiskType.WEBDAV);
        assertEquals(80, account.getPort());
        account.setSecure(true);
        assertEquals(443, account.getPort());
    }

    /**
     * 已启用加密时，被自动填进来的明文默认端口必须让位。
     *
     * <p>表单先切类型（按明文算出 80 填进端口框）、用户后粘地址（地址里是 https）。
     * 那个 80 会被存进账号，拼出来的就是 {@code https://host:80/...} —— 把 TLS 建在
     * 明文端口上。实测坚果云这种地址直接连不上（curl 返回 000），用户只看到「无法访问」。
     */
    @Test
    public void stalePlaintextPortIsDroppedOnceTlsIsOn() {
        NetDiskAccount account = NetDiskAccount.create(NetDiskType.WEBDAV);
        account.setHost("dav.jianguoyun.com");
        account.setPort(80);
        account.setSecure(true);
        assertEquals(443, account.getPort());
    }

    /**
     * 手填的非默认端口在多写之后仍然要保留。
     *
     * <p>只有「恰好等于明文默认端口」才判为残留；8443 这类自建端口是用户的明确意图。
     */
    @Test
    public void explicitPortSurvivesWhenTlsIsOn() {
        NetDiskAccount account = NetDiskAccount.create(NetDiskType.WEBDAV);
        account.setHost("nas.example.com");
        account.setPort(8443);
        account.setSecure(true);
        assertEquals(8443, account.getPort());
        account.setPort(443);
        assertEquals(443, account.getPort());
    }

    /**
     * 明文服务放在 443 上是真实存在的自建配置，不能替用户改写。
     *
     * <p>反方向（明文 + 加密默认端口）刻意不处理：443 明确写在端口框里，
     * 覆盖它等于把用户能连上的配置改成连不上的。
     */
    @Test
    public void plaintextOnTheTlsDefaultPortIsLeftAlone() {
        NetDiskAccount account = NetDiskAccount.create(NetDiskType.WEBDAV);
        account.setHost("nas.example.com");
        account.setPort(443);
        account.setSecure(false);
        assertEquals(443, account.getPort());
    }

    @Test
    public void displayNameFallsBackToHostAndShare() {
        NetDiskAccount account = NetDiskAccount.create(NetDiskType.SMB);
        account.setHost("nas");
        assertEquals("nas", account.getDisplayName());
        account.setShare("video");
        assertEquals("nas/video", account.getDisplayName());
        account.setName("客厅 NAS");
        assertEquals("客厅 NAS", account.getDisplayName());
    }

    @Test
    public void describeNeverLeaksPassword() {
        NetDiskAccount account = smb("a1", "nas", "video", "tom");
        account.setPassword("secret");
        assertFalse(account.describe().contains("secret"));
    }

    @Test
    public void createAssignsDistinctIds() {
        assertFalse(NetDiskAccount.create(NetDiskType.SMB).getId()
                .equals(NetDiskAccount.create(NetDiskType.SMB).getId()));
    }

    // ------------------------------------------------------------------ 条目

    @Test
    public void entriesSortDirectoriesFirstThenByNameIgnoringCase() {
        NetDiskEntry dir = new NetDiskEntry("b", "b", true, 0, 0);
        NetDiskEntry fileA = new NetDiskEntry("A.mkv", "A.mkv", false, 10, 0);
        NetDiskEntry fileB = new NetDiskEntry("b.mkv", "b.mkv", false, 10, 0);
        assertTrue(dir.compareTo(fileA) < 0);
        assertTrue(fileA.compareTo(fileB) < 0);
    }

    @Test
    public void entryIsPlayableOnlyForKnownMediaExtensions() {
        assertTrue(new NetDiskEntry("a.mkv", "a.mkv", false, 1, 0).isPlayable());
        assertFalse(new NetDiskEntry("a.mkv", "a.mkv", true, 0, 0).isPlayable());
        assertFalse(new NetDiskEntry("readme.txt", "readme.txt", false, 1, 0).isPlayable());
    }

    @Test
    public void typeFromSchemeAcceptsAliases() {
        assertEquals(NetDiskType.SMB, NetDiskType.fromScheme("smb"));
        assertEquals(NetDiskType.SMB, NetDiskType.fromScheme("SMB2"));
        assertEquals(NetDiskType.WEBDAV, NetDiskType.fromScheme("dav"));
        assertEquals(NetDiskType.WEBDAV, NetDiskType.fromScheme("webdavs"));
        assertNull(NetDiskType.fromScheme("http"));
        assertNull(NetDiskType.fromScheme(null));
    }

    @Test
    public void onlySecureSchemesRequireTls() {
        assertTrue(NetDiskType.isSecureScheme("webdavs"));
        assertTrue(NetDiskType.isSecureScheme("davs"));
        assertFalse(NetDiskType.isSecureScheme("webdav"));
        assertFalse(NetDiskType.isSecureScheme("smb"));
    }
}
