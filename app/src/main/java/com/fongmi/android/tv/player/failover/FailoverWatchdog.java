package com.fongmi.android.tv.player.failover;

import android.os.SystemClock;

import androidx.media3.common.Player;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.player.PlaybackTrace;
import com.fongmi.android.tv.player.PlayerManager;
import com.github.catvod.crawler.SpiderDebug;

import java.util.List;

/**
 * The multi-source failover watchdog (T2-24).
 *
 * <p>Samples the active line once per second, feeds {@link FailoverHealthTracker}, writes the
 * runtime score back onto the owning {@link Flag}, and asks {@link FailoverRetryPolicy} which
 * recovery step to take. It never touches the UI directly: the host activity supplies the
 * callbacks that actually re-open a line, so the phone and TV flavours share one watchdog.
 *
 * <p>All decisions live in the pure {@code Failover*} classes; this type is only the sampler
 * and the wiring, which keeps the whole ladder unit-testable without a device.
 */
public final class FailoverWatchdog {

    /** A line that has not been re-sampled within this window is presumed paused/backgrounded. */
    private static final long STALE_SAMPLE_MS = 5_000L;
    /** Minimum gap between two recovery actions, so one bad streak acts once. */
    private static final long ACTION_COOLDOWN_MS = 10_000L;
    /**
     * No degradation verdict during the first seconds of a line: a legitimate slow start
     * must never be mistaken for a broken source. Fatal errors still act immediately.
     */
    private static final long STARTUP_GRACE_MS = 12_000L;

    public interface Host {

        /** The line (flag) list currently offered for this episode, or null/empty when none. */
        List<Flag> flags();

        /** Index of the flag being played, or -1 when unknown. */
        int currentFlagIndex();

        /** Whether the user's automatic source change setting is on. */
        boolean autoChangeEnabled();

        /** Whether there is another site (站源) to fall back to via search. */
        boolean hasNextSite();

        /** Same-line re-open rounds allowed before switching (0..3). */
        default int maxSameSourceRetries() {
            return FailoverRetryPolicy.DEFAULT_SAME_SOURCE_RETRIES;
        }

        /** Re-open the current line (same flag) from {@code positionMs}. */
        void retrySameSource(long positionMs);

        /** Switch to the flag at {@code index}, preserving the written progress. */
        void switchToFlag(int index, long positionMs);

        /** Fall back to the next site via the existing search path. */
        void switchToNextSite();

        /** Trace id used for the failover decision trail. */
        default String traceId() {
            return PlaybackTrace.NONE;
        }

        /**
         * Identity of the line currently playing (flag + episode), used to tell a re-open of
         * the <i>same</i> line apart from a switch to a different one. Re-arming the same line
         * must keep its retry count, otherwise a dead line would be retried forever.
         */
        default String lineIdentity() {
            return "";
        }
    }

    /** Sampling period. The watchdog drives itself so a stalled line is still sampled. */
    private static final long SAMPLE_PERIOD_MS = 1_000L;
    /** How close to the duration counts as a genuine end rather than a truncated stream. */
    private static final long END_TOLERANCE_MS = 3_000L;

    private final FailoverHealthTracker tracker = new FailoverHealthTracker();
    private final Host host;
    private PlayerManager player;
    private final Runnable ticker = this::onTick;

    private int sameSourceRetries;
    private String lineIdentity = "";
    private long lastSampleMs;
    private long lastActionMs;
    private long startedAtMs;
    private FailoverErrorKind pendingError = FailoverErrorKind.NONE;
    private boolean armed;

    public FailoverWatchdog(Host host) {
        this.host = host;
    }

    /** (Re)start watching a freshly prepared line. */
    public void start(PlayerManager player) {
        this.player = player;
        String identity = host.lineIdentity();
        // A different line starts with a clean slate; re-opening the same line keeps its
        // retry count so a permanently dead source still escalates to a switch.
        if (!identity.equals(lineIdentity)) {
            lineIdentity = identity;
            sameSourceRetries = 0;
        }
        tracker.reset();
        lastSampleMs = 0;
        lastActionMs = 0;
        startedAtMs = SystemClock.elapsedRealtime();
        pendingError = FailoverErrorKind.NONE;
        armed = player != null && !player.isEmpty();
        // Self-scheduled: position callbacks stop arriving while a line stalls, which is
        // precisely the moment the watchdog must keep judging. The host timer alone is not
        // enough, so the watchdog owns its own clock.
        App.removeCallbacks(ticker);
        if (armed) App.post(ticker, SAMPLE_PERIOD_MS);
    }

    public void stop() {
        armed = false;
        player = null;
        App.removeCallbacks(ticker);
    }

    private void onTick() {
        if (!armed) return;
        try {
            tick();
        } finally {
            if (armed) App.post(ticker, SAMPLE_PERIOD_MS);
        }
    }

    public boolean isArmed() {
        return armed;
    }

    /** Feed a hard player error; a fatal one condemns the line immediately. */
    public void onError(String message) {
        if (!armed) return;
        FailoverErrorKind kind = FailoverErrorKind.classify(message, 0);
        if (!kind.isError()) return;
        pendingError = kind;
        tracker.observeError(kind);
        PlaybackTrace.log("failover", host.traceId(), "error kind=%s fatal=%s score=%.1f",
                kind, kind.fatal(), tracker.score());
    }

