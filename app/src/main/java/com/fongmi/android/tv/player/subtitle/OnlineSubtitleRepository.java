package com.fongmi.android.tv.player.subtitle;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Searches online subtitle providers and stores the chosen document where the
 * player can read it.
 *
 * <p>Two providers are supported and both were chosen for the same reason: they
 * answer without an account or an API key, which a viewer cannot be asked to
 * obtain. OpenSubtitles is queried through its long-standing read-only REST
 * search and hands out a stable gzip-compressed document; SubHD is queried
 * through its public search page and two-step download endpoint, which requires
 * the page's own session cookie so the four calls are made on one client with a
 * cookie jar and are attempted only in the browser-like order SubHD expects.
 *
 * <p>SubHD hides its results behind a session, so it is treated as a best-effort
 * extra source: any failure there leaves the OpenSubtitles results intact and is
 * only logged. Searches never write to player state; the caller decides whether
 * to apply a result.
 */
public final class OnlineSubtitleRepository {

    private static final String TAG = "subtitle";
    private static final String OPEN_SUBTITLES = "OpenSubtitles";
    private static final String SUBHD = "SubHD";
    private static final String OPEN_SUBTITLES_SEARCH = "https://rest.opensubtitles.org/search";
    private static final String SUBHD_HOST = "https://subhd.cc";
    private static final String USER_AGENT = "WebHTV/5.6 Android";
    private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final int MAX_ITEMS = 40;

    private static final OkHttpClient CLIENT = OkHttp.client()
            .newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    /** Own client with a cookie jar: SubHD's download handshake depends on session cookies. */
    private static final OkHttpClient SUBHD_CLIENT = CLIENT.newBuilder()
            .cookieJar(new SubtitleCookieJar())
            .build();

    private OnlineSubtitleRepository() {
    }

    /**
     * Searches every provider for {@code query} and returns the results ranked by
     * fit. Providers run sequentially because a search is user-initiated and its
     * result list is small; running them in parallel would buy little while making
     * failures harder to attribute.
     */
    public static List<OnlineSubtitle> search(SubtitleMatchPolicy.MediaQuery query, List<String> profiles) {
        List<OnlineSubtitle> items = new ArrayList<>();
        String keyword = SubtitleMatchPolicy.buildQuery(query);
        if (keyword.isEmpty()) return items;
        List<String> openSubtitlesIds = SubtitleMatchPolicy.openSubtitlesIds(profiles);
        if (!openSubtitlesIds.isEmpty()) items.addAll(openSubtitles(query, keyword, openSubtitlesIds));
        // SubHD is queried with the plain title because its search is release-name oriented
        // and it has no per-language filter that a request could carry.
        items.addAll(subHd(query, SubtitleMatchPolicy.buildQuery(new SubtitleMatchPolicy.MediaQuery(query.title(), null, false, 0))));
        List<OnlineSubtitle> ranked = SubtitleMatchPolicy.rank(query, items, MAX_ITEMS);
        if (SpiderDebug.isEnabled()) SpiderDebug.log(TAG, "online search keyword=%s profiles=%s found=%d kept=%d", keyword, profiles, items.size(), ranked.size());
        return ranked;
    }

    // --- OpenSubtitles --------------------------------------------------------------

    /**
     * One request per language id: the endpoint rejects a comma-separated list
     * with "multiple values per parameter are not supported".
     */
    private static List<OnlineSubtitle> openSubtitles(SubtitleMatchPolicy.MediaQuery query, String keyword, List<String> ids) {
        List<OnlineSubtitle> items = new ArrayList<>();
        for (String id : ids) items.addAll(openSubtitles(query, keyword, id));
        return items;
    }

