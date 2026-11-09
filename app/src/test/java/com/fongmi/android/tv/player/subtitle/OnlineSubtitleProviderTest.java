package com.fongmi.android.tv.player.subtitle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Exercises the parts of the online providers that can be checked without a
 * network: the SubHD search page parser and the gzip unwrapping.
 *
 * <p>The HTML fixture is the real markup the site served for one search, kept
 * verbatim so a provider-side markup change shows up as a failing assertion
 * rather than as an empty result list on a device.
 */
public class OnlineSubtitleProviderTest {

    @Test
    public void subHdSearchPageYieldsTheResultCards() throws Exception {
        String html = SubHdSearchPageFixture.INCEPTION;
        List<String> ids = SubHdParser.subtitleIds(html);
        assertFalse("no subtitle id found in the search page", ids.isEmpty());
        // The page is known to open with this release; its id must be the first one.
        assertEquals("58k0EQ", ids.get(0));
        assertEquals(ids.size(), new java.util.LinkedHashSet<>(ids).size());
    }

    @Test
    public void subHdCardCarriesReleaseLanguageFormatAndDownloads() throws Exception {
        String html = SubHdSearchPageFixture.INCEPTION;
        String id = SubHdParser.subtitleIds(html).get(0);
        OnlineSubtitle item = SubHdParser.toItem(id, html, "https://subhd.cc", "SubHD");

        assertNotNull(item);
        assertEquals(id, item.getId());
        assertEquals("https://subhd.cc/a/" + id, item.getDownloadUrl());
        assertEquals("SubHD", item.getSource());
        assertTrue(item.isUsable());
        // The release name is the identifying text, not the display title next to it.
        assertTrue(item.getRelease().startsWith("Inception.2010"));
        assertTrue("release should carry no markup: " + item.getRelease(), !item.getRelease().contains("<"));
        assertTrue("language markers were not read: " + item.getLanguage(), item.getLanguage().contains("双语"));
        assertEquals("ass", item.getFormat());
        assertTrue("download count was not read: " + item.getDownloads(), item.getDownloads() > 0);
    }

    @Test
    public void subHdCardWithoutAReleaseNameIsDiscarded() {
        // The ranking keys on the release name; a card that has none could not be
        // matched, so it is dropped instead of being offered as a blind choice.
        String html = "<div class=\"bg-white shadow-sm rounded-3 mb-4\"><a href='/a/abc123'>title</a></div></div></div></div>";
        assertTrue(SubHdParser.subtitleIds(html).contains("abc123"));
        assertEquals(null, SubHdParser.toItem("abc123", html, "https://subhd.cc", "SubHD"));
        assertEquals(null, SubHdParser.toItem("", html, "https://subhd.cc", "SubHD"));
        assertEquals(null, SubHdParser.toItem(null, html, "https://subhd.cc", "SubHD"));
    }

    @Test
    public void gzipPayloadIsUnwrappedAndPlainPayloadIsPassedThrough() throws Exception {
        String document = "1\n00:00:01,000 --> 00:00:03,000\n你好，世界\n";
        byte[] plain = document.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(plain);
        }
        byte[] gz = compressed.toByteArray();

        // The providers flag the payload as gzip; the magic must still be honoured.
        assertEquals(new String(plain, StandardCharsets.UTF_8), new String(OnlineSubtitleRepository.gunzip(gz), StandardCharsets.UTF_8));
        // A provider that stops compressing must not have its payload destroyed.
        assertEquals(new String(plain, StandardCharsets.UTF_8), new String(OnlineSubtitleRepository.gunzip(plain), StandardCharsets.UTF_8));

        // The document is then identified by its content, not by the URL or the extension.
        assertEquals("srt", SubtitleFileTranslator.detectFormat(new String(OnlineSubtitleRepository.gunzip(gz), StandardCharsets.UTF_8)));
        String ass = "[Script Info]\nTitle: x\n\n[V4+ Styles]\nFormat: Name\n\n[Events]\nFormat: Layer\n";
        assertEquals("ass", SubtitleFileTranslator.detectFormat(ass));
        assertEquals("vtt", SubtitleFileTranslator.detectFormat("WEBVTT\n\n00:00.000 --> 00:02.000\nhi\n"));
    }

    @Test
    public void truncatedGzipIsRejectedRatherThanReturnedAsGarbage() {
        byte[] truncated = new byte[]{(byte) 0x1F, (byte) 0x8B, 0x08, 0x00, 0x01, 0x02};
        assertEquals(0, OnlineSubtitleRepository.gunzip(truncated).length);
        assertEquals(0, OnlineSubtitleRepository.gunzip(null).length);
        assertEquals(0, OnlineSubtitleRepository.gunzip(new byte[0]).length);
    }

    @Test
    public void queryNeverReachesTheProviderWithoutATitle() {
        SubtitleMatchPolicy.MediaQuery empty = new SubtitleMatchPolicy.MediaQuery("", null, false, 0);
        assertTrue(OnlineSubtitleRepository.search(empty, List.of("zh-CN")).isEmpty());
    }

    @Test
    public void cookieJarKeepsOnlyTheSessionItWasGiven() {
        SubtitleCookieJar jar = new SubtitleCookieJar();
        okhttp3.HttpUrl subhd = okhttp3.HttpUrl.parse("https://subhd.cc");
        assertNotNull(subhd);
        assertTrue(jar.loadForRequest(subhd).isEmpty());
    }

}
