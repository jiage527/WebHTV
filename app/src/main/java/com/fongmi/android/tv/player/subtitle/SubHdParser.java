package com.fongmi.android.tv.player.subtitle;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts SubHD search results from the site's server-rendered HTML.
 *
 * <p>The search page is the only route SubHD offers without an account, so the
 * parser works on the markup itself. It is written against the card structure
 * the site serves today, but every rule is tolerant: a card that does not match
 * is skipped rather than allowed to break the whole list, because a markup tweak
 * on the provider side must degrade the result list, not the feature.
 *
 * <p>Only the fields the ranking and the list need are read: the result id (from
 * the {@code /a/<id>} link), the release name, the language markers and the
 * format. Which language a result actually carries is also encoded in SubHD's
 * numeric language id ({@code lan-1/2/4}); that mapping is not guessed here
 * because the visible markers carry the same information and are present in the
 * markup.
 */
public final class SubHdParser {

    /** One result card: the block that carries a title anchor followed by its download link. */
    private static final Pattern CARD = Pattern.compile("<div class=\"bg-white shadow-sm rounded-3 mb-4\">(.*?)</div>\\s*</div>\\s*</div>\\s*</div>", Pattern.DOTALL);
    private static final Pattern ID = Pattern.compile("href=['\"]/a/([A-Za-z0-9]+)['\"]");
    private static final Pattern RELEASE = Pattern.compile("class=['\"]view-text[^>]*>\\s*<a[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern LANGUAGE_WORDS = Pattern.compile("(简体|繁体|繁體|双语|雙語|中文|英语|英文|英語|日语|日语|日文|韩语|韩語|韩文|中英|特效)");
    private static final Pattern FORMAT = Pattern.compile("\\b(ASS|SRT|SSA|VTT|SUP|SUB|TTML)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIZE = Pattern.compile("([0-9.]+)\\s*([KMGkmg]?)\\s*(?:<|$)", Pattern.DOTALL);
    private static final Pattern DOWNLOADS = Pattern.compile("</svg>\\s*<span[^>]*>([0-9,]+)</span>");

    private SubHdParser() {
    }

    /** Result ids in page order, deduplicated because one card may link twice. */
    public static List<String> subtitleIds(String html) {
        Set<String> ids = new LinkedHashSet<>();
        if (html == null || html.isEmpty()) return new ArrayList<>();
        for (String card : cards(html)) {
            Matcher id = ID.matcher(card);
            if (id.find()) ids.add(id.group(1));
        }
        return new ArrayList<>(ids);
    }

    /**
     * Builds the search-result entry for one id. The card the id came from supplies
     * the metadata; a card without a release name is treated as unusable.
     *
     * @param host   the provider's origin, used to build the link the download step
     *               resolves; it is not the same thing as the displayed source name.
     * @param source the provider name shown next to a result.
     */
    public static OnlineSubtitle toItem(String id, String html, String host, String source) {
        if (id == null || id.isEmpty() || html == null) return null;
        String card = cardOf(html, id);
        String release = firstGroup(RELEASE, card);
        if (release.isEmpty()) return null;
        String language = join(LANGUAGE_WORDS, card);
        String format = normalizeFormat(firstGroup(FORMAT, card));
        return OnlineSubtitle.builder()
                .id(id)
                .name(clean(release))
                .language(language)
                .format(format)
                .release(clean(release))
                .source(source)
                .downloadUrl(host + "/a/" + id)
                .downloads(downloads(card))
                .build();
    }

    /** The card markup that contains the given result id. */
    private static String cardOf(String html, String id) {
        for (String card : cards(html)) if (card.contains("/a/" + id)) return card;
        return "";
    }

    private static List<String> cards(String html) {
        List<String> cards = new ArrayList<>();
        Matcher matcher = CARD.matcher(html);
        while (matcher.find()) contracts(cards, matcher.group());
        // The card boundary is heuristic; if it found nothing usable, fall back to
        // splitting on the marker itself so a nested-div change still yields cards.
        if (cards.isEmpty()) {
            // Skip the leading part: it is everything before the first card and can
            // still contain unrelated links.
            String[] parts = html.split("bg-white shadow-sm rounded-3 mb-4");
            for (int i = 1; i < parts.length; i++) if (parts[i].contains("/a/")) cards.add(parts[i]);
        }
        return cards;
    }

    private static void contracts(List<String> cards, String card) {
        if (card != null && card.contains("/a/")) cards.add(card);
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1) : "";
    }

    /** All distinct language markers found in a card, in page order. */
    private static String join(Pattern pattern, String text) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        while (matcher.find()) values.add(matcher.group(1));
        return String.join("/", values);
    }

    private static String normalizeFormat(String value) {
        if (value == null || value.isEmpty()) return "";
        String upper = value.toUpperCase(java.util.Locale.ROOT);
        if (upper.equals("ASS") || upper.equals("SSA")) return "ass";
        if (upper.equals("VTT")) return "vtt";
        if (upper.equals("TTML")) return "ttml";
        return "srt";
    }

    /** Download count, or 0 when the card does not report one. */
    private static long downloads(String card) {
        Matcher matcher = DOWNLOADS.matcher(card == null ? "" : card);
        return matcher.find() ? parseLong(matcher.group(1)) : 0;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.replace(",", ""));
        } catch (Throwable e) {
            return 0;
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        String text = value.replaceAll("<[^>]+>", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
        return text.replaceAll("\\s+", " ").trim();
    }
}
