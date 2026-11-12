package com.fongmi.android.tv.player.subtitle;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the text track an external subtitle just became, so the player can
 * select it.
 *
 * <p>Adding a subtitle rebuilds the media item, and the rebuilt item carries the
 * subtitle as a new {@code SingleSampleMediaSource} track group. Exo's track
 * selection then runs the app's persisted preference restore, which either
 * disables the text renderer outright or pins it to the previous group. The new
 * group is never referenced, so nothing renders and nothing fails. This class
 * supplies the missing step: which group in the fresh {@code Tracks} snapshot is
 * the subtitle we just added.
 *
 * <p>Identity has to be inferred, because a single-sample source keeps its
 * {@code Uri} in the media source rather than the {@code Format}. What survives
 * into the {@code Format} is the label (the subtitle name), the language and the
 * sample MIME type, so matching scores those three and demands a minimum. The
 * built-in {@code Format.id} is not usable as the primary key either: only ASS
 * subtitles get one, so SRT and VTT arrive with a null id.
 *
 * <p>The ranking is a plain function over {@link Candidate} values so it can be
 * pinned by JVM unit tests: building a media3 {@code Format} pulls in
 * {@code android.text.TextUtils}, which is not available off-device. Only
 * {@link #find} touches media3, and it does nothing but adapt.
 */
public final class PendingSubtitleSelection {

    /**
     * A label match alone is what identifies the added subtitle, so the label is
     * worth more than the signals that merely break ties between equally-named
     * tracks. See {@link #best}.
     */
    private static final int LABEL_SCORE = 2;
    private static final int MIME_SCORE = 1;
    private static final int LANGUAGE_SCORE = 1;

    private PendingSubtitleSelection() {
    }

    /** One text track, as the player reports it. */
    public record Candidate(@Nullable String label, @Nullable String mimeType, @Nullable String language) {
    }

    /** The track group and the index within it that should be selected. */
    public record Match(TrackGroup group, int index) {
    }

    /**
     * Picks the best-scoring candidate.
     *
     * <p>The label is a gate, not just a signal. MIME and language are shared by
     * every track of the same kind — an embedded English SRT scores exactly as
     * high as the English SRT the user just added — so acting on them without a
     * label match would quietly swap the subtitle for another track. Within the
     * candidates that do carry the label, MIME and language break ties.
     *
     * @param label    the subtitle name, as written into the track's label
     * @param mimeType the subtitle MIME type, may be empty
     * @param language the language of the track, may be empty
     * @return the position in {@code candidates}, or -1 when nothing matches
     */
    public static int best(List<Candidate> candidates, @Nullable String label,
                           @Nullable String mimeType, @Nullable String language) {
        if (candidates == null || candidates.isEmpty() || isBlank(label)) return -1;
        int bestIndex = -1;
        int bestScore = 0;
        for (int index = 0; index < candidates.size(); index++) {
            int score = score(candidates.get(index), label, mimeType, language);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    /**
     * Adapts a {@code Tracks} snapshot into {@link Candidate} values and resolves
     * the match back to the group whose override the player needs.
     */
    @Nullable
    public static Match find(Tracks tracks, @Nullable String label,
                             @Nullable String mimeType, @Nullable String language) {
        if (tracks == null || tracks.isEmpty()) return null;
        List<Candidate> candidates = new ArrayList<>();
        List<TrackGroup> groups = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int index = 0; index < group.length; index++) {
                Format format = group.getTrackFormat(index);
                candidates.add(new Candidate(
                        format == null ? null : format.label,
                        format == null ? null : format.sampleMimeType,
                        format == null ? null : format.language));
                groups.add(group.getMediaTrackGroup());
                indices.add(index);
            }
        }
        int position = best(candidates, label, mimeType, language);
        return position < 0 ? null : new Match(groups.get(position), indices.get(position));
    }

    /**
     * Scores one candidate. A candidate whose label does not match scores nothing:
     * see {@link #best} for why the label is a gate rather than one signal among
     * several.
     */
    private static int score(Candidate candidate, @Nullable String label,
                             @Nullable String mimeType, @Nullable String language) {
        if (candidate == null || !label.equals(candidate.label())) return 0;
        int score = LABEL_SCORE;
        if (!isBlank(mimeType) && mimeType.equals(candidate.mimeType())) score += MIME_SCORE;
        if (!isBlank(language) && language.equals(candidate.language())) score += LANGUAGE_SCORE;
        return score;
    }

    /**
     * Deliberately not {@code android.text.TextUtils}: this class is exercised by
     * plain JVM unit tests, where the Android framework is not available.
     */
    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }
}
