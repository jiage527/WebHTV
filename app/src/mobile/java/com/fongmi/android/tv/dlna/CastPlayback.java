package com.fongmi.android.tv.dlna;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.web.WebHomeInlineVodStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.jupnp.support.contentdirectory.DIDLParser;

import java.util.Map;

/**
 * Opens the media a DLNA control point pushed to this device.
 *
 * <p>The receiver services live in the shared source set, but playing a pushed
 * item is flavour specific: a phone has no cast screen, so the item is handed to
 * the ordinary player instead. The URL and its headers are registered with
 * {@link WebHomeInlineVodStore}, which already exists to play a raw URL together
 * with headers, and the player is opened on that entry.
 */
public final class CastPlayback {

    private CastPlayback() {
    }

    public static void play(Context context, CastAction action) {
        String id = WebHomeInlineVodStore.put(payload(action));
        String name = title(action);
        Intent intent = new Intent(context, VideoActivity.class);
        intent.putExtra("key", WebHomeInlineVodStore.KEY);
        intent.putExtra("id", id);
        intent.putExtra("name", name);
        intent.putExtra("collect", false);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private static JsonObject payload(CastAction action) {
        JsonObject episode = new JsonObject();
        episode.addProperty("url", action.getCurrentURI());
        JsonArray episodes = new JsonArray();
        episodes.add(episode);
        JsonObject payload = new JsonObject();
        payload.addProperty("title", title(action));
        payload.add("episodes", episodes);
        // Headers go on the payload, not the episode: the store keeps payload
        // headers for the whole entry, and that is the spec the player reads
        // back when it later resolves this id.
        Map<String, String> headers = action.getHeaders();
        if (headers != null && !headers.isEmpty()) payload.add("headers", App.gson().toJsonTree(headers));
        return payload;
    }

    private static String title(CastAction action) {
        try {
            return new DIDLParser().parse(action.getCurrentURIMetaData()).getItems().get(0).getTitle();
        } catch (Exception e) {
            return action.getCurrentURI();
        }
    }
}
