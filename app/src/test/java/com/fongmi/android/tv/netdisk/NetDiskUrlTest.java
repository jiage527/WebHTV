package com.fongmi.android.tv.netdisk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link NetDiskUrl} 与 {@link NetDiskCodec} 的解析规则。
 *
 * <p>这些用例盯的是几类真正会让播放失败的输入：共享名与路径要分开、百分号要按
 * 字节解码、带 {@code %} 或 {@code +} 的真实文件名不能被改写成别的路径、
 * 以及 {@code ..} 不能逃出账号根目录。
 */
public class NetDiskUrlTest {

    // ------------------------------------------------------------------ 协议识别

    @Test
    public void recognisesOnlyNetDiskSchemes() {
        assertTrue(NetDiskUrl.isNetDiskUrl("smb://nas/media/a.mkv"));
        assertTrue(NetDiskUrl.isNetDiskUrl("SMB://nas/media/a.mkv"));
        assertTrue(NetDiskUrl.isNetDiskUrl("webdav://nas/dav/a.mkv"));
        assertTrue(NetDiskUrl.isNetDiskUrl("webdavs://nas/dav/a.mkv"));
    }

    @Test
    public void ignoresOrdinaryHttpAndLocalPaths() {
        // 普通 http 绝不能被当成网盘，否则会把正常播放地址也改写掉。
        assertFalse(NetDiskUrl.isNetDiskUrl("https://example.com/a.mkv"));
        assertFalse(NetDiskUrl.isNetDiskUrl("http://127.0.0.1:9978/file/x.mp4"));
        assertFalse(NetDiskUrl.isNetDiskUrl("/storage/emulated/0/a.mkv"));
        assertFalse(NetDiskUrl.isNetDiskUrl("C:/Users/a.mkv"));
        assertFalse(NetDiskUrl.isNetDiskUrl("file:///sdcard/a.mkv"));
        assertFalse(NetDiskUrl.isNetDiskUrl(""));
        assertFalse(NetDiskUrl.isNetDiskUrl(null));
    }

    @Test
    public void schemeRequiresTripleSlashSeparator() {
        assertEquals("", NetDiskUrl.schemeOf("smb:/nas/a.mkv"));
        assertEquals("smb", NetDiskUrl.schemeOf("smb://nas/a.mkv"));
    }

    // ------------------------------------------------------------------ 解析

    @Test
    public void splitsSmbShareFromPath() {
        NetDiskUrl.Remote remote = NetDiskUrl.parse("smb://nas/media/电影/第一集.mkv");
        assertNotNull(remote);
        assertEquals(NetDiskType.SMB, remote.getType());
        assertEquals("nas", remote.getHost());
        assertEquals(445, remote.getPort());
        // 共享名必须单独拿出来：smbj 的 connectShare 只吃共享名，吃整串路径会连不上。
        assertEquals("media", remote.getShare());
        assertEquals("电影" + NetDiskType.SMB.separator() + "第一集.mkv", remote.getPath());
    }

    @Test
    public void webDavKeepsWholePathWithoutShare() {
        NetDiskUrl.Remote remote = NetDiskUrl.parse("webdav://nas/dav/media/a.mkv");
        assertNotNull(remote);
        assertEquals(NetDiskType.WEBDAV, remote.getType());
        assertEquals(80, remote.getPort());
        assertEquals("", remote.getShare());
        assertEquals("dav/media/a.mkv", remote.getPath());
    }

    @Test
    public void readsExplicitPort() {
        assertEquals(1445, NetDiskUrl.parse("smb://nas:1445/media/a.mkv").getPort());
        assertEquals(8080, NetDiskUrl.parse("webdav://nas:8080/dav/a.mkv").getPort());
        assertEquals(443, NetDiskUrl.parse("webdavs://nas/dav/a.mkv").getPort());
    }

    @Test
    public void keepsDefaultPortWhenPortIsNotNumeric() {
        // 主机名里带冒号但后面不是数字时不能当成端口，否则主机会被截断成无法解析的值。
        NetDiskUrl.Remote remote = NetDiskUrl.parse("smb://nas:abc/media/a.mkv");
        assertNotNull(remote);
        assertEquals(445, remote.getPort());
    }

    @Test
    public void rejectsUrlWithoutHost() {
        assertNull(NetDiskUrl.parse("smb:///media/a.mkv"));
        assertNull(NetDiskUrl.parse("http://nas/media/a.mkv"));
    }

    @Test
    public void parsesCredentialsWithDomain() {
        NetDiskUrl.Remote remote = NetDiskUrl.parse("smb://WORKGROUP%3Btom:pa%40ss@nas/media/a.mkv");
        assertNotNull(remote);
        assertEquals("nas", remote.getHost());
        // 用户名里的 %3B 要解回 ';'，密码里的 %40 要解回 '@'，
        // 否则会把转义后的字面量当用户名发过去、被服务端拒绝。
        assertEquals("WORKGROUP;tom", remote.getUsername());
        assertEquals("pa@ss", remote.getPassword());
    }

