package com.fongmi.android.tv.player.subtitle;

import com.github.catvod.utils.Trans;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matching rules for online subtitles: what to search for, which provider
 * language markers a chosen language means, and how well a result fits what is
 * playing.
 *
 * <p>Everything here is pure Java so the ranking can be pinned by unit tests
 * without a network. The text normalisation is the same one the lyrics matcher
 * uses, which already removes release noise (codec tags, container suffixes,
 * bracketed annotations) and simplifies Chinese variants; reusing it keeps one
 * definition of "the same title" across the app.
 *
 * <p>Ranking never lets popularity outvote the title: a result whose release
 * name does not relate to the playing item is rejected outright rather than
 * ranked low, because a confident wrong subtitle is worse than no subtitle.
 */
public final class SubtitleMatchPolicy {

    /** Language profiles offered in the search dialog, in display order. */
    private static final String[] PROFILES = {"zh-CN", "zh-TW", "en", "ja", "ko"};

    /** OpenSubtitles language ids per profile. More than one means "any of these". */
    private static final Map<String, String[]> OPEN_SUBTITLES_IDS = Map.of(
            "zh-CN", new String[]{"chi", "zhe"},
            "zh-TW", new String[]{"zht", "zhe"},
            "en", new String[]{"eng"},
            "ja", new String[]{"jpn"},
            "ko", new String[]{"kor"});

    /** SubHD writes language names in Chinese; these are the markers it uses. */
    private static final Map<String, String[]> SUBHD_KEYWORDS = Map.of(
            "zh-CN", new String[]{"简体", "双语", "中文"},
            "zh-TW", new String[]{"繁体", "雙語", "双语", "中文"},
            "en", new String[]{"英语", "英文", "英語"},
            "ja", new String[]{"日语", "日語", "日文"},
            "ko", new String[]{"韩语", "韓語", "韩文"});

    /** Display names for provider language markers, both OpenSubtitles ids and Chinese labels. */
    private static final Map<String, String> LANGUAGE_LABELS = buildLanguageLabels();

    private static final Pattern EPISODE_DIGITS = Pattern.compile("(\\d{1,4})");
    private static final Pattern SEASON_EPISODE = Pattern.compile("(?i)S(\\d{1,2})E(\\d{1,3})");
    private static final Pattern YEAR_SUFFIX = Pattern.compile("[（(]?\\s*(19|20)\\d{2}\\s*[)）]?$");
    /**
     * Tokens a release name carries about the encode rather than the content.
     * They are removed before comparison so that an exact title is not scored as
     * a mere partial match just because the release name is long.
     */
    private static final Pattern RELEASE_NOISE = Pattern.compile(
            "(?i)\\b(?:\\d{3,4}p|2160p?|1080p?|720p?|576p?|480p?|4k|8k|uhd|hdr10\\+?|hdr|sdr|dolby ?vision|dv|"
                    + "bluray|blu-?ray|bdrip|brrip|bdremux|remux|webrip|web-?dl|web|hdtv|hdrip|dvdrip|dvd|"
                    + "x264|x265|h\\.?264|h\\.?265|hevc|avc|xvid|divx|10bit|8bit|aac|ac3|eac3|dts(?:-hd)?|truehd|"
                    + "atmos|ddp?5\\.?1|flac|mp3|opus|"
                    + "proper|repack|internal|extended|uncut|remastered|imax|complete|multi|dual ?audio|"
                    + "chs|cht|chinese|english|eng|jpn|kor|gb|big5|utf-?8|hardsub|softsub|sub|subs|"
                    + "(?:19|20)\\d{2})\\b");

    private static final Pattern ANNOTATION = Pattern.compile("\\([^)]*\\)|\\[[^]]*]|（[^）]*）|【[^】]*】");
    private static final Pattern DISPLAY_NOISE = Pattern.compile("[《》【】\\[\\]()（）·、,，。!！?？\"'“”‘’:：;；|\\\\/]+");

