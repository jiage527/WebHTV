package com.fongmi.android.tv.player.mpv;

/**
 * Decides whether a stalled MPV hardware-decode attempt should be retried with
 * software decode.
 *
 * <p>Some Android 16 devices (observed on OnePlus ACE 3V) accept MediaCodec
 * configuration and even packets, but never emit output frames. FFmpeg only
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
 */
public final class MpvHardwareNoOutputPolicy {

    private MpvHardwareNoOutputPolicy() {
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
