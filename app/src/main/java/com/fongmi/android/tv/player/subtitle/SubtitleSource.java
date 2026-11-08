package com.fongmi.android.tv.player.subtitle;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

import okhttp3.Response;

/**
 * Reads a subtitle document from wherever the player can read media, and writes
 * the translated result back to a path the player can consume.
 *
 * <p>Subtitles supplied by sites are usually served as plain files, but legacy
 * ones are frequently GBK. The encoding is therefore decided from the bytes
 * rather than assumed to be UTF-8; a mis-detected charset turns every translated
 * line into mojibake, which is worse than leaving the subtitle untouched.
 */
public final class SubtitleSource {

    private static final String TAG = "subtitle";
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final String ASSET_PREFIX = "asset:///";

    private SubtitleSource() {
    }

    /** Returns the subtitle text, or {@code null} when it cannot be read. */
    public static String read(String url) {
        if (url == null || url.trim().isEmpty()) return null;
        String value = url.trim();
        try {
            if (value.startsWith(ASSET_PREFIX)) {
                return Path.read(com.fongmi.android.tv.App.get().getAssets().open(value.substring(ASSET_PREFIX.length())));
            }
            if (value.startsWith("file://") || value.startsWith("/")) {
                File file = new File(value.replace("file://", ""));
                return file.isFile() ? decode(Path.readToByte(file)) : null;
            }
            if (value.startsWith("http")) {
                return http(value);
            }
            if (value.startsWith("content://")) {
                File file = copyFromContent(value);
                return file == null ? null : decode(Path.readToByte(file));
            }
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "subtitle read failed url=%s error=%s", value, e.getMessage());
        }
        return null;
    }

    private static String http(String url) throws Exception {
        try (Response response = OkHttp.client().newCall(new okhttp3.Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            long length = response.body().contentLength();
            if (length > MAX_BYTES) return null;
            byte[] data = readBytes(response.body().byteStream());
            return data.length == 0 || data.length > MAX_BYTES ? null : decode(data);
        }
    }

    private static byte[] readBytes(java.io.InputStream input) throws java.io.IOException {
        try (java.io.InputStream stream = input;
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = stream.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }

    private static File copyFromContent(String value) {
        try {
            android.content.ContentResolver resolver = com.fongmi.android.tv.App.get().getContentResolver();
            android.net.Uri uri = android.net.Uri.parse(value);
            String name = "subtitle-" + digest(value) + ".txt";
            File target = Path.cache(name);
            try (java.io.InputStream is = resolver.openInputStream(uri)) {
                if (is == null) return null;
                Path.write(target, is);
            }
            return target;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * Persists translated text in a stable location so the player can re-read it
     * after a rebuild, a track switch or a resume.
     */
    public static String write(String url, String text, String format) {
        try {
            File dir = new File(Path.cache(), "subtitle");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File file = new File(dir, "translated-" + digest(url + format) + "." + extension(format));
            Path.write(file, text.getBytes(StandardCharsets.UTF_8));
            return "file://" + file.getAbsolutePath();
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "subtitle write failed error=%s", e.getMessage());
            return null;
        }
    }

    /** Clears previously generated subtitles when a new item starts. */
    public static void cleanCache() {
        try {
            File dir = new File(Path.cache(), "subtitle");
            if (!dir.isDirectory()) return;
            for (File file : Path.list(dir)) if (file.getName().startsWith("translated-")) Path.clear(file);
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "subtitle cache clean failed error=%s", e.getMessage());
        }
    }

    public static String extension(String format) {
        String value = format == null ? "" : format.toLowerCase(Locale.ROOT);
        if (value.contains("ssa")) return "ass";
        if (value.contains("vtt")) return "vtt";
        if (value.contains("ttml")) return "ttml";
        return "srt";
    }

    /** A short, stable identifier for a URL, used only as a cache key. */
    static String digest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) builder.append(String.format("%02x", item));
            return builder.substring(0, 12);
        } catch (Throwable e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    /**
     * Decodes subtitle bytes. A UTF-8 BOM wins outright; otherwise invalid UTF-8
     * or a large number of GBK-looking pairs selects GB18030, which also covers
     * GBK and GB2312 content.
     */
    static String decode(byte[] data) {
        if (data == null || data.length == 0) return null;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return new String(data, 3, data.length - 3, StandardCharsets.UTF_8);
        }
        if (isValidUtf8(data)) return new String(data, StandardCharsets.UTF_8);
        try {
            return new String(data, Charset.forName("GB18030"));
        } catch (Throwable e) {
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    static boolean isValidUtf8(byte[] data) {
        int index = 0;
        while (index < data.length) {
            int value = data[index] & 0xFF;
            if (value < 0x80) {
                index++;
                continue;
            }
            int extra;
            int minimum;
            if ((value & 0xE0) == 0xC0) {
                extra = 1;
                minimum = 0x80;
            } else if ((value & 0xF0) == 0xE0) {
                extra = 2;
                minimum = 0x800;
            } else if ((value & 0xF8) == 0xF0) {
                extra = 3;
                minimum = 0x10000;
            } else {
                return false;
            }
            if (index + extra >= data.length) return false;
            int codePoint = value & (0x7F >> extra);
            for (int i = 1; i <= extra; i++) {
                int next = data[index + i] & 0xFF;
                if ((next & 0xC0) != 0x80) return false;
                codePoint = (codePoint << 6) | (next & 0x3F);
            }
            if (codePoint < minimum) return false;
            index += extra + 1;
        }
        return true;
    }
}