    /** Weights of the individual sub-scores; they are renormalised over what is known. */
    private static final int WEIGHT_TITLE = 45;
    private static final int WEIGHT_EPISODE = 15;
    private static final int WEIGHT_DURATION = 20;
    private static final int WEIGHT_POPULARITY = 12;
    private static final int WEIGHT_RATING = 8;

    /** Download count that counts as "fully popular" for the popularity component. */
    private static final double POPULARITY_CEILING = 100000;

    private SubtitleMatchPolicy() {
    }

    /** What is currently playing, as far as subtitle search cares. */
    public record MediaQuery(String title, String episode, boolean series, double durationSec) {

        public boolean hasTitle() {
            return !clean(title).isEmpty();
        }

        public boolean hasDuration() {
            return durationSec > 0;
        }

        /**
         * The episode number, read from an explicit {@code SxxExx} marker when there
         * is one. Falling back to "the first number in the text" would return the
         * season for every ordinary episode label, which then flows into the query
         * and into the episode comparison.
         */
        public String episodeDigits() {
            return digitsOf(episode);
        }
    }

    public static List<String> profiles() {
        return List.of(PROFILES);
    }

    /** True when the marker is one this app offers. */
    public static boolean isKnownProfile(String profile) {
        return profile != null && OPEN_SUBTITLES_IDS.containsKey(profile);
    }

    /** OpenSubtitles language ids for the chosen profiles, deduplicated and ordered. */
    public static List<String> openSubtitlesIds(List<String> profiles) {
        List<String> ids = new ArrayList<>();
        for (String profile : safeProfiles(profiles)) ids.addAll(List.of(OPEN_SUBTITLES_IDS.get(profile)));
        return new ArrayList<>(new LinkedHashSet<>(ids));
    }

    /** Chinese language markers SubHD publishes for the chosen profiles, deduplicated. */
    public static List<String> subHdKeywords(List<String> profiles) {
        List<String> keywords = new ArrayList<>();
        for (String profile : safeProfiles(profiles)) keywords.addAll(List.of(SUBHD_KEYWORDS.get(profile)));
        return new ArrayList<>(new LinkedHashSet<>(keywords));
    }

    /** A human label for a provider language marker, falling back to the marker itself. */
    public static String languageLabel(String marker) {
        String value = marker == null ? "" : marker.trim();
        if (value.isEmpty()) return "";
        String label = LANGUAGE_LABELS.get(value.toLowerCase(Locale.ROOT));
        return label == null ? value : label;
    }

    /**
     * The text handed to a provider's search endpoint.
     *
     * <p>Providers full-text match, so the year, the season marker and bracketed
     * annotations are dropped: keeping them makes the query far less likely to hit.
     * For a series the episode number is appended because one title usually has
     * subtitles for many episodes.
     */
    public static String buildQuery(MediaQuery query) {
        String title = clean(query == null ? "" : query.title());
        title = ANNOTATION.matcher(title).replaceAll(" ");
        title = DISPLAY_NOISE.matcher(title).replaceAll(" ").trim();
        title = YEAR_SUFFIX.matcher(title).replaceAll("").trim();
        if (title.isEmpty()) return "";
        if (query.series()) {
            String episode = query.episodeDigits();
            if (!episode.isEmpty()) return title + " " + episode;
        }
        return title;
    }

    /**
     * True when a provider result plausibly belongs to what is playing.
     *
     * <p>A result whose release name names a different episode of the same series
     * is rejected rather than ranked low: the search already asked for one
     * episode, so offering its neighbours only invites picking the wrong one. A
     * release that names no episode at all stays, because plenty of providers
     * publish "Show.BluRay.1080p" for a single episode.
     */
    public static boolean isAcceptable(MediaQuery query, OnlineSubtitle item) {
        if (query == null || item == null || !item.isUsable()) return false;
        if (!query.hasTitle()) return false;
        String release = bestRelease(item);
        if (query.series() && !query.episodeDigits().isEmpty()) {
            Matcher marker = SEASON_EPISODE.matcher(release);
            if (marker.find() && !normalizeNumber(marker.group(2)).equals(normalizeNumber(query.episodeDigits()))) return false;
        }
        return titleFit(query.title(), release, item.getName()) >= 30;
    }

