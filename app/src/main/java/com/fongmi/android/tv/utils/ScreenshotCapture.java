package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.Setting;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Grabs the current video frame with {@link PixelCopy}.
 *
 * <p>The video surface is read directly when it is a {@link SurfaceView} or a
 * {@link TextureView}, so the captured image holds the picture only. When the
 * surface is neither (or is not readable, e.g. a DRM-protected stream) the
 * window region is copied instead, which keeps the UI overlays such as
 * subtitles and danmaku in the shot. Both paths run on the main thread, as
 * PixelCopy requires.
 */
public final class ScreenshotCapture {

    private static final String DIRECTORY = "WebHTV";
    private static final String PREFIX = "WebHTV_";
    private static final String SUFFIX = ".png";
    private static final int QUALITY = 100;

    private ScreenshotCapture() {
    }

    public interface Listener {

        /** Called on the main thread. Exactly one of the arguments is non-null. */
        void onCaptured(@Nullable Bitmap bitmap, @Nullable Integer errorCode);
    }

    /**
     * Copies the frame shown by {@code surface} into a new bitmap. The caller
     * owns that bitmap and must recycle it.
     */
    public static void capture(Activity activity, View surface, Listener listener) {
        if (activity == null || surface == null || surface.getWidth() <= 0 || surface.getHeight() <= 0) {
            listener.onCaptured(null, null);
            return;
        }
        if (surface instanceof TextureView textureView) {
            Bitmap content = textureView.getBitmap();
            listener.onCaptured(content, content == null ? Integer.valueOf(PixelCopy.ERROR_SOURCE_NO_DATA) : null);
            return;
        }
        Bitmap bitmap = Bitmap.createBitmap(surface.getWidth(), surface.getHeight(), Bitmap.Config.ARGB_8888);
        PixelCopy.OnPixelCopyFinishedListener callback = result -> {
            if (result == PixelCopy.SUCCESS) {
                listener.onCaptured(bitmap, null);
            } else {
                bitmap.recycle();
                listener.onCaptured(null, result);
            }
        };
        try {
            if (surface instanceof SurfaceView surfaceView) {
                PixelCopy.request(surfaceView, bitmap, callback, new Handler(Looper.getMainLooper()));
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                int[] origin = new int[2];
                surface.getLocationInWindow(origin);
                Rect region = new Rect(origin[0], origin[1], origin[0] + surface.getWidth(), origin[1] + surface.getHeight());
                PixelCopy.request(activity.getWindow(), region, bitmap, callback, new Handler(Looper.getMainLooper()));
            } else {
                bitmap.recycle();
                listener.onCaptured(null, null);
            }
        } catch (RuntimeException error) {
            bitmap.recycle();
            listener.onCaptured(null, null);
        }
    }

    /**
     * Writes the bitmap as a PNG and returns the file. Blocking: call from a
     * worker thread. Prefers the public Pictures folder, which is only writable
     * once file access has been granted, and otherwise falls back to the app's
     * own external pictures folder so the shot is never lost.
     */
    public static File save(Bitmap bitmap) throws IOException {
        File directory = targetDirectory();
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Cannot create " + directory);
        File file = new File(directory, fileName());
        try (FileOutputStream output = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, QUALITY, output)) throw new IOException("Cannot encode PNG");
            output.flush();
        }
        MediaScannerConnection.scanFile(App.get(), new String[]{file.getAbsolutePath()}, new String[]{"image/png"}, null);
        return file;
    }

    private static File targetDirectory() {
        if (Setting.hasFileAccess()) return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), DIRECTORY);
        return new File(App.get().getExternalFilesDir(Environment.DIRECTORY_PICTURES), DIRECTORY);
    }

    private static String fileName() {
        return PREFIX + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + SUFFIX;
    }

    /** Opens the system share sheet for a saved screenshot. */
    public static void share(Activity activity, File file) {
        if (activity == null || file == null || !file.exists()) return;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("image/png");
        intent.putExtra(Intent.EXTRA_STREAM, FileUtil.getShareUri(file));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(Intent.createChooser(intent, null));
    }
}