    /**
     * Called once per second by the host clock. Returns the action taken, or null when the
     * watchdog stayed idle (not armed, paused, or the line is still acceptable).
     */
    public FailoverRetryPolicy.Action tick() {
        if (!armed || player == null) return null;
        long now = SystemClock.elapsedRealtime();
        // A stalled line stops reporting isPlaying(), so liveness must be "the user asked for
        // playback and the player is not stopped" — otherwise the stall we exist to catch
        // would switch the watchdog off. An explicit pause is genuinely not judgeable.
        Player raw = player.getPlayer();
        // A finished video also stops progressing. Treating that as a stall would "fail over"
        // an episode the user simply watched to the end, so a completed stream is not judgeable.
        // A truncated stream also reports ENDED, though, so "finished" must mean the position
        // actually reached the duration; otherwise the drop stays a judgeable failure.
        boolean live;
        if (raw == null) {
            live = player.isPlaying() || player.isLoading();
        } else if (raw.getPlaybackState() == Player.STATE_ENDED) {
            long duration = raw.getDuration();
            boolean reallyFinished = duration > 0 && raw.getCurrentPosition() >= duration - END_TOLERANCE_MS;
            live = !reallyFinished;
        } else {
            live = raw.getPlayWhenReady() && raw.getPlaybackState() != Player.STATE_IDLE;
        }
        if (!live) {
            // Paused / idle: erase the window so a long pause is never read as a stall.
            tracker.reset();
            pendingError = FailoverErrorKind.NONE;
            lastSampleMs = now;
            return null;
        }
        if (lastSampleMs != 0 && now - lastSampleMs > STALE_SAMPLE_MS) {
            // Sampling gap (backgrounded / screen off): start a clean window.
            tracker.reset();
            pendingError = FailoverErrorKind.NONE;
            startedAtMs = now;
        }
        lastSampleMs = now;

        FailoverObservation matured = tracker.observe(
                now,
                player.getPosition(),
                player.getRebufferCount(),
                player.getRebufferTotalMs(),
                player.getBufferedDuration(),
                player.getNetworkProtectionStableThroughput(),
                player.getNetworkProtectionMediaBitrate());

        FailoverErrorKind error = pendingError;
        pendingError = FailoverErrorKind.NONE;
        double lineScore = tracker.score();
        // A reported error is evidence in itself: it drives the ladder regardless of window
        // length, so a mid-playback timeout escalates exactly as the work order describes.
        // Sustained badness needs a matured window and a passed startup grace, so a short
        // warm-up window can never condemn a healthy line.
        boolean unhealthy = error.isError()
                || tracker.shouldAct() && now - startedAtMs >= STARTUP_GRACE_MS;
        FailoverRetryPolicy.Action action = unhealthy ? FailoverRetryPolicy.decide(new FailoverRetryPolicy.Input(
                host.autoChangeEnabled(),
                error.fatal(),
                lineScore,
                sameSourceRetries,
                host.maxSameSourceRetries(),
                hasNextFlag(),
                host.hasNextSite())) : null;

        publishScore(lineScore);
        // One line per matured judgement window (>= 8s): proves sampling is live in the field
        // without flooding the diagnostic ring buffer.
        if (matured != null) {
            PlaybackTrace.log("failover", host.traceId(),
                    "window score=%.1f badWindows=%d severe=%s window=%dms played=%dms rebuffer=%d/%dms buffered=%dms throughput=%d bitrate=%d error=%s progress=%s",
                    lineScore, tracker.consecutiveBadWindows(), tracker.shouldAct(),
                    matured.windowMs(), matured.playedMs(), matured.rebufferCount(),
                    matured.rebufferMs(), matured.bufferedMs(), matured.throughputBps(),
                    matured.mediaBitrateBps(), error, matured.progressing());
        }
        if (action == null) return null;
        if (SpiderDebug.isEnabled()) {
            SpiderDebug.log("failover", "tick score=%.1f error=%s action=%s retries=%d badWindows=%d",
                    lineScore, error, action, sameSourceRetries, tracker.consecutiveBadWindows());
        }
        if (now - lastActionMs < ACTION_COOLDOWN_MS) return null;
        lastActionMs = now;

        switch (action) {
            case RETRY_SAME -> retrySameSource();
            case NEXT_FLAG -> switchToNextFlag();
            case NEXT_SITE -> switchToNextSite();
            default -> {
                return action;
            }
        }
        return action;
    }

    private void retrySameSource() {
        sameSourceRetries++;
        long position = player == null ? 0 : Math.max(0, player.getPosition());
        PlaybackTrace.log("failover", host.traceId(), "action=retry-same position=%d round=%d score=%.1f",
                position, sameSourceRetries, tracker.score());
        tracker.reset();
        host.retrySameSource(position);
    }

    private void switchToNextFlag() {
        int index = host.currentFlagIndex();
        if (index < 0) {
            switchToNextSite();
            return;
        }
        long position = player == null ? 0 : Math.max(0, player.getPosition());
        PlaybackTrace.log("failover", host.traceId(), "action=next-flag from=%d to=%d position=%d score=%.1f",
                index, index + 1, position, tracker.score());
        host.switchToFlag(index + 1, position);
        stop();
    }

    private void switchToNextSite() {
        PlaybackTrace.log("failover", host.traceId(), "action=next-site score=%.1f", tracker.score());
        host.switchToNextSite();
        stop();
    }

    private void publishScore(double score) {
        List<Flag> flags = host.flags();
        int index = host.currentFlagIndex();
        if (flags == null || index < 0 || index >= flags.size()) return;
        Flag flag = flags.get(index);
        if (flag == null) return;
        flag.setFailoverScore(score);
        flag.setFailoverRetries(sameSourceRetries);
    }

    private boolean hasNextFlag() {
        List<Flag> flags = host.flags();
        int index = host.currentFlagIndex();
        return flags != null && index >= 0 && index + 1 < flags.size();
    }
}
