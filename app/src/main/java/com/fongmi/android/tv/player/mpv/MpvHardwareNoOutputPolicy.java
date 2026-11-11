package com.fongmi.android.tv.player.mpv;

/**
 * Decides whether a stalled MPV hardware-decode attempt should be retried with
 * software decode.
 *
 * <p>Some Android 16 devices (observed on OnePlus ACE 3V) accept MediaCodec
 * configuration and even packets, but never emit output frames. Another mode on
 * the same device emits one frame and then freezes; see
 * {@link #shouldRetryFrozenWithSoftware}. FFmpeg only
 * logs {@code Dropping unmatched MediaCodec packet properties} at warning level
 * until the packet-properties queue saturates, which never increments mpv's
 * hardware decode failure counter, so {@code hwdec-software-fallback} cannot
 * recover. Playback then stays in startup forever: no first frame, no video
 * frame submitted and no error, so no existing retry path observes it.
 *
 * <p>Detection keys on mpv actually <em>engaging</em> a hardware decoder
 * ({@code hwdec-current}) while never submitting a frame, and it refuses to fire
 * while the demuxer is still starved for data. A slow source starves the cache
 * instead (and usually never selects a track), while a working hardware path
 * submits frames, so neither can trigger the fallback.
 *
 * <p>There is a second, distinct failure of the same device family that the
 * no-frame test cannot see: the hardware decoder <em>does</em> submit a first
 * frame and then wedges, so the position never advances again while the cache
 * stays full. Because a frame was submitted, mpv never counts a decode failure
 * and {@code hwdec-software-fallback} stays idle, yet playback is dead. See
 * {@link #shouldRetryFrozenWithSoftware}.
 */
public final class MpvHardwareNoOutputPolicy {

    /**
     * How long the position may stay frozen before a hardware decoder that already
     * produced a frame is considered wedged. Well above a normal keyframe or seek
     * pause, so a slow-but-working line is never condemned.
     */
    public static final long FROZEN_WINDOW_MS = 6_000L;

    /** Position movement below this many milliseconds counts as "did not advance". */
    public static final long FROZEN_EPSILON_MS = 250L;

    private MpvHardwareNoOutputPolicy() {
    }

    /**
     * Decides whether a hardware decoder that <em>already submitted a frame</em> but
     * stopped advancing the position should be rebuilt as software.
     *
     * <p>This is the complement of {@link #shouldRetryWithSoftware}: that one covers
     * "engaged but never produced a frame", this one covers "produced a frame, then
     * wedged". Both end in the same software rebuild.
     *
     * <p>Guards, each of which prevents a different false positive:
     * <ul>
     *   <li>{@code ended} — a finished stream stops advancing by design.</li>
     *   <li>{@code playRequested} — a user pause is not a stall.</li>
     *   <li>{@code frozenMs} — the position must be stuck for a real window, not a
     *       momentary seek or keyframe gap.</li>
     *   <li>cache — a starved demuxer (still waiting for data) explains the stall by
     *       itself and must be left alone; only a cache that is satisfied while the
     *       position still does not move means the decoder is the bottleneck.</li>
     * </ul>
     *
     * @param cacheBufferingState mpv {@code cache-buffering-state}; 100 means the
     *                            cache is satisfied, 1..99 means it is waiting.
     * @param cacheBytesGrew      observed cache byte growth during the frozen window,
     *                            which proves data is arriving and the demuxer is not the
     *                            bottleneck.
     */
    public static boolean shouldRetryFrozenWithSoftware(boolean hardware, boolean frameSubmitted,
                                                        String observedHwdecCurrent, long frozenMs,
                                                        int cacheBufferingState, boolean cacheBytesGrew,
                                                        boolean ended, boolean playRequested,
                                                        boolean alreadyRetried) {
        if (alreadyRetried || !hardware || !frameSubmitted) return false;
        if (ended || !playRequested) return false;
        if (!isHardwareDecoder(observedHwdecCurrent)) return false;
        if (frozenMs < FROZEN_WINDOW_MS) return false;
        return cacheBytesGrew || !isSourceStarved(cacheBufferingState);
    }

    /**
     * @param cacheBufferingState mpv {@code cache-buffering-state}; 100 means the
     *                            cache is satisfied, values in between mean the
     *                            demuxer is still waiting for data.
     */
    public static boolean shouldRetryWithSoftware(boolean hardware, boolean frameSubmitted,
                                                  String observedHwdecCurrent, int cacheBufferingState,
                                                  boolean alreadyRetried) {
        if (alreadyRetried || !hardware || frameSubmitted) return false;
        if (isSourceStarved(cacheBufferingState)) return false;
        return isHardwareDecoder(observedHwdecCurrent);
    }

    /** True while the demuxer reports it is waiting for data it cannot yet read. */
    public static boolean isSourceStarved(int cacheBufferingState) {
        return cacheBufferingState > 0 && cacheBufferingState < 100;
    }

    /** True when mpv reports a hardware decoder, as opposed to none or software. */
    public static boolean isHardwareDecoder(String hwdec) {
        if (hwdec == null) return false;
        String value = hwdec.trim();
        if (value.isEmpty() || "no".equals(value) || "auto".equals(value)
                || "auto-safe".equals(value) || "auto-copy".equals(value)) return false;
        return value.contains("mediacodec") || value.contains("vaapi")
                || value.contains("nvdec") || value.contains("cuda")
                || value.contains("vulkan") || value.contains("drm")
                || value.contains("videotoolbox") || value.contains("d3d11");
    }
}
