package androidx.media3.mpvplayer;

import java.util.Locale;

/**
 * Decides whether a passthrough list must be dropped after mpv reported a real
 * audio-output initialization failure.
 *
 * <p><b>Why this exists.</b> The app publishes {@code audio-spdif} from route
 * probing ({@code MpvAudioCapabilities}), but the option is applied once at
 * initialization. When the probed list still names a codec whose carrier the
 * output cannot actually open, mpv logs
 * {@code Could not open/initialize audio device -> no sound.} and continues with
 * a dead audio path: there is no sound, and because {@code video-sync} defaults
 * to {@code audio} the video clock then waits on a clock that never advances.
 * One cause, two symptoms (silent playback and a frozen picture while the
 * demuxer keeps reporting throughput). Nothing in the Java layer reacted to that
 * line, so playback stayed broken for the whole item.
 *
 * <p>The retry value is the empty string, which is mpv's own default and simply
 * disables passthrough, so the track is decoded to PCM instead. This is the same
 * state a user reaches by turning the passthrough switch off manually.
 *
 * <p>Pure logic and Android-free so the line matching is unit-testable: the
 * literal messages are taken from the bundled {@code libmpv.so}, not invented.
 * An empty or absent configured list means passthrough was already off, so there
 * is nothing to drop and the policy stays silent.
 */
final class MpvAudioSpdifFailurePolicy {

    private static final String NO_SOUND =
            "could not open/initialize audio device";
    private static final String AO_INIT_FAILED =
            "audio output initialization failed";
    private static final String DRIVER_INIT_FAILED =
            "failed to initialize audio driver";

    private MpvAudioSpdifFailurePolicy() {
    }

    /** Check the log and configuration before touching the audio chain again. */
    static boolean shouldInspect(String configuredCodecs, String logLine,
                                 boolean attempted) {
        if (attempted) return false;
        if (configuredCodecs == null || configuredCodecs.trim().isEmpty()) return false;
        return isAudioOutputInitFailure(logLine);
    }

    /**
     * True only for mpv's own audio-output initialization failures.
     *
     * <p>Deliberately narrow: a generic buffering or network warning must not
     * silently disable passthrough for users whose HDMI sink works.
     */
    static boolean isAudioOutputInitFailure(String line) {
        String normalized = normalize(line);
        if (normalized.isEmpty()) return false;
        return normalized.contains(NO_SOUND)
                || normalized.contains(AO_INIT_FAILED)
                || normalized.contains(DRIVER_INIT_FAILED);
    }

    /** Runtime value that disables passthrough and restores PCM decoding. */
    static String disabledCodecs() {
        return "";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }
}
