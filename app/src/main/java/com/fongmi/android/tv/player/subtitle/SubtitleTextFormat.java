package com.fongmi.android.tv.player.subtitle;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits one subtitle display line into translatable text and verbatim markup.
 *
 * <p>Markup never leaves the app: only the plain-text fragments are sent for
 * translation and are stitched back in their original order, so ASS override
 * blocks, HTML tags and ASS line breaks survive translation byte for byte.
 */
public final class SubtitleTextFormat {

    private SubtitleTextFormat() {
    }

    /** One fragment of a display line; markup fragments are never translated. */
    public static final class Part {

        private final String text;
        private final boolean translatable;

        Part(String text, boolean translatable) {
            this.text = text;
            this.translatable = translatable;
        }

        public String text() {
            return text;
        }

        public boolean isTranslatable() {
            return translatable;
        }
    }

    public static List<Part> split(String line) {
        List<Part> parts = new ArrayList<>();
        int index = 0;
        int length = line.length();
        while (index < length) {
            char current = line.charAt(index);
            if (current == '{') {
                int end = line.indexOf('}', index + 1);
                int next = end < 0 ? length : end + 1;
                parts.add(new Part(line.substring(index, next), false));
                index = next;
                continue;
            }
            if (current == '<') {
                int end = line.indexOf('>', index + 1);
                int next = end < 0 ? length : end + 1;
                parts.add(new Part(line.substring(index, next), false));
                index = next;
                continue;
            }
            if (current == '\\' && index + 1 < length && isBreak(line.charAt(index + 1))) {
                parts.add(new Part(line.substring(index, index + 2), false));
                index += 2;
                continue;
            }
            // A single newline character also breaks a display line for MPV's ASS path.
            if (current == '\n' || current == '\r') {
                parts.add(new Part(String.valueOf(current), false));
                index++;
                continue;
            }
            int end = index;
            while (end < length && !startsMarkup(line, end)) end++;
            addText(parts, line.substring(index, end));
            index = end;
        }
        return parts;
    }

    public static boolean hasTranslatableText(String line) {
        for (Part part : split(line)) if (part.isTranslatable() && !part.text().trim().isEmpty()) return true;
        return false;
    }

    /** True when the line contains markup, so rendering must not be re-flowed. */
    public static boolean hasMarkup(String line) {
        for (Part part : split(line)) if (!part.isTranslatable()) return true;
        return false;
    }

    public static String render(List<Part> parts, List<String> translations) {
        StringBuilder builder = new StringBuilder();
        int index = 0;
        for (Part part : parts) {
            if (part.isTranslatable()) {
                String value = index < translations.size() ? translations.get(index) : null;
                builder.append(value == null ? part.text() : value);
                index++;
            } else {
                builder.append(part.text());
            }
        }
        return builder.toString();
    }

    private static boolean startsMarkup(String line, int index) {
        char current = line.charAt(index);
        if (current == '{' || current == '<' || current == '\n' || current == '\r') return true;
        return current == '\\' && index + 1 < line.length() && isBreak(line.charAt(index + 1));
    }

    private static boolean isBreak(char value) {
        return value == 'N' || value == 'n' || value == 'h';
    }

    private static void addText(List<Part> parts, String text) {
        if (text.isEmpty()) return;
        // Leading and trailing spaces stay verbatim so a translated line keeps the
        // exact indentation and separation of the original cue.
        int start = 0;
        int end = text.length();
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        if (start > 0) parts.add(new Part(text.substring(0, start), false));
        // Whitespace-only runs are never translated: the request would be wasted
        // and some engines answer them with a visible character.
        if (start == end) return;
        parts.add(new Part(text.substring(start, end), true));
        if (end < text.length()) parts.add(new Part(text.substring(end), false));
    }
}
