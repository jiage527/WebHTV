package com.fongmi.android.tv.dlna;

import android.content.Context;
import android.content.Intent;

import com.fongmi.android.tv.ui.activity.CastActivity;

/**
 * Opens the media a DLNA control point pushed to this device.
 *
 * <p>The receiver services live in the shared source set, but playing a pushed
 * item is flavour specific: on a TV it runs in {@link CastActivity}, which owns
 * the cast-specific controls. This class keeps that step out of the shared code
 * so the phone flavour can hand the same item to its own player instead.
 */
public final class CastPlayback {

    private CastPlayback() {
    }

    public static void play(Context context, CastAction action) {
        Intent intent = new Intent(context, CastActivity.class);
        intent.putExtra(CastAction.KEY_EXTRA, action);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(intent);
    }
}
