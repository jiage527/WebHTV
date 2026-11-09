package com.fongmi.android.tv.setting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

/**
 * Pins the rules behind "paste one address to add a site". These are the
 * behaviours a user notices immediately when they regress, so each one is
 * asserted by name rather than through a broad round-trip.
 */
public class CustomCspQuickAddTest {

    private static final String MUJI = "https://mujiziyuan00.com/api.php/provide/vod/?ac=list";

    @Test
    public void bareUrlIsAccepted() {
        CustomCspQuickAdd.Parsed parsed = CustomCspQuickAdd.parse(MUJI);
        assertTrue(parsed.isValid());
        assertEquals("mujiziyuan00", parsed.getName());
        assertEquals("https://mujiziyuan00.com/api.php/provide/vod/", parsed.getApi());
    }

    @Test
    public void machineQueryIsDropped() {
        assertEquals("https://a.com/api.php/provide/vod", CustomCspQuickAdd.stripQueryNoise("https://a.com/api.php/provide/vod?ac=list"));
        assertEquals("https://a.com/vod", CustomCspQuickAdd.stripQueryNoise("https://a.com/vod?ac=detail&pg=1&wd=x"));
        assertEquals("https://a.com/vod", CustomCspQuickAdd.stripQueryNoise("https://a.com/vod?ac=detail&ids=1"));
    }

    @Test
    public void unknownQueryIsPreserved() {
        assertEquals("https://a.com/vod?token=abc", CustomCspQuickAdd.stripQueryNoise("https://a.com/vod?token=abc"));
        assertEquals("https://a.com/vod?token=abc", CustomCspQuickAdd.stripQueryNoise("https://a.com/vod?ac=list&token=abc"));
    }

    @Test
    public void namePrefixIsUsedWhenPresent() {
        CustomCspQuickAdd.Parsed parsed = CustomCspQuickAdd.parse("木及资源 https://mujiziyuan00.com/api.php/provide/vod?ac=list");
        assertTrue(parsed.isValid());
        assertEquals("木及资源", parsed.getName());
    }

    @Test
    public void nameSeparatorsAreStripped() {
        assertEquals("木及", CustomCspQuickAdd.parse("木及：https://a.com/api").getName());
        assertEquals("木及", CustomCspQuickAdd.parse("木及, https://a.com/api").getName());
        assertEquals("木及", CustomCspQuickAdd.parse("\"木及\" https://a.com/api").getName());
        assertEquals("木及", CustomCspQuickAdd.parse("名称=木及 https://a.com/api").getName());
    }

    @Test
    public void nameFromUrlFormIsAccepted() {
        CustomCspQuickAdd.Parsed parsed = CustomCspQuickAdd.parse("url=https://a.com/api.php/provide/vod");
        assertTrue(parsed.isValid());
        assertEquals("https://a.com/api.php/provide/vod", parsed.getApi());
        assertEquals("a", parsed.getName());
    }

    @Test
    public void headerBlockBeforeUrlIsKept() {
        String text = "User-Agent: Mozilla/5.0\nReferer: https://a.com/\nhttps://a.com/api.php/provide/vod";
        CustomCspQuickAdd.Parsed parsed = CustomCspQuickAdd.parse(text);
        assertTrue(parsed.isValid());
        assertEquals("https://a.com/api.php/provide/vod", parsed.getApi());
        assertEquals("User-Agent: Mozilla/5.0\nReferer: https://a.com/", parsed.getHeaderText());
    }

    @Test
    public void refererWithUrlIsNotMistakenForTheInterface() {
        CustomCspQuickAdd.Parsed parsed = CustomCspQuickAdd.parse("Referer: https://evil.com/\nhttps://a.com/api.php/provide/vod");
        assertTrue(parsed.isValid());
        assertEquals("https://a.com/api.php/provide/vod", parsed.getApi());
        assertEquals("a", parsed.getName());
    }

    @Test
    public void headerOnlyPayloadIsRejected() {
        CustomCspQuickAdd.Parsed parsed = CustomCspQuickAdd.parse("User-Agent: Mozilla/5.0\nReferer: https://a.com/");
        assertFalse(parsed.isValid());
        assertEquals("no-url", parsed.getReason());
    }

    @Test
    public void emptyPayloadIsRejected() {
        assertFalse(CustomCspQuickAdd.parse("").isValid());
        assertFalse(CustomCspQuickAdd.parse("   ").isValid());
        assertFalse(CustomCspQuickAdd.parse(null).isValid());
        assertEquals("empty", CustomCspQuickAdd.parse("").getReason());
    }

