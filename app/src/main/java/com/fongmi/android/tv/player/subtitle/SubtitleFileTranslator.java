package com.fongmi.android.tv.player.subtitle;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Translates a whole subtitle document while preserving its structure.
 *
 * <p>Text and timing are handled separately: everything that is not display
 * text (timing rows, indices, ASS sections and header keys, ASS style tags) is
 * copied through verbatim, and only display text is rewritten. Inside a display
 * line the markup is kept as-is and only the plain-text fragments are
 * translated, which is what lets SRT, WebVTT and ASS all survive the round trip
 * without the renderer losing positions or styling.
 */
public final class SubtitleFileTranslator {

    /** Target languages offered by the dialog; the codes are the engine's. */
    public static final String[] TARGET_CODES = {"zh-CN", "zh-TW", "en", "ja", "ko", "es", "fr", "de", "ru"};
    private static final int[] TARGET_NAMES = {
            R.string.subtitle_translate_lang_zh_cn,
            R.string.subtitle_translate_lang_zh_tw,
            R.string.subtitle_translate_lang_en,
            R.string.subtitle_translate_lang_ja,
            R.string.subtitle_translate_lang_ko,
            R.string.subtitle_translate_lang_es,
            R.string.subtitle_translate_lang_fr,
            R.string.subtitle_translate_lang_de,
            R.string.subtitle_translate_lang_ru,
    };

    private static final String ASS_EVENTS = "[events]";
    private static final String ASS_DIALOGUE = "dialogue:";
    private static final String ASS_FORMAT = "format:";

    private SubtitleFileTranslator() {
    }

    public static List<String> languageNames() {
        List<String> names = new ArrayList<>(TARGET_NAMES.length);
        for (int id : TARGET_NAMES) names.add(App.get().getString(id));
        return names;
    }

    /**
     * Rewrites {@code text} into a translated subtitle document.
     *
     * @param bilingual when true each translated line is appended under its
     *                  original, which is the usual request when watching
     *                  foreign-language material; when false the original text
     *                  is replaced.
     */
    public static String translate(String text, String target, boolean bilingual) {
        return rewrite(text, bilingual, requests -> SubtitleTranslator.translate(requests, target));
    }

    /**
     * Structure-preserving rewrite with an injected translator, so the exact
     * output shape can be verified without network access.
     */
    static String rewrite(String text, boolean bilingual, Function<List<String>, List<String>> translator) {
        if (text == null || text.isEmpty()) return text;
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String format = detectFormat(normalized);
        List<Line> lines = splitLines(normalized, format);
        List<String> requests = new ArrayList<>();
        for (Line line : lines) collect(line, requests);
        if (requests.isEmpty()) return text;
        List<String> translations = translator.apply(requests);
        StringBuilder builder = new StringBuilder(text.length() + translations.size() * 8);
        int cursor = 0;
        for (Line line : lines) {
            builder.append(rewrite(line, translations, cursor, bilingual));
            cursor += countSlots(line);
            builder.append('\n');
        }
        // Remove the separator the loop appended after the final line.
        builder.setLength(builder.length() - 1);
        return builder.toString();
    }

    public static String detectFormat(String text) {
        String head = text.length() > 2048 ? text.substring(0, 2048) : text;
        String lower = head.toLowerCase(Locale.ROOT);
        if (lower.contains("[script info]") || lower.contains("[v4+ styles]") || lower.contains(ASS_EVENTS)) return "ass";
        return lower.stripLeading().startsWith("webvtt") ? "vtt" : "srt";
    }

    private static List<Line> splitLines(String text, String format) {
        boolean ass = "ass".equals(format);
        List<Line> lines = new ArrayList<>();
        String[] parts = text.split("\n", -1);
        int fieldCount = 0;
        boolean inEvents = false;
        for (String raw : parts) {
            String trimmed = raw.trim();
            if (ass) {
                if (isSection(trimmed)) {
                    inEvents = ASS_EVENTS.equals(trimmed.toLowerCase(Locale.ROOT));
                    lines.add(Line.verbatim(raw));
                    continue;
                }
                if (!inEvents) {
                    lines.add(Line.verbatim(raw));
                    continue;
                }
                String lower = trimmed.toLowerCase(Locale.ROOT);
                if (lower.startsWith(ASS_FORMAT)) {
                    fieldCount = countFields(trimmed);
                    lines.add(Line.verbatim(raw));
                    continue;
                }
                if (!lower.startsWith(ASS_DIALOGUE)) {
                    lines.add(Line.verbatim(raw));
                    continue;
                }
                String[] fields = splitFields(raw, fieldCount <= 0 ? 10 : fieldCount);
                // A row whose shape does not match the declared format is left
                // untouched rather than rewritten into a corrupt cue.
                lines.add(fields == null ? Line.verbatim(raw) : Line.ass(raw, fields));
                continue;
            }
            boolean textLine = !trimmed.isEmpty() && !isIndex(trimmed) && !trimmed.contains("-->");
            lines.add(textLine ? Line.text(raw) : Line.verbatim(raw));
        }
        return lines;
    }

