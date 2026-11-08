package com.fongmi.android.tv.player.subtitle;

import org.junit.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SubtitleTextFormatTest {

    @Test
    public void assOverrideBlocksAreNeverTranslated() {
        List<SubtitleTextFormat.Part> parts = SubtitleTextFormat.split("{\\an8}{\\c&HFFFFFF&}Hello");
        StringBuilder translatable = new StringBuilder();
        for (SubtitleTextFormat.Part part : parts) if (part.isTranslatable()) translatable.append(part.text());
        assertEquals("Hello", translatable.toString());
    }

    @Test
    public void htmlTagsAreNeverTranslated() {
        List<SubtitleTextFormat.Part> parts = SubtitleTextFormat.split("<i>Hello</i> <font color=\"#fff\">night</font>");
        List<String> translatable = new java.util.ArrayList<>();
        for (SubtitleTextFormat.Part part : parts) if (part.isTranslatable()) translatable.add(part.text());
        assertEquals(java.util.Arrays.asList("Hello", "night"), translatable);
        assertEquals("<i>HELLO</i> <font color=\"#fff\">NIGHT</font>",
                SubtitleTextFormat.render(parts, java.util.Arrays.asList("HELLO", "NIGHT")));
    }

    @Test
    public void assHardBreakIsNotTranslated() {
        assertFalse(SubtitleTextFormat.hasTranslatableText("\\N"));
        List<SubtitleTextFormat.Part> parts = SubtitleTextFormat.split("Hello\\Nnight");
        assertEquals(2, parts.stream().filter(SubtitleTextFormat.Part::isTranslatable).count());
    }

    @Test
    public void renderStitchesMarkupBackInPlace() {
        List<SubtitleTextFormat.Part> parts = SubtitleTextFormat.split("{\\an8}Hello world");
        String rendered = SubtitleTextFormat.render(parts, List.of("你好世界"));
        assertEquals("{\\an8}你好世界", rendered);
    }

    @Test
    public void surroundingWhitespaceIsPreservedButNotTranslated() {
        List<SubtitleTextFormat.Part> parts = SubtitleTextFormat.split("  Hello  ");
        List<String> translatable = new java.util.ArrayList<>();
        for (SubtitleTextFormat.Part part : parts) if (part.isTranslatable()) translatable.add(part.text());
        assertEquals(List.of("Hello"), translatable);
        assertEquals("  你好  ", SubtitleTextFormat.render(parts, List.of("你好")));
    }

    @Test
    public void blankCueLineHasNothingToTranslate() {
        assertFalse(SubtitleTextFormat.hasTranslatableText("   "));
        assertFalse(SubtitleTextFormat.hasTranslatableText(""));
        assertFalse(SubtitleTextFormat.hasTranslatableText("{\\an8}"));
    }

    @Test
    public void internalSpacingStaysInsideOneTranslatedSentence() {
        List<SubtitleTextFormat.Part> parts = SubtitleTextFormat.split("Hello   world");
        List<String> translatable = new java.util.ArrayList<>();
        for (SubtitleTextFormat.Part part : parts) if (part.isTranslatable()) translatable.add(part.text());
        assertEquals(List.of("Hello   world"), translatable);
    }

    @Test
    public void unclosedOverrideDoesNotSwallowTheLine() {
        List<SubtitleTextFormat.Part> parts = SubtitleTextFormat.split("Hello {\\an8");
        String rendered = SubtitleTextFormat.render(parts, List.of("你好"));
        assertEquals("你好 {\\an8", rendered);
    }

    @Test
    public void rewriteKeepsEveryTimingRowIdentical() {
        String srt = "1\n00:00:01,000 --> 00:00:03,000\nHello world\n\n2\n00:00:04,000 --> 00:00:06,000\nGood night\n";
        String result = SubtitleFileTranslator.rewrite(srt, false, translate("T:"));
        assertTrue(result.contains("00:00:01,000 --> 00:00:03,000"));
        assertTrue(result.contains("00:00:04,000 --> 00:00:06,000"));
        assertTrue(result.contains("T:Hello world"));
        assertTrue(result.contains("T:Good night"));
        assertFalse(result.contains("T:1"));
    }

    @Test
    public void rewriteKeepsAssHeaderAndStyleRowsByteIdentical() {
        String ass = "[Script Info]\nTitle: demo\n\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize\nStyle: Default,Arial,20\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\\an8}Hello world\n";
        String result = SubtitleFileTranslator.rewrite(ass, false, translate("T:"));
        assertTrue(result.contains("Title: demo"));
        assertTrue(result.contains("Style: Default,Arial,20"));
        assertTrue(result.contains("Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\\an8}T:Hello world"));
    }

    @Test
    public void rewriteKeepsAssTextCommas() {
        String ass = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,a, b, c\n";
        String result = SubtitleFileTranslator.rewrite(ass, false, translate("T:"));
        assertTrue(result.endsWith("Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,T:a, b, c\n"));
    }

    @Test
    public void bilingualKeepsBothLanguagesOnSeparateRows() {
        String srt = "1\n00:00:01,000 --> 00:00:03,000\nHello\n";
        String result = SubtitleFileTranslator.rewrite(srt, true, translate("T:"));
        assertTrue(result.contains("Hello\nT:Hello"));
    }

    @Test
    public void bilingualUsesAssHardBreakInsideAss() {
        String ass = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello\n";
        String result = SubtitleFileTranslator.rewrite(ass, true, translate("T:"));
        assertTrue(result.contains(",,Hello\\NT:Hello\n"));
    }

    @Test
    public void untranslatedLineIsLeftByteIdentical() {
        String srt = "1\n00:00:01,000 --> 00:00:03,000\nHello\n";
        String result = SubtitleFileTranslator.rewrite(srt, false, Function.identity());
        assertEquals(srt, result);
    }

    @Test
    public void detectsFormatFromContent() {
        assertEquals("ass", SubtitleFileTranslator.detectFormat("[Script Info]\nTitle: x"));
        assertEquals("vtt", SubtitleFileTranslator.detectFormat("WEBVTT\n\n00:00.000 --> 00:02.000\nhi"));
        assertEquals("srt", SubtitleFileTranslator.detectFormat("1\n00:00:01,000 --> 00:00:03,000\nhi"));
    }

    @Test
    public void utf8IsDetectedAndGbkIsNot() {
        assertTrue(SubtitleSource.isValidUtf8("你好世界".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertFalse(SubtitleSource.isValidUtf8("你好世界".getBytes(java.nio.charset.Charset.forName("GB18030"))));
    }

    @Test
    public void gbkSubtitleDecodesBackToItsOriginalText() {
        String original = "1\n00:00:01,000 --> 00:00:03,000\n你好，世界\n";
        byte[] gbk = original.getBytes(java.nio.charset.Charset.forName("GB18030"));
        assertEquals(original, SubtitleSource.decode(gbk));
    }

    @Test
    public void utf8BomIsStripped() {
        byte[] data = "\uFEFFHello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("Hello", SubtitleSource.decode(data));
    }

    @Test
    public void replySegmentsAreJoinedAndSplitBackPerLine() {
        // The real reply shape: one segment per source line, each echoing its newline.
        String json = "[[[\"你好世界\\n\",\"Hello world\\n\",null,null,3],"
                + "[\"晚安\",\"Good night\",null,null,3]],null,\"en\",null,null,null,1,[],[ [\"en\"],null,[1],[\"en\"]]]";
        List<String> values = SubtitleTranslator.parse(json);
        assertEquals(java.util.Arrays.asList("你好世界", "晚安"), values);
    }

    @Test
    public void replyKeepsLinesApartWhenTheEngineEchoesTheSeparator() {
        // Two lines merged into one segment still carry the separator, so they
        // still map back onto two cues instead of one.
        String json = "[[[\"你好世界\\n晚安\",\"Hello world\\nGood night\",null,null,3]]]";
        List<String> values = SubtitleTranslator.parse(json);
        assertEquals(java.util.Arrays.asList("你好世界", "晚安"), values);
    }

    @Test
    public void replyThatDropsTheSeparatorYieldsTooFewLines() {
        // Merging without the separator loses a line; the caller compares this
        // count with the request size and retries line by line instead of
        // shifting every later cue onto the wrong text.
        String json = "[[[\"你好世界晚安\",\"Hello world Good night\",null,null,3]]]";
        List<String> values = SubtitleTranslator.parse(json);
        assertEquals(1, values.size());
    }

    @Test
    public void malformedReplyIsRejectedRatherThanGuessed() {
        assertEquals(null, SubtitleTranslator.parse(""));
        assertEquals(null, SubtitleTranslator.parse("not json"));
        assertEquals(null, SubtitleTranslator.parse("{\"error\":true}"));
    }

    /** A translator that marks every request so output shape is easy to assert. */
    private static Function<List<String>, List<String>> translate(String prefix) {
        return requests -> {
            java.util.List<String> result = new java.util.ArrayList<>(requests.size());
            for (String request : requests) result.add(prefix + request);
            return result;
        };
    }
}