    @Test
    public void nonHttpSchemeIsRejected() {
        assertEquals("bad-scheme", CustomCspQuickAdd.parse("ftp://a.com/vod").getReason());
        assertEquals("no-url", CustomCspQuickAdd.parse("csp_Builtin").getReason());
        assertEquals("no-url", CustomCspQuickAdd.parse("随便一段中文").getReason());
    }

    @Test
    public void maccmsJsonInterfaceMapsToTypeOne() {
        assertEquals(1, CustomCspQuickAdd.parse(MUJI).getType());
        assertEquals(1, CustomCspQuickAdd.parse("https://a.com/api.php/provide/vod").getType());
        assertEquals(1, CustomCspQuickAdd.parse("https://a.com/?ac=detail").getType());
    }

    @Test
    public void xmlFeedMapsToTypeZero() {
        assertEquals(0, CustomCspQuickAdd.parse("https://a.com/feed.xml").getType());
        assertEquals(0, CustomCspQuickAdd.parse("https://a.com/vod?ac=videolist").getType());
    }

    @Test
    public void xmlCheckIgnoresQuerySuffix() {
        assertEquals(0, CustomCspQuickAdd.detectType("https://a.com/feed.xml?ac=videolist"));
        assertEquals(1, CustomCspQuickAdd.detectType("https://a.com/feed.xml.php"));
    }

    @Test
    public void duplicateDetectionIgnoresNoiseAndScheme() {
        assertTrue(CustomCspQuickAdd.isDuplicate("https://a.com/api.php/provide/vod?ac=list", "http://a.com/api.php/provide/vod"));
        assertTrue(CustomCspQuickAdd.isDuplicate("https://a.com/api.php/provide/vod/", "https://a.com/api.php/provide/vod"));
        assertTrue(CustomCspQuickAdd.isDuplicate("https://A.com/api.php/provide/vod", "https://a.com/api.php/provide/vod"));
        assertFalse(CustomCspQuickAdd.isDuplicate("https://a.com/api.php/provide/vod", "https://b.com/api.php/provide/vod"));
    }

    @Test
    public void duplicateDetectionKeepsMeaningfulQuery() {
        assertFalse(CustomCspQuickAdd.isDuplicate("https://a.com/vod?token=1", "https://a.com/vod?token=2"));
    }

    @Test
    public void hostIsLowercasedAndPortIsDropped() {
        assertEquals("a.com", CustomCspQuickAdd.hostOf("https://A.COM:8443/vod"));
        assertEquals("a.com", CustomCspQuickAdd.hostOf("https://user:pw@a.com/vod"));
        assertEquals("", CustomCspQuickAdd.hostOf("随便一段中文"));
    }

    @Test
    public void defaultNameDropsWwwPrefix() {
        assertEquals("mujiziyuan00", CustomCspQuickAdd.defaultName("https://www.mujiziyuan00.com/api.php/provide/vod"));
        assertEquals("localhost", CustomCspQuickAdd.defaultName("http://localhost:8080/vod"));
        assertEquals("192", CustomCspQuickAdd.defaultName("http://192.168.1.5/vod"));
    }

    @Test
    public void trailingPunctuationIsTrimmed() {
        CustomCspQuickAdd.Parsed parsed = CustomCspQuickAdd.parse("看这里 https://a.com/api.php/provide/vod，");
        assertTrue(parsed.isValid());
        assertEquals("https://a.com/api.php/provide/vod", parsed.getApi());
    }

    @Test
    public void jsonSnippetIsNotTreatedAsAnAddress() {
        // A pasted JSON object has no bare http token before other fields, so the
        // quick-add path must not silently accept it as an address.
        CustomCspQuickAdd.Parsed parsed = CustomCspQuickAdd.parse("{\"name\":\"x\",\"api\":\"\"}");
        assertFalse(parsed.isValid());
    }

    @Test
    public void headerMapPreservesCasingAndOrder() {
        Map<String, String> map = CustomCspQuickAdd.headerMap("User-Agent: UA\nReferer: https://a.com/");
        assertEquals(2, map.size());
        assertEquals("UA", map.get("User-Agent"));
        assertEquals("https://a.com/", map.get("Referer"));
    }

    @Test
    public void headerLineRejectsBareAddresses() {
        assertTrue(CustomCspQuickAdd.isHeaderLine("Referer: https://a.com/"));
        assertTrue(CustomCspQuickAdd.isHeaderLine("User-Agent: Mozilla/5.0"));
        assertFalse(CustomCspQuickAdd.isHeaderLine("https://a.com/api.php/provide/vod"));
        assertFalse(CustomCspQuickAdd.isHeaderLine("随便一段中文"));
    }
}