    @Test
    public void dropsQueryAndFragmentSoTheyDoNotBecomeFileNames() {
        NetDiskUrl.Remote remote = NetDiskUrl.parse("smb://nas/media/a.mkv?size=1#frag");
        assertNotNull(remote);
        assertEquals("media", remote.getShare());
        assertEquals("a.mkv", remote.getPath());
    }

    @Test
    public void backslashesInSmbPathAreTreatedAsSeparators() {
        NetDiskUrl.Remote remote = NetDiskUrl.parse("smb://nas/media/电视剧\\第一季\\01.mkv");
        assertNotNull(remote);
        assertEquals("media", remote.getShare());
        assertEquals("电视剧\\第一季\\01.mkv", remote.getPath());
    }

    @Test
    public void dotDotCannotEscapeAccountRoot() {
        NetDiskUrl.Remote remote = NetDiskUrl.parse("smb://nas/media/../../etc/passwd");
        assertNotNull(remote);
        // 路径不能把共享名弹掉：否则代理端点会去访问共享之外的目录。
        assertEquals("media", remote.getShare());
        assertEquals("etc\\passwd", remote.getPath());
    }

    @Test
    public void describeOmitsCredentials() {
        NetDiskUrl.Remote remote = NetDiskUrl.parse("smb://user:secret@nas/media/a.mkv");
        assertNotNull(remote);
        String text = remote.describe();
        assertFalse(text.contains("secret"));
        assertFalse(text.contains("user"));
        assertTrue(text.contains("nas"));
    }

    // ------------------------------------------------------------------ 编解码

    @Test
    public void encodeEscapesSpacesAsPercentTwenty() {
        // URLEncoder 会把空格编成 '+'，在路径段里是非法的，必须编成 %20。
        assertEquals("My%20Movie.mkv", NetDiskCodec.encodeSegment("My Movie.mkv"));
    }

    @Test
    public void encodeLeavesUnreservedCharactersAlone() {
        assertEquals("a-b_c.d~e", NetDiskCodec.encodeSegment("a-b_c.d~e"));
    }

    @Test
    public void encodeUsesUtf8ForChinese() {
        assertEquals("%E7%94%B5%E5%BD%B1", NetDiskCodec.encodeSegment("电影"));
    }

    @Test
    public void decodeRestoresEscapes() {
        assertEquals("My Movie.mkv", NetDiskCodec.decodeSegment("My%20Movie.mkv"));
        assertEquals("电影", NetDiskCodec.decodeSegment("%E7%94%B5%E5%BD%B1"));
        assertEquals("a+b", NetDiskCodec.decodeSegment("a+b"));
    }

    @Test
    public void decodeKeepsInvalidEscapesLiteral() {
        // 真实文件名里可能有单独的百分号，丢掉它会让路径指向别的文件。
        assertEquals("100%", NetDiskCodec.decodeSegment("100%"));
        assertEquals("a%zzb", NetDiskCodec.decodeSegment("a%zzb"));
    }

    @Test
    public void roundTripsAwkwardNames() {
        for (String name : new String[]{"a b.mkv", "电影 01.mkv", "100% real.mkv", "a+b.mkv", "a#b.mkv", "a&b.mkv"}) {
            assertEquals(name, NetDiskCodec.decodeSegment(NetDiskCodec.encodeSegment(name)));
        }
    }

    // ------------------------------------------------------------------ 路径

    @Test
    public void normalizeCollapsesEmptyAndDotSegments() {
        assertEquals("a/b", NetDiskCodec.normalize("/a//b/./", '/'));
        assertEquals("a\\b", NetDiskCodec.normalize("a\\\\b", '\\'));
    }

    @Test
    public void normalizePopsDotDotWithoutGoingAboveRoot() {
        assertEquals("b", NetDiskCodec.normalize("a/../b", '/'));
        assertEquals("", NetDiskCodec.normalize("..", '/'));
        assertEquals("a", NetDiskCodec.normalize("../a", '/'));
    }

    @Test
    public void parentAndNameSplitOnGivenSeparator() {
        assertEquals("a\\b", NetDiskCodec.parent("a\\b\\c.mkv", '\\'));
        assertEquals("c.mkv", NetDiskCodec.name("a\\b\\c.mkv", '\\'));
        assertEquals("", NetDiskCodec.parent("c.mkv", '\\'));
        assertTrue(NetDiskCodec.isRoot(""));
        assertTrue(NetDiskCodec.isRoot("/"));
    }

