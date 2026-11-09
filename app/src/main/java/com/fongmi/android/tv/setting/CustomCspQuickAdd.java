package com.fongmi.android.tv.setting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a bare collection-interface address into a ready-to-insert site entry so
 * a user can paste one URL instead of filling the whole form.
 *
 * <p>Kept free of Android types on purpose: the recognition rules are the part
 * most likely to regress, so they must stay reachable from plain JVM unit tests.
 * The caller writes the produced fields into an Item.
 */
public final class CustomCspQuickAdd {

    /** JSON API: category/detail requests carry {@code ac=detail}. */
    public static final int TYPE_JSON = 1;
    /** XML API: category/detail requests carry {@code ac=videolist}. */
    public static final int TYPE_XML = 0;

    private static final Pattern HOST = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]+)");
    private static final Pattern HEADER_LINE = Pattern.compile("^([A-Za-z][A-Za-z0-9-]*)\\s*:\\s*(\\S.*)$");
    /** Any {@code scheme://} token, used to report a wrong scheme instead of "no address". */
    private static final Pattern ANY_SCHEME = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*://");
    /** A {@code label=value} prefix such as {@code url=} or {@code 名称=}. */
    private static final Pattern LABEL_PREFIX = Pattern.compile("^[^\\s=]+\\s*=\\s*(.*)$");
    private static final List<String> NOISE_KEYS = List.of("ac", "wd", "pg", "ids");

    private CustomCspQuickAdd() {
    }

    /** What the user pasted, after normalisation. */
    public static final class Parsed {

        private final boolean valid;
        private final String name;
        private final String api;
        private final String rawApi;
        private final String headerText;
        private final String reason;

        private Parsed(boolean valid, String name, String api, String rawApi, String headerText, String reason) {
            this.valid = valid;
            this.name = name;
            this.api = api;
            this.rawApi = rawApi;
            this.headerText = headerText;
            this.reason = reason;
        }

        public static Parsed invalid(String reason) {
            return new Parsed(false, "", "", "", "", reason);
        }

        public boolean isValid() {
            return valid;
        }

        public String getName() {
            return name;
        }

        public String getApi() {
            return api;
        }

        /** Header block exactly as the form's header box expects it, possibly empty. */
        public String getHeaderText() {
            return headerText;
        }

        /** Why the text was rejected; only meaningful when {@link #isValid()} is false. */
        public String getReason() {
            return reason;
        }

        /**
         * The type comes from the address the user pasted, not the cleaned one:
         * {@code ac=videolist} is type information even though {@code ac} is
         * otherwise dropped as machine noise.
         */
        public int getType() {
            return detectType(rawApi.isEmpty() ? api : rawApi);
        }
    }

    /**
     * Parses pasted text that may be a bare URL, a {@code name + url} pair, or a
     * header block followed by a URL. Returns an invalid result with a reason
     * instead of throwing, because the caller renders the reason to the user.
     */
    public static Parsed parse(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) return Parsed.invalid("empty");
        List<String> lines = new ArrayList<>();
        for (String line : value.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) lines.add(trimmed);
        }
        if (lines.isEmpty()) return Parsed.invalid("empty");
        String addressLine = null;
        String name = "";
        for (String line : lines) {
            if (isHeaderLine(line)) continue;
            String found = firstUrl(line);
            if (found.isEmpty()) continue;
            addressLine = line;
            name = stripSeparators(line.substring(0, line.indexOf(found)));
            break;
        }
        if (addressLine == null) {
            // A non-http address is a different mistake from "no address at all",
            // and the user can fix it only if the message says which one it is.
            // Header lines are skipped so a Referer value cannot be mistaken for
            // the interface address the user forgot to paste.
            for (String line : lines) {
                if (isHeaderLine(line)) continue;
                if (ANY_SCHEME.matcher(line).find()) return Parsed.invalid("bad-scheme");
            }
            return Parsed.invalid("no-url");
        }
        String raw = firstUrl(addressLine);
        String clean = stripQueryNoise(raw);
        if (!isHttpAddress(clean)) return Parsed.invalid("bad-scheme");
        if (hostOf(clean).isEmpty()) return Parsed.invalid("bad-host");
        if (name.isEmpty()) name = defaultName(clean);
        if (name.isEmpty()) return Parsed.invalid("bad-host");
        return new Parsed(true, name, clean, raw, headerLines(lines, addressLine), "");
    }

    /**
     * A bare maccms-style JSON interface is the common case, so it maps to
     * {@code type = 1}. XML feeds keep the XML API mapping.
     */
    public static int detectType(String api) {
        String lower = api == null ? "" : api.toLowerCase(Locale.ROOT);
        if (lower.contains("ac=videolist")) return TYPE_XML;
        String path = lower;
        int mark = path.indexOf('?');
        if (mark >= 0) path = path.substring(0, mark);
        return path.endsWith(".xml") ? TYPE_XML : TYPE_JSON;
    }

    /**
     * Drops machine-owned query parameters so the stored address stays clean.
     * Unknown parameters are preserved because a private interface may need them.
     */
    public static String stripQueryNoise(String url) {
        if (url == null) return "";
        int mark = url.indexOf('?');
        if (mark < 0) return trimTrailing(url);
        String base = url.substring(0, mark);
        StringBuilder keep = new StringBuilder();
        for (String pair : url.substring(mark + 1).split("&")) {
            if (pair.isEmpty()) continue;
            String key = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
            if (NOISE_KEYS.contains(key.trim().toLowerCase(Locale.ROOT))) continue;
            if (keep.length() > 0) keep.append('&');
            keep.append(pair);
        }
        return trimTrailing(keep.length() == 0 ? base : base + "?" + keep);
    }

    /**
     * A readable default name taken from the host, minus the common {@code www}
     * prefix, so the list shows something meaningful before the user renames it.
     */
    public static String defaultName(String url) {
        String host = hostOf(url);
        if (host.isEmpty()) return "";
        if (host.startsWith("www.")) host = host.substring(4);
        int dot = host.indexOf('.');
        String head = dot > 0 ? host.substring(0, dot) : host;
        return head.isEmpty() ? host : head;
    }

    /** Host of an address, lowercased and without user info or port. */
    public static String hostOf(String url) {
        if (url == null) return "";
        Matcher matcher = HOST.matcher(url.trim());
        if (!matcher.find()) return "";
        String host = matcher.group(1);
        int at = host.indexOf('@');
        if (at >= 0) host = host.substring(at + 1);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        return host.toLowerCase(Locale.ROOT);
    }

    /** True when two addresses point at the same interface and should not both be added. */
    public static boolean isDuplicate(String leftApi, String rightApi) {
        return normalizeForCompare(leftApi).equals(normalizeForCompare(rightApi));
    }

    private static String normalizeForCompare(String url) {
        String value = stripQueryNoise(url == null ? "" : url).toLowerCase(Locale.ROOT);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value.replace("https://", "http://");
    }

    /** Keeps {@code Name: Value} lines from the block, excluding the address line. */
    private static String headerLines(List<String> lines, String addressLine) {
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            if (line.equals(addressLine)) continue;
            if (!isHeaderLine(line)) continue;
            kept.add(line);
        }
        return String.join("\n", kept);
    }

    private static boolean isHeaderBlock(List<String> lines) {
        for (String line : lines) if (isHeaderLine(line)) return true;
        return false;
    }

    /**
     * A header line is {@code Name: Value} whose name is not a URI scheme, which
     * keeps a bare {@code https://...} address from being mistaken for a header
     * while still catching {@code Referer: https://...}.
     */
    static boolean isHeaderLine(String line) {
        if (line == null) return false;
        Matcher matcher = HEADER_LINE.matcher(line.trim());
        if (!matcher.matches()) return false;
        String scheme = matcher.group(1).toLowerCase(Locale.ROOT);
        return !"http".equals(scheme) && !"https".equals(scheme) && !"rtsp".equals(scheme) && !"ftp".equals(scheme) && !"file".equals(scheme) && !"rtmp".equals(scheme);
    }

    /** Extracts the first {@code http(s)} address inside a line, or an empty string. */
    public static String firstUrl(String line) {
        if (line == null) return "";
        String lower = line.toLowerCase(Locale.ROOT);
        int http = lower.indexOf("http://");
        int https = lower.indexOf("https://");
        int start = http < 0 ? https : https < 0 ? http : Math.min(http, https);
        if (start < 0) return "";
        int end = start;
        while (end < line.length() && !Character.isWhitespace(line.charAt(end))) end++;
        return trimTrailing(line.substring(start, end));
    }

    private static boolean isHttpAddress(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String trimTrailing(String value) {
        String result = value == null ? "" : value;
        while (endsWithAny(result, ",", ";", "\"", "'", "]", ")", "。", "，")) {
            result = result.substring(0, result.length() - 1);
        }
        return result.trim();
    }

    private static boolean endsWithAny(String value, String... suffixes) {
        for (String suffix : suffixes) if (value.endsWith(suffix)) return true;
        return false;
    }

    private static String stripSeparators(String value) {
        String result = value.trim();
        Matcher label = LABEL_PREFIX.matcher(result);
        if (label.matches()) result = label.group(1).trim();
        while (endsWithAny(result, ":", "=", "-", ",", "：", "；", "，", "、")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        while (startsWithAny(result, "\"", "'", "-", "：", ",")) result = result.substring(1).trim();
        while (endsWithAny(result, "\"", "'")) result = result.substring(0, result.length() - 1).trim();
        return result;
    }

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) if (value.startsWith(prefix)) return true;
        return false;
    }

    /** Splits a header block into ordered pairs, preserving the user's own casing. */
    public static Map<String, String> headerMap(String text) {
        Map<String, String> map = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) return map;
        for (String line : text.split("\\r?\\n")) {
            int index = line.indexOf(':');
            if (index <= 0) continue;
            String key = line.substring(0, index).trim();
            if (key.isEmpty()) continue;
            map.put(key, line.substring(index + 1).trim());
        }
        return map;
    }
}