    /**
     * A 0-100 fit score. Components that the provider did not report are dropped
     * and the remaining weights are renormalised, so an unknown duration does not
     * silently become a penalty.
     */
    public static int score(MediaQuery query, OnlineSubtitle item) {
        if (query == null || item == null) return 0;
        String actual = bestRelease(item);
        int total = 0;
        int weight = 0;

        total += WEIGHT_TITLE * titleFit(query.title(), actual, item.getName());
        weight += WEIGHT_TITLE;

        if (query.series() && !query.episodeDigits().isEmpty()) {
            total += WEIGHT_EPISODE * episodeFit(query.episodeDigits(), actual);
            weight += WEIGHT_EPISODE;
        }
        if (query.hasDuration() && item.getMatchedSeconds() > 0) {
            total += WEIGHT_DURATION * durationFit(query.durationSec(), item.getMatchedSeconds());
            weight += WEIGHT_DURATION;
        }
        total += WEIGHT_POPULARITY * popularityFit(item.getDownloads());
        weight += WEIGHT_POPULARITY;
        if (item.getRating() > 0) {
            total += WEIGHT_RATING * ratingFit(item.getRating());
            weight += WEIGHT_RATING;
        }

        int score = weight == 0 ? 0 : total / weight;
        if (item.isHd()) score += 2;
        return Math.max(0, Math.min(100, score));
    }

    /**
     * The result list as the UI should show it: unusable and unrelated items
     * dropped, best match first, duplicates by download link removed.
     */
    public static List<OnlineSubtitle> rank(MediaQuery query, List<OnlineSubtitle> items, int limit) {
        List<OnlineSubtitle> result = new ArrayList<>();
        if (items == null || items.isEmpty()) return result;
        Map<String, Scored> unique = new LinkedHashMap<>();
        for (OnlineSubtitle item : items) {
            if (!isAcceptable(query, item)) continue;
            String key = item.getDownloadUrl();
            Scored scored = new Scored(item, score(query, item), isTitleVerified(query, item));
            Scored existing = unique.get(key);
            if (existing == null || scored.score > existing.score) unique.put(key, scored);
        }
        List<Scored> scored = new ArrayList<>(unique.values());
        // A result whose title could actually be compared with the playing item
        // always outranks one that could not, whatever the popularity gap: for a
        // cross-script pair popularity is the only remaining signal, and it is a
        // much weaker one than "the titles are the same".
        scored.sort(Comparator.comparing((Scored value) -> value.verified).reversed()
                .thenComparing(Comparator.comparingInt((Scored value) -> value.score).reversed())
                .thenComparing(Comparator.comparingLong((Scored value) -> value.item.getDownloads()).reversed())
                .thenComparing(value -> value.item.getName()));
        for (Scored value : scored) {
            result.add(value.item);
            if (limit > 0 && result.size() >= limit) break;
        }
        return result;
    }

    // --- fit components, each 0-100 -------------------------------------------------

    /** How well a release name matches the wanted title. */
    static int titleFit(String wanted, String release, String fallbackName) {
        int primary = relatedScore(wanted, release);
        if (primary >= 30) return primary;
        // Some providers only echo the title in the file name, so fall back to it,
        // discounted because a file name carries less identifying information.
        int secondary = relatedScore(wanted, stripExtension(fallbackName));
        return secondary >= 30 ? Math.max(primary, secondary - 10) : Math.max(primary, secondary);
    }