    @Test
    public void isMediaNameMatchesKnownExtensions() {
        assertTrue(NetDiskCodec.isMediaName("a.MKV"));
        assertTrue(NetDiskCodec.isMediaName("a.mp4"));
        assertTrue(NetDiskCodec.isMediaName("a.ass"));
        assertFalse(NetDiskCodec.isMediaName("a.txt"));
        assertFalse(NetDiskCodec.isMediaName("a"));
    }

    // ------------------------------------------------------------------ 代理地址

    @Test
    public void proxyUrlCarriesAccountAndEncodedPath() {
        String url = NetDiskUrl.toProxyUrl("http://127.0.0.1:9978", "acc-1", "电影\\a b.mkv");
        assertEquals("http://127.0.0.1:9978/netdisk?account=acc-1&path=%E7%94%B5%E5%BD%B1%5Ca%20b.mkv", url);
    }

    @Test
    public void proxyUrlWithoutPathStaysUsable() {
        assertEquals("http://127.0.0.1:9978/netdisk?account=acc-1",
                NetDiskUrl.toProxyUrl("http://127.0.0.1:9978", "acc-1", ""));
    }

    @Test
    public void readsBackAccountAndPathFromProxyUrl() {
        String url = NetDiskUrl.toProxyUrl("http://127.0.0.1:9978", "acc 1", "a b\\c.mkv");
        assertEquals("acc 1", NetDiskUrl.accountOfProxyUrl(url));
        assertEquals("a b\\c.mkv", NetDiskUrl.pathOfProxyUrl(url));
    }

    @Test
    public void ignoresProxyLookupsOnOtherUrls() {
        assertEquals("", NetDiskUrl.accountOfProxyUrl("http://127.0.0.1:9978/file/a.mkv"));
        assertEquals("", NetDiskUrl.pathOfProxyUrl("http://127.0.0.1:9978/file/a.mkv"));
        assertEquals("", NetDiskUrl.pathOfProxyUrl(null));
    }

    // ------------------------------------------------------------------ FTP

    @Test
    public void parsesFtpUrl() {
        NetDiskUrl.Remote remote = NetDiskUrl.parse("ftp://user:pass@nas.local:2121/media/movie.mp4");
        assertEquals(NetDiskType.FTP, remote.getType());
        assertEquals("nas.local", remote.getHost());
        assertEquals(2121, remote.getPort());
        assertEquals("media/movie.mp4", remote.getPath());
        assertEquals("user", remote.getUsername());
        assertEquals("pass", remote.getPassword());
    }

    @Test
    public void ftpHasNoShareConcept() {
        // SMB 的 /media/movie.mp4 里 media 是共享名；FTP 没有这一层，
        // 整条路径都属于服务器根之下的内容。
        NetDiskUrl.Remote remote = NetDiskUrl.parse("ftp://nas/media/movie.mp4");
        assertEquals("", remote.getShare());
        assertEquals("media/movie.mp4", remote.getPath());
    }

    @Test
    public void ftpDefaultPortIs21() {
        NetDiskUrl.Remote remote = NetDiskUrl.parse("ftp://nas/media");
        assertEquals(21, remote.getPort());
        assertEquals(NetDiskType.FTP, NetDiskUrl.parse("ftp://nas/").getType());
    }

    @Test
    public void ftpIsRecognisedAsNetDiskUrl() {
        assertTrue(NetDiskUrl.isNetDiskUrl("ftp://nas/movie.mp4"));
        assertFalse(NetDiskUrl.isNetDiskUrl("ftp:/nas"));
        assertFalse(NetDiskUrl.isNetDiskUrl("ftps://nas/x"));
    }

    @Test
    public void ftpCannotEscapeRootWithDotDot() {
        // 代理端点按账号鉴权，路径逃出根目录就必须不可能。
        NetDiskUrl.Remote remote = NetDiskUrl.parse("ftp://nas/a/../../etc/passwd");
        assertEquals("etc/passwd", remote.getPath());
        // 根目录上的 ".." 是无效操作（无处可退），退完仍在根目录下，
        // 所以剩下的 etc 是根的子项，而不是被弹到根之外。
        assertEquals("etc", NetDiskUrl.parse("ftp://nas/../../etc").getPath());
        assertFalse(NetDiskUrl.parse("ftp://nas/../../etc").getPath().contains(".."));
        // 全由 ".." 组成的路径被压成根目录本身。
        assertEquals("", NetDiskUrl.parse("ftp://nas/../..").getPath());
    }

    @Test
    public void ftpKeepsSpacesAndDecodesEscapes() {
        assertEquals("My Movie.mp4", NetDiskUrl.parse("ftp://nas/My%20Movie.mp4").getPath());
        assertEquals("My Movie.mp4", NetDiskUrl.parse("ftp://nas/My Movie.mp4").getPath());
    }
}
