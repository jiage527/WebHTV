package com.fongmi.android.tv.player.subtitle;

import com.fongmi.android.tv.bean.Sub;

/**
 * A subtitle offered by an online provider, plus the metadata used to rank it
 * against what is playing.
 *
 * <p>The class is deliberately free of network types so the matching rules can
 * be exercised on the JVM. Two download shapes are modelled because the two
 * supported providers differ: OpenSubtitles serves a gzip-compressed document
 * behind a stable link, while SubHD hands out a temporary direct link that has
 * to be fetched as-is.
 */
public final class OnlineSubtitle {

    private final String id;
    private final String name;
    private final String language;
    private final String format;
    private final String release;
    private final String source;
    private final String downloadUrl;
    private final long downloads;
    private final double rating;
    private final boolean gzip;
    private final double matchedSeconds;
    private final boolean hd;

    private OnlineSubtitle(Builder builder) {
        this.id = safe(builder.id);
        this.name = safe(builder.name);
        this.language = safe(builder.language);
        this.format = safe(builder.format).toLowerCase(java.util.Locale.ROOT);
        this.release = safe(builder.release);
        this.source = safe(builder.source);
        this.downloadUrl = safe(builder.downloadUrl);
        this.downloads = Math.max(0, builder.downloads);
        this.rating = builder.rating;
        this.gzip = builder.gzip;
        this.matchedSeconds = builder.matchedSeconds;
        this.hd = builder.hd;
    }

    /** Provider-side result id, empty when the provider identifies results by link. */
    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /** Raw provider language marker (OpenSubtitles id, SubHD label), never empty for a usable item. */
    public String getLanguage() {
        return language;
    }

    public String getFormat() {
        return format;
    }

    /** The release name the subtitle was authored for, used as the match key. */
    public String getRelease() {
        return release;
    }

    public String getSource() {
        return source;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public long getDownloads() {
        return downloads;
    }

    public double getRating() {
        return rating;
    }

    /** True when the served bytes are a gzip member rather than the document itself. */
    public boolean isGzip() {
        return gzip;
    }

    /** Rough running time the provider reports, 0 when unknown. */
    public double getMatchedSeconds() {
        return matchedSeconds;
    }

    public boolean isHd() {
        return hd;
    }

    public boolean isUsable() {
        return !downloadUrl.isEmpty();
    }

    /**
     * The mime type to register the track under, derived from the document
     * format. The values are the same ones the rest of the app maps subtitle
     * suffixes to, spelled out here so this class stays usable without Android.
     */
    public String mimeType() {
        if (format.contains("ssa") || format.contains("ass")) return "text/x-ssa";
        if (format.contains("vtt")) return "text/vtt";
        if (format.contains("ttml") || format.contains("xml") || format.contains("dfxp")) return "application/ttml+xml";
        return "application/x-subrip";
    }

    /** The {@link Sub} entry handed to the player once the file has been written. */
    public Sub toSub(String localUrl) {
        return Sub.create(name, localUrl, language, mimeType());
    }

    /**
     * A short label for the search result list: language, format and popularity.
     * Kept here so the dialog stays free of scoring logic.
     */
    public String describe() {
        StringBuilder builder = new StringBuilder();
        String lang = languageLabel(language);
        if (!lang.isEmpty()) builder.append(lang);
        if (!format.isEmpty()) builder.append(builder.length() == 0 ? "" : " · ").append(format.toUpperCase(java.util.Locale.ROOT));
        if (downloads > 0) builder.append(builder.length() == 0 ? "" : " · ").append(downloads).append(" 次下载");
        return builder.toString();
    }

    /**
     * Labels a language marker, or a "/"-joined set of them, as SubHD publishes
     * several markers for one result while OpenSubtitles publishes a single id.
     */
    private static String languageLabel(String marker) {
        if (marker == null || marker.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        for (String part : marker.split("/")) {
            String label = SubtitleMatchPolicy.languageLabel(part);
            if (label.isEmpty()) continue;
            builder.append(builder.length() == 0 ? "" : "/").append(label);
        }
        return builder.toString();
    }

    @Override
    public String toString() {
        return "OnlineSubtitle{name=" + name + ", lang=" + language + ", format=" + format + ", source=" + source + ", downloads=" + downloads + "}";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    /** A builder pre-filled with this entry, for tests and for adjusting one field. */
    public Builder toBuilder() {
        return builder().id(id).name(name).language(language).format(format).release(release)
                .source(source).downloadUrl(downloadUrl).downloads(downloads).rating(rating)
                .gzip(gzip).matchedSeconds(matchedSeconds).hd(hd);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private String id;
        private String name;
        private String language;
        private String format;
        private String release;
        private String source;
        private String downloadUrl;
        private long downloads;
        private double rating;
        private boolean gzip;
        private double matchedSeconds;
        private boolean hd;

        public Builder id(String value) {
            this.id = value;
            return this;
        }

        public Builder name(String value) {
            this.name = value;
            return this;
        }

        public Builder language(String value) {
            this.language = value;
            return this;
        }

        public Builder format(String value) {
            this.format = value;
            return this;
        }

        public Builder release(String value) {
            this.release = value;
            return this;
        }

        public Builder source(String value) {
            this.source = value;
            return this;
        }

        public Builder downloadUrl(String value) {
            this.downloadUrl = value;
            return this;
        }

        public Builder downloads(long value) {
            this.downloads = value;
            return this;
        }

        public Builder rating(double value) {
            this.rating = value;
            return this;
        }

        public Builder gzip(boolean value) {
            this.gzip = value;
            return this;
        }

        public Builder matchedSeconds(double value) {
            this.matchedSeconds = value;
            return this;
        }

        public Builder hd(boolean value) {
            this.hd = value;
            return this;
        }

        public OnlineSubtitle build() {
            return new OnlineSubtitle(this);
        }
    }
}