    private static boolean isSection(String trimmed) {
        return trimmed.startsWith("[") && trimmed.endsWith("]");
    }

    private static boolean isIndex(String trimmed) {
        for (int i = 0; i < trimmed.length(); i++) if (!Character.isDigit(trimmed.charAt(i))) return false;
        return true;
    }

    private static int countFields(String format) {
        int count = 1;
        for (int i = 0; i < format.length(); i++) if (format.charAt(i) == ',') count++;
        return count;
    }

    /**
     * Splits an ASS event row into its prefix, its leading fields and its text.
     * Everything before the last required comma is preserved byte for byte;
     * only the text field travels through translation. Text may itself contain
     * commas, which is why the count comes from the section's Format row.
     */
    static String[] splitFields(String line, int fieldCount) {
        int colon = line.indexOf(':');
        String prefix = colon >= 0 ? line.substring(0, colon + 1) : "";
        String body = colon >= 0 ? line.substring(colon + 1) : line;
        int needed = Math.max(1, fieldCount - 1);
        int position = -1;
        for (int i = 0; i < needed; i++) {
            position = body.indexOf(',', position + 1);
            if (position < 0) return null;
        }
        return new String[]{prefix, body.substring(0, position), body.substring(position + 1)};
    }

    private static void collect(Line line, List<String> requests) {
        if (line.kind == Line.Kind.VERBATIM) return;
        for (SubtitleTextFormat.Part part : SubtitleTextFormat.split(line.text)) {
            if (!part.isTranslatable() || part.text().trim().isEmpty()) continue;
            requests.add(part.text());
        }
    }

    private static int countSlots(Line line) {
        if (line.kind == Line.Kind.VERBATIM) return 0;
        int count = 0;
        for (SubtitleTextFormat.Part part : SubtitleTextFormat.split(line.text)) {
            if (part.isTranslatable() && !part.text().trim().isEmpty()) count++;
        }
        return count;
    }

    private static String rewrite(Line line, List<String> translations, int offset, boolean bilingual) {
        if (line.kind == Line.Kind.VERBATIM) return line.raw;
        StringBuilder builder = new StringBuilder();
        int cursor = offset;
        boolean changed = false;
        for (SubtitleTextFormat.Part part : SubtitleTextFormat.split(line.text)) {
            if (!part.isTranslatable() || part.text().trim().isEmpty()) {
                builder.append(part.text());
                continue;
            }
            String value = cursor < translations.size() ? translations.get(cursor) : null;
            cursor++;
            // A translation identical to the source means the engine did not
            // translate anything; printing it again would duplicate the line.
            if (value == null || value.trim().equals(part.text().trim())) {
                builder.append(part.text());
                continue;
            }
            changed = true;
            if (bilingual) {
                builder.append(part.text());
                // An ASS hard break keeps both languages on separate rows
                // regardless of how the renderer wraps the cue.
                builder.append(line.kind == Line.Kind.ASS ? "\\N" : "\n");
            }
            builder.append(value);
        }
        return changed ? line.rebuild(builder.toString()) : line.raw;
    }

    private static final class Line {

        enum Kind { VERBATIM, TEXT, ASS }

        final Kind kind;
        final String raw;
        final String text;
        final String[] fields;

        private Line(Kind kind, String raw, String text, String[] fields) {
            this.kind = kind;
            this.raw = raw;
            this.text = text;
            this.fields = fields;
        }

        static Line verbatim(String raw) {
            return new Line(Kind.VERBATIM, raw, null, null);
        }

        static Line text(String raw) {
            return new Line(Kind.TEXT, raw, raw, null);
        }

        static Line ass(String raw, String[] fields) {
            return new Line(Kind.ASS, raw, fields[2], fields);
        }

        /** Writes translated text back into this line's original shape. */
        String rebuild(String translated) {
            if (kind == Kind.TEXT) return translated;
            return fields[0] + fields[1] + "," + translated;
        }
    }
}