    private static List<OnlineSubtitle> openSubtitles(SubtitleMatchPolicy.MediaQuery query, String keyword, String languageId) {
        List<OnlineSubtitle> items = new ArrayList<>();
        StringBuilder url = new StringBuilder(OPEN_SUBTITLES_SEARCH);
        url.append("/query-").append(encode(keyword));
        url.append("/sublanguageid-").append(encode(languageId));
        if (query.series()) {
            String season = seasonOf(query.episode());
            if (!season.isEmpty()) url.append("/season-").append(season);
            String episode = query.episodeDigits();
            if (!episode.isEmpty()) url.append("/episode-").append(episode);
        }
        try {
            String body = get(url.toString(), OPEN_SUBTITLES);
            if (body.isEmpty()) return items;
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonArray()) return items;
            for (JsonElement element : parsed.getAsJsonArray()) {
                OnlineSubtitle item = openSubtitlesItem(element);
                if (item != null) items.add(item);
            }
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "online search OpenSubtitles failed language=%s error=%s", languageId, e.getMessage());
        }
        return items;
    }

    private static OnlineSubtitle openSubtitlesItem(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject object = element.getAsJsonObject();
        String link = string(object, "SubDownloadLink");
        if (link.isEmpty()) link = string(object, "ZipDownloadLink");
        if (link.isEmpty()) return null;
        return OnlineSubtitle.builder()
                .name(string(object, "SubFileName"))
                .language(string(object, "SubLanguageID"))
                .format(string(object, "SubFormat"))
                .release(string(object, "MovieReleaseName"))
                .source(OPEN_SUBTITLES)
                .downloadUrl(link)
                .downloads(number(object, "SubDownloadsCnt"))
                .rating(decimal(object, "SubRating"))
                .matchedSeconds(0)
                // OpenSubtitles always serves .gz regardless of the reported extension.
                .gzip(true)
                .hd(number(object, "SubHD") > 0)
                .build();
    }

    // --- SubHD ----------------------------------------------------------------------

    private static List<OnlineSubtitle> subHd(SubtitleMatchPolicy.MediaQuery query, String keyword) {
        List<OnlineSubtitle> items = new ArrayList<>();
        if (keyword.isEmpty()) return items;
        try {
            String body = get(SUBHD_HOST + "/search/" + encode(keyword), SUBHD, SUBHD_CLIENT);
            for (String id : SubHdParser.subtitleIds(body)) {
                OnlineSubtitle item = SubHdParser.toItem(id, body, SUBHD_HOST, SUBHD);
                if (item != null) items.add(item);
            }
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "online search SubHD failed error=%s", e.getMessage());
        }
        return items;
    }

    /**
     * Resolves one SubHD result into a downloadable document.
     *
     * <p>The handshake mirrors what the site's own page does, in the same order:
     * open the detail page (which hands out the session cookie), ask
     * {@code prepare-download}, open the intermediate download page, then ask
     * {@code /api/sub/down} for the real link. Skipping a step makes the endpoint
     * answer "the temporary page has expired" instead.
     */
    private static String resolveSubHd(OnlineSubtitle item) {
        String encoded = encode(item.getId().isEmpty() ? item.getName() : item.getId());
        if (encoded.isEmpty()) return "";
        try {
            get(SUBHD_HOST + "/a/" + encoded, SUBHD, SUBHD_CLIENT);
            post(SUBHD_HOST + "/api/sub/prepare-download", "{\"sid\":\"" + escape(encoded) + "\"}", SUBHD_HOST + "/a/" + encoded, SUBHD_CLIENT);
            get(SUBHD_HOST + "/down/" + encoded, SUBHD, SUBHD_CLIENT);
            String body = post(SUBHD_HOST + "/api/sub/down", "{\"sid\":\"" + escape(encoded) + "\"}", SUBHD_HOST + "/down/" + encoded, SUBHD_CLIENT);
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) return "";
            JsonObject object = parsed.getAsJsonObject();
            if (!bool(object, "pass")) return "";
            return string(object, "url");
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "online download SubHD resolve failed sid=%s error=%s", item.getId(), e.getMessage());
            return "";
        }
    }

    // --- download -------------------------------------------------------------------

    /**
     * Downloads {@code item} and writes it next to the cache so the player can read
     * it as an ordinary external subtitle. Returns the {@code file://} URL, or null
     * when nothing usable could be stored.
     */
    public static String download(OnlineSubtitle item) {
        if (item == null || !item.isUsable()) return null;
        String url = item.getDownloadUrl();
        if (SUBHD.equals(item.getSource())) {
            url = resolveSubHd(item);
            if (url.isEmpty()) return null;
        }
        try {
            byte[] data = bytes(url, item.isGzip());
            if (data.length == 0) return null;
            // The provider's extension is not always the document's format: SubHD
            // serves ASS behind links that the player would otherwise mislabel.
            String format = SubtitleFileTranslator.detectFormat(decode(data));
            String name = sanitize(item.getName());
            String url2 = SubtitleSource.writeOnline(name + "#" + SubtitleSource.digest(url), data, format);
            if (url2 == null) return null;
            if (SpiderDebug.isEnabled()) SpiderDebug.log(TAG, "online download source=%s name=%s format=%s bytes=%d", item.getSource(), item.getName(), format, data.length);
            return url2;
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "online download failed source=%s name=%s error=%s", item.getSource(), item.getName(), e.getMessage());
            return null;
        }
    }

    private static byte[] bytes(String url, boolean gzip) throws Exception {
        try (Response response = CLIENT.newCall(new Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return new byte[0];
            byte[] data = readBytes(response.body().byteStream());
            if (data.length == 0 || data.length > MAX_BYTES) return new byte[0];
            return gzip ? gunzip(data) : data;
        }
    }

    /**
     * Unwraps a gzip member. The providers serve a real gzip body, so the bytes
     * start with the magic regardless of what the response headers claim; a body
     * that is not gzip is passed through unchanged rather than thrown away.
     */
    static byte[] gunzip(byte[] data) {
        if (data == null || data.length == 0) return new byte[0];
        if (data.length < 2 || (data[0] & 0xFF) != 0x1F || (data[1] & 0xFF) != 0x8B) return data;
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(data));
             ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(1024, data.length * 4))) {
            byte[] buffer = new byte[16384];
            int read;
            long total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BYTES) return new byte[0];
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (Throwable e) {
            // A truncated or otherwise unreadable member is a provider-side problem
            // the caller already reports as a failed download; returning nothing is
            // the whole of this method's contract.
            return new byte[0];
        }
    }

    private static byte[] readBytes(java.io.InputStream input) throws java.io.IOException {
        try (java.io.InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = stream.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }

    // --- http helpers ---------------------------------------------------------------

    private static String get(String url, String tag) throws Exception {
        return get(url, tag, CLIENT);
    }

    private static String get(String url, String tag, OkHttpClient client) throws Exception {
        Request request = new Request.Builder().url(url)
                .header("User-Agent", SUBHD.equals(tag) ? BROWSER_UA : USER_AGENT)
                .header("Accept", "*/*")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.body() == null) return "";
            return response.body().string();
        }
    }

    private static String post(String url, String json, String referer, OkHttpClient client) throws Exception {
        Request request = new Request.Builder().url(url)
                .header("User-Agent", BROWSER_UA)
                .header("Referer", referer)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json")
                .post(RequestBody.create(json, MediaType.parse("application/json; charset=utf-8")))
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.body() == null) return "";
            return response.body().string();
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20");
        } catch (Throwable e) {
            return "";
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Keeps the provider's file name usable as a local file name. */
    private static String sanitize(String name) {
        String value = name == null ? "" : name.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (value.length() > 60) value = value.substring(0, 60);
        return value.isEmpty() ? "subtitle" : value;
    }

    static String seasonOf(String episode) {
        if (episode == null) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)S(\\d{1,2})").matcher(episode);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String string(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
        } catch (Throwable e) {
            return "";
        }
    }

    private static long number(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? 0 : value.getAsLong();
        } catch (Throwable e) {
            return 0;
        }
    }

    private static double decimal(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? 0 : value.getAsDouble();
        } catch (Throwable e) {
            return 0;
        }
    }

    private static boolean bool(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            return value != null && !value.isJsonNull() && value.getAsBoolean();
        } catch (Throwable e) {
            return false;
        }
    }

    /** Decodes stored bytes for format detection; the shared rules cover BOM and GBK. */
    static String decode(byte[] data) {
        return SubtitleSource.decode(data);
    }
}
