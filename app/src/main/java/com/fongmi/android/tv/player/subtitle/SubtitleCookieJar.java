package com.fongmi.android.tv.player.subtitle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

/**
 * A minimal in-memory cookie store for the subtitle providers.
 *
 * <p>SubHD only answers its download endpoint when the session cookie it handed
 * out while rendering the detail page is sent back, and a bare OkHttp client
 * keeps nothing. The app already depends on OkHttp, but not on the
 * {@code okhttp-urlconnection} artifact that carries {@code JavaNetCookieJar},
 * so the few rules this handshake needs are implemented here rather than by
 * adding a dependency for one provider.
 *
 * <p>Cookies are keyed by host and path, and expired ones are dropped on read.
 * The store is process-local on purpose: a session is only meaningful for the
 * few seconds a download takes, and persisting one would leave state behind for
 * a flow the viewer may never repeat.
 */
final class SubtitleCookieJar implements CookieJar {

    private final Map<String, Cookie> store = new LinkedHashMap<>();

    @Override
    public synchronized void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
        if (url == null || cookies == null) return;
        for (Cookie cookie : cookies) {
            if (cookie == null) continue;
            if (cookie.expiresAt() < System.currentTimeMillis()) store.remove(key(url, cookie.name()));
            else store.put(key(url, cookie.name()), cookie);
        }
    }

    @Override
    public synchronized List<Cookie> loadForRequest(HttpUrl url) {
        List<Cookie> result = new ArrayList<>();
        if (url == null) return result;
        for (Map.Entry<String, Cookie> entry : new ArrayList<>(store.entrySet())) {
            Cookie cookie = entry.getValue();
            if (cookie.expiresAt() < System.currentTimeMillis()) {
                store.remove(entry.getKey());
                continue;
            }
            if (cookie.matches(url)) result.add(cookie);
        }
        return result;
    }

    private String key(HttpUrl url, String name) {
        return url.host() + "|" + name;
    }
}
