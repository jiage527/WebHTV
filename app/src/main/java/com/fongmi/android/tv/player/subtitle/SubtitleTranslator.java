package com.fongmi.android.tv.player.subtitle;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * A bounded, line-aligned translation client for subtitle text.
 *
 * <p>Lines travel as a single newline-joined value, which the engine answers one
 * segment per source line. Two properties make that safe to rely on: the reply
 * is rejected unless it splits back into exactly as many lines as were sent, and
 * a rejected batch is retried one line at a time. A line that still cannot be
 * translated keeps its source text rather than blocking playback.
 */
public final class SubtitleTranslator {

    private static final String TAG = "subtitle";
    private static final String BASE = "https://translate.googleapis.com/translate_a/single";
    private static final int BATCH = 40;
    private static final int PARALLEL = 4;
    private static final int MAX_TEXT_LENGTH = 6000;
    private static final OkHttpClient CLIENT = OkHttp.client()
            .newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    private SubtitleTranslator() {
    }

    /** A failed translation keeps the source text so playback still works. */
    public static List<String> translate(List<String> lines, String target) {
        List<String> result = new ArrayList<>(lines.size());
        if (target == null || target.isEmpty()) {
            result.addAll(lines);
            return result;
        }
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) if (isPresent(lines.get(i))) indexes.add(i);
        String[] translated = new String[lines.size()];
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int start = 0; start < indexes.size(); start += BATCH) {
                int from = start;
                int to = Math.min(start + BATCH, indexes.size());
                futures.add(pool.submit((Callable<Void>) () -> {
                    fetch(lines, indexes, translated, from, to, target);
                    return null;
                }));
            }
            for (Future<?> future : futures) future.get();
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "translate wait failed: %s", e.getMessage());
        } finally {
            pool.shutdownNow();
        }
        for (int i = 0; i < lines.size(); i++) {
            String value = translated[i];
            result.add(isPresent(value) ? value : lines.get(i));
        }
        return result;
    }

    private static void fetch(List<String> lines, List<Integer> indexes, String[] out, int from, int to, String target) {
        List<Integer> slice = indexes.subList(from, to);
        List<String> values = request(slice, lines, target);
        if (values != null) {
            for (int i = 0; i < slice.size(); i++) out[slice.get(i)] = values.get(i);
            return;
        }
        if (slice.size() == 1) return;
        // A batch whose segmentation does not match is answered one line at a
        // time: a single-line request always has one unambiguous answer.
        for (int i = 0; i < slice.size(); i++) {
            List<String> single = request(slice.subList(i, i + 1), lines, target);
            if (single != null && !single.isEmpty()) out[slice.get(i)] = single.get(0);
        }
    }

    /** Returns {@code null} when the reply cannot be matched back one-to-one. */
    private static List<String> request(List<Integer> slice, List<String> lines, String target) {
        if (slice.isEmpty()) return new ArrayList<>();
        StringBuilder joined = new StringBuilder();
        for (int index : slice) {
            String text = lines.get(index);
            if (text.length() > MAX_TEXT_LENGTH) text = text.substring(0, MAX_TEXT_LENGTH);
            if (joined.length() > 0) joined.append('\n');
            joined.append(text);
        }
        StringBuilder body = new StringBuilder();
        body.append("q=").append(encode(joined.toString()))
                .append("&sl=auto&tl=").append(encode(target))
                .append("&client=gtx&dt=t");
        Request request = new Request.Builder()
                .url(BASE)
                .post(RequestBody.create(body.toString().getBytes(StandardCharsets.UTF_8),
                        MediaType.parse("application/x-www-form-urlencoded")))
                .build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) return null;
            String json = response.body() == null ? "" : response.body().string();
            List<String> values = parse(json);
            if (values == null || values.size() != slice.size()) {
                SpiderDebug.log(TAG, "translate shape mismatch sent=%d got=%s", slice.size(),
                        values == null ? "null" : String.valueOf(values.size()));
                return null;
            }
            return values;
        } catch (IOException e) {
            SpiderDebug.log(TAG, "translate request failed: %s", e.getMessage());
            return null;
        }
    }

    private static boolean isPresent(String text) {
        return text != null && !text.isEmpty();
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    /**
     * Reads the engine's reply into one string per source line.
     *
     * <p>Segments are concatenated (the engine echoes each line's separator) and
     * split on newlines, so the returned count is the number of lines the engine
     * actually separated. The caller compares that count with the number of lines
     * it sent, which is what detects a reply that merged or dropped lines; this
     * method deliberately makes no network or framework calls so that behaviour
     * can be verified directly.
     */
    static List<String> parse(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) return null;
            JsonArray outer = root.getAsJsonArray();
            if (outer.size() == 0 || !outer.get(0).isJsonArray()) return null;
            StringBuilder joined = new StringBuilder();
            for (JsonElement element : outer.get(0).getAsJsonArray()) {
                if (!element.isJsonArray()) continue;
                JsonArray parts = element.getAsJsonArray();
                if (parts.size() == 0) continue;
                JsonElement text = parts.get(0);
                if (text == null || text.isJsonNull()) continue;
                joined.append(text.getAsString());
            }
            if (joined.length() == 0) return null;
            List<String> values = new ArrayList<>();
            int start = 0;
            for (int i = 0; i < joined.length(); i++) {
                if (joined.charAt(i) != '\n') continue;
                values.add(joined.substring(start, i));
                start = i + 1;
            }
            if (start < joined.length()) values.add(joined.substring(start));
            return values.isEmpty() ? null : values;
        } catch (Throwable e) {
            return null;
        }
    }
}