    /**
     * Episode agreement; a release naming a different episode is actively
     * penalised.
     *
     * <p>Only an explicit {@code SxxExx} marker is trusted. A bare number in a
     * release name is as likely to be a resolution or a year as an episode, and
     * rejecting a good subtitle because "1080" differs from "2" would be worse
     * than saying nothing: without a marker the release is treated as unconfirmed.
     */
    static int episodeFit(String wantedEpisode, String release) {
        Matcher marker = SEASON_EPISODE.matcher(release == null ? "" : release);
        if (!marker.find()) return 50;
        return normalizeNumber(marker.group(2)).equals(normalizeNumber(wantedEpisode)) ? 100 : 0;
    }

    static int durationFit(double wantedSec, double actualSec) {
        double delta = Math.abs(wantedSec - actualSec);
        if (delta <= 2) return 100;
        if (delta <= 5) return 85;
        if (delta <= 10) return 65;
        if (delta <= 20) return 35;
        if (delta <= 40) return 10;
        return 0;
    }

    /**
     * Download counts span six orders of magnitude, so they are compared on a log
     * scale: the difference between 10 and 100 downloads matters, the difference
     * between 500 000 and 600 000 does not. The arithmetic is done in {@code double}
     * because {@code downloads + 1} overflows for a value near the limit.
     */
    static int popularityFit(long downloads) {
        if (downloads <= 0) return 0;
        double scaled = Math.log10((double) downloads) / Math.log10(POPULARITY_CEILING);
        if (Double.isNaN(scaled) || scaled <= 0) return 0;
        return (int) Math.round(Math.min(1, scaled) * 100);
    }

    static int ratingFit(double rating) {
        return (int) Math.round(Math.max(0, Math.min(10, rating)) * 10);
    }

    // --- helpers -------------------------------------------------------------------

    /**
     * Folds a title down to what identifies it: case, width, file extension,
     * bracketed annotations, punctuation, spacing and Chinese variant all removed.
     *
     * <p>The rules mirror the ones the lyrics matcher already applies to titles,
     * so "the same title" means the same thing across the app. They are repeated
     * here rather than called through, because the lyrics version reaches for
     * Android text APIs and must stay a pure-JVM rule for this package's tests.
     */
    static String normalizeTitle(String text) {
        String value = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return "";
        value = Normalizer.normalize(value, Normalizer.Form.NFKC);
        value = value.replaceAll("\\.[a-z0-9]{2,5}$", " ");
        value = value.replaceAll("\\([^)]*\\)|\\[[^]]*]|（[^）]*）|【[^】]*】", " ");
        value = RELEASE_NOISE.matcher(value).replaceAll(" ");
        value = Trans.t2s(false, value);
        value = value.replaceAll("[\\s_\\-.,:;!?/\\\\|+~`'\"#@$%^&*=<>，。！？、·：；“”‘’《》〈〉]+", "");
        return value.trim();
    }

    /** Score meaning "the two titles cannot be compared", see {@link #relatedScore}. */
    private static final int NEUTRAL = 50;

    /**
     * Similarity of two titles after the shared normalisation.
     *
     * <p>When the wanted title and the provider's release name are written in
     * different scripts, comparing them character by character says nothing
     * useful: a Chinese query very often matches an English release name for the
     * same film, and rejecting those would empty the result list. Such a pair is
     * reported as {@link #NEUTRAL} so the decision falls to the signals that are
     * comparable (episode, duration, popularity) instead of to a failed string
     * comparison.
     */
    private static int relatedScore(String wanted, String actual) {
        String a = normalizeTitle(wanted);
        String b = normalizeTitle(actual);
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (isCjk(a) != isCjk(b)) return NEUTRAL;
        if (a.equals(b)) return 100;
        if (!a.contains(b) && !b.contains(a)) return 0;
        int shorter = Math.min(a.codePointCount(0, a.length()), b.codePointCount(0, b.length()));
        int longer = Math.max(a.codePointCount(0, a.length()), b.codePointCount(0, b.length()));
        if (shorter <= 1 || longer <= 0) return 0;
        float ratio = shorter / (float) longer;
        if (ratio >= 0.75f) return 80;
        if (ratio >= 0.55f) return 55;
        return 40;
    }

