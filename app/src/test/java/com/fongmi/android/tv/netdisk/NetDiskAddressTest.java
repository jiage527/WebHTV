package com.fongmi.android.tv.netdisk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link NetDiskAddress} 的解析与路径合成。
 *
 * <p>这个类的存在本身就是两个真机缺陷的产物：用户把整条网址粘进「服务器地址」，
 * 旧实现把它当主机名用（拼出 {@code http://https://...}，主机名成了字面量
 * {@code https}），而端口与路径又被各自重复处理一次。所以这里的断言都直接对着
 * 那两个现象的成因写。
 */
public class NetDiskAddressTest {

    private static final String DAV_URL = "https://dav.jianguoyun.com/dav/";

    @Test
    public void parsesAFullUrlIntoEveryPart() {
        NetDiskAddress.Parsed parsed = NetDiskAddress.parse(DAV_URL);
        assertEquals("dav.jianguoyun.com", parsed.host());
        assertEquals(0, parsed.port());
        assertTrue(parsed.secure());
        assertEquals("dav", parsed.path());
        assertTrue(parsed.hasScheme());
        assertFalse(parsed.isEmpty());
    }

    /** 中文目录必须原样留在路径里，交给下游逐段百分号编码。 */
    @Test
    public void keepsNonAsciiPathSegments() {
        NetDiskAddress.Parsed parsed = NetDiskAddress.parse("https://dav.jianguoyun.com/dav/影视");
        assertEquals("dav/影视", parsed.path());
    }

    @Test
    public void readsPortAndTlsFromTheUrl() {
        NetDiskAddress.Parsed parsed = NetDiskAddress.parse("webdavs://nas.example.com:8443/media");
        assertEquals("nas.example.com", parsed.host());
        assertEquals(8443, parsed.port());
        assertTrue(parsed.secure());
        assertEquals("media", parsed.path());
    }

    /** 明文地址不能被当成加密，否则会去连一个不存在的 TLS 端点。 */
    @Test
    public void plainUrlIsNotSecure() {
        assertFalse(NetDiskAddress.parse("http://nas.example.com/dav/").secure());
        assertFalse(NetDiskAddress.parse("webdav://nas.example.com/dav/").secure());
    }

    /** 裸主机与「主机:端口」两种常见写法仍要工作。 */
    @Test
    public void acceptsBareHostAndHostWithPort() {
        NetDiskAddress.Parsed bare = NetDiskAddress.parse("192.168.1.5");
        assertEquals("192.168.1.5", bare.host());
        assertEquals(0, bare.port());
        assertFalse(bare.hasScheme());

        NetDiskAddress.Parsed withPort = NetDiskAddress.parse("192.168.1.5:4455");
        assertEquals("192.168.1.5", withPort.host());
        assertEquals(4455, withPort.port());
    }

    /** 地址里带凭据时凭据要丢掉：账号密码有各自独立的输入框。 */
    @Test
    public void dropsCredentialsEmbeddedInTheUrl() {
        NetDiskAddress.Parsed parsed = NetDiskAddress.parse("webdavs://tom:secret@nas.example.com/media");
        assertEquals("nas.example.com", parsed.host());
        assertFalse(parsed.host().contains("tom"));
        assertFalse(parsed.host().contains("secret"));
    }

    /** IPv6 字面量里的冒号不能当端口分隔符切掉。 */
    @Test
    public void keepsIpv6LiteralIntact() {
        NetDiskAddress.Parsed parsed = NetDiskAddress.parse("http://[fe80::1]:8080/dav");
        assertEquals("[fe80::1]", parsed.host());
        assertEquals(8080, parsed.port());
    }

    /** 查询串与片段不属于路径。 */
    @Test
    public void cutsQueryAndFragment() {
        assertEquals("dav", NetDiskAddress.parse("https://h/dav?a=1#frag").path());
    }

    @Test
    public void emptyInputYieldsAnEmptyResult() {
        assertTrue(NetDiskAddress.parse(null).isEmpty());
        assertTrue(NetDiskAddress.parse("   ").isEmpty());
    }

    /**
     * 地址里的路径前缀与单独填的「目录」要合并。
     *
     * <p>{@code /dav/} 是服务端给的根前缀，「影视」是用户自己的目录，
     * 只取一个都会连错地方。
     */
    @Test
    public void mergeRootCombinesTheUrlPrefixAndTheTypedDirectory() {
        assertEquals("dav/影视", NetDiskAddress.mergeRoot("dav", "影视"));
        assertEquals("影视", NetDiskAddress.mergeRoot("", "影视"));
        assertEquals("dav", NetDiskAddress.mergeRoot("dav", ""));
        assertEquals("", NetDiskAddress.mergeRoot("", ""));
    }

    /**
     * 用户第二次编辑账号、又把整条网址粘一遍时不能拼成 {@code dav/dav/影视}。
     *
     * <p>这是很常见的操作：地址框里留着上次那条网址，目录框里是「影视」。
     */
    @Test
    public void mergeRootDoesNotDuplicateAnAlreadyPresentPrefix() {
        assertEquals("dav/影视", NetDiskAddress.mergeRoot("dav", "dav/影视"));
        assertEquals("dav", NetDiskAddress.mergeRoot("dav", "dav"));
    }

    /**
     * base 已含账号根目录时，调用方再传一次根开始的完整路径必须被剥掉。
     *
     * <p>这是「点进去显示无法访问」的成因：两条路径叠加请求到
     * {@code /dav/影视/dav/影视/}，服务端回 404（实测：{@code /dav/影视/} 是 207）。
     */
    @Test
    public void stripRootRemovesTheAccountRootFromAFullPath() {
        assertEquals("", NetDiskAddress.stripRoot("dav/影视", "dav/影视"));
        assertEquals("sub", NetDiskAddress.stripRoot("dav/影视/sub", "dav/影视"));
        assertEquals("sub/deep", NetDiskAddress.stripRoot("dav/影视/sub/deep", "dav/影视"));
    }

    /** 本来就传相对路径的地方（真子目录）不能被误剥。 */
    @Test
    public void stripRootLeavesRelativePathsAlone() {
        assertEquals("sub", NetDiskAddress.stripRoot("sub", "dav/影视"));
        assertEquals("", NetDiskAddress.stripRoot("", "dav/影视"));
        assertEquals("dav/other", NetDiskAddress.stripRoot("dav/other", "dav/影视"));
    }

    /** 账号没设根目录时不该剥掉任何东西。 */
    @Test
    public void stripRootIsANoOpWithoutARoot() {
        assertEquals("dav/影视", NetDiskAddress.stripRoot("dav/影视", ""));
        assertEquals("dav/影视", NetDiskAddress.stripRoot("dav/影视", null));
    }

    /**
     * 平台目录名被当作根目录时，前缀相同的兄弟目录不能被误剥。
     *
     * <p>{@code dav/影视两} 并不在 {@code dav/影视} 之下，按字符前缀切会切错。
     */
    @Test
    public void stripRootComparesPathSegmentsNotCharacters() {
        assertEquals("dav/影视两", NetDiskAddress.stripRoot("dav/影视两", "dav/影视"));
    }

    /** 地址里写明的端口最优先：那是用户在那串网址里敲的。 */
    @Test
    public void resolvePortPrefersThePortWrittenInTheUrl() {
        assertEquals(8443, NetDiskAddress.resolvePort(NetDiskType.WEBDAV, 443, 8443, true, true));
    }

    /**
     * 自动填进来的明文默认端口在启用 TLS 后必须让位。
     *
     * <p>表单先切类型（按明文算出 80）、用户后粘 https 地址，那个 80 会留在框里，
     * 拼出 {@code https://host:80/...}；实测这种地址直接连不上（curl 返回 000）。
     */
    @Test
    public void resolvePortDropsAStalePlaintextDefault() {
        assertEquals(0, NetDiskAddress.resolvePort(NetDiskType.WEBDAV, 80, 0, true, true));
    }

    /** 手填的其它端口是用户的明确意图，一律保留。 */
    @Test
    public void resolvePortKeepsExplicitPorts() {
        assertEquals(8443, NetDiskAddress.resolvePort(NetDiskType.WEBDAV, 8443, 0, true, true));
        assertEquals(443, NetDiskAddress.resolvePort(NetDiskType.WEBDAV, 443, 0, true, true));
    }

    /** 没写 scheme 时无从判断传输方式，不能擅自改端口。 */
    @Test
    public void resolvePortKeepsTypedPortWithoutAScheme() {
        assertEquals(80, NetDiskAddress.resolvePort(NetDiskType.WEBDAV, 80, 0, false, false));
    }

    /** SMB 的默认端口不随加密变化，不存在「残留」一说。 */
    @Test
    public void resolvePortNeverTouchesSmb() {
        assertEquals(445, NetDiskAddress.resolvePort(NetDiskType.SMB, 445, 0, true, true));
    }

    /** 端口框留空时返回 0，交给协议默认值。 */
    @Test
    public void resolvePortFallsBackToTheProtocolDefault() {
        assertEquals(0, NetDiskAddress.resolvePort(NetDiskType.WEBDAV, 0, 0, true, true));
    }
}