    /** True when the text is mostly CJK, i.e. the two scripts could not be compared. */
    private static boolean isCjk(String text) {
        if (text == null || text.isEmpty()) return false;
        int cjk = 0;
        int total = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) continue;
            total++;
            if (Character.isIdeographic(codePoint) || (codePoint >= 0x3040 && codePoint <= 0x30FF) || (codePoint >= 0xAC00 && codePoint <= 0xD7AF)) cjk++;
        }
        return total > 0 && cjk * 2 >= total;
    }

    private static String bestRelease(OnlineSubtitle item) {
        String release = item.getRelease();
        if (!release.isEmpty()) return release;
        return stripExtension(item.getName());
    }

    private static String stripExtension(String name) {
        return name == null ? "" : name.replaceAll("(?i)\\.[a-z0-9]{2,5}$", "");
    }

    /** Episode number of a label: the {@code SxxExx} episode, else the first number. */
    static String digitsOf(String value) {
        String text = value == null ? "" : value;
        Matcher marker = SEASON_EPISODE.matcher(text);
        if (marker.find()) return marker.group(2);
        Matcher number = EPISODE_DIGITS.matcher(text);
        return number.find() ? number.group(1) : "";
    }

    /** "01" and "1" must compare equal, "10" and "01" must not. */
    private static String normalizeNumber(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            return Integer.toString(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private static List<String> safeProfiles(List<String> profiles) {
        List<String> result = new ArrayList<>();
        if (profiles == null) return result;
        for (String profile : profiles) if (isKnownProfile(profile) && !result.contains(profile)) result.add(profile);
        return result;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static Map<String, String> buildLanguageLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("chi", "简体中文");
        labels.put("zht", "繁體中文");
        labels.put("zhe", "双语");
        labels.put("eng", "英语");
        labels.put("jpn", "日语");
        labels.put("kor", "韩语");
        labels.put("spa", "西班牙语");
        labels.put("fre", "法语");
        labels.put("fra", "法语");
        labels.put("ger", "德语");
        labels.put("deu", "德语");
        labels.put("rus", "俄语");
        labels.put("por", "葡萄牙语");
        labels.put("ita", "意大利语");
        labels.put("tha", "泰语");
        labels.put("vie", "越南语");
        labels.put("ara", "阿拉伯语");
        labels.put("swe", "瑞典语");
        labels.put("dan", "丹麦语");
        labels.put("nor", "挪威语");
        labels.put("fin", "芬兰语");
        labels.put("pol", "波兰语");
        labels.put("tur", "土耳其语");
        labels.put("ind", "印尼语");
        labels.put("may", "马来语");
        labels.put("dut", "荷兰语");
        // SubHD publishes Chinese markers rather than ids.
        labels.put("简体", "简体中文");
        labels.put("繁体", "繁體中文");
        labels.put("繁體", "繁體中文");
        labels.put("双语", "双语");
        labels.put("雙語", "双语");
        labels.put("中文", "中文");
        labels.put("英语", "英语");
        labels.put("英語", "英语");
        labels.put("英文", "英语");
        labels.put("日语", "日语");
        labels.put("日語", "日语");
        labels.put("日文", "日语");
        labels.put("韩语", "韩语");
        labels.put("韓語", "韩语");
        labels.put("韩文", "韩语");
        return Map.copyOf(labels);
    }

    /** True when the wanted title and the release name could be compared directly. */
    private static boolean isTitleVerified(MediaQuery query, OnlineSubtitle item) {
        String release = bestRelease(item);
        return isCjk(normalizeTitle(query.title())) == isCjk(normalizeTitle(release));
    }

    private record Scored(OnlineSubtitle item, int score, boolean verified) {
    }
}
