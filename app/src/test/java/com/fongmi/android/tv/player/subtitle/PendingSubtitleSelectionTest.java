package com.fongmi.android.tv.player.subtitle;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Pins which text track the player should select after a subtitle was added
 * during playback.
 *
 * <p>The rebuild that carries the new subtitle produces a brand new track group,
 * and the only identity that survives into its {@code Format} is the label, the
 * language and the sample MIME type — a single-sample source keeps its URI in the
 * media source, and only ASS subtitles get a {@code Format.id} at all. These tests
 * therefore exercise the scoring, including the cases where it must refuse to act.
 *
 * <p>Only {@link PendingSubtitleSelection.Candidate} values are used: building a
 * media3 {@code Format} off-device pulls in {@code android.text.TextUtils}, which
 * the JVM test runtime does not provide.
 */
public class PendingSubtitleSelectionTest {

    private static final String SRT = "application/x-subrip";
    private static final String ASS = "text/x-ssa";

    private static PendingSubtitleSelection.Candidate candidate(String label, String mimeType, String language) {
        return new PendingSubtitleSelection.Candidate(label, mimeType, language);
    }

    @Test
    public void findsTheNewlyAddedSubtitleByItsLabel() {
        List<PendingSubtitleSelection.Candidate> tracks = List.of(
                candidate("English", SRT, "en"),
                candidate("Inception.2010.srt", SRT, "en"));
        assertEquals(1, PendingSubtitleSelection.best(tracks, "Inception.2010.srt", SRT, "en"));
    }

    @Test
    public void prefersTheBetterScoringTrackWhenLabelsDiffer() {
        List<PendingSubtitleSelection.Candidate> tracks = List.of(
                candidate("English", SRT, "en"),
                candidate("Inception.2010.srt", SRT, "zh-Hans"));
        assertEquals(1, PendingSubtitleSelection.best(tracks, "Inception.2010.srt", SRT, "zh-Hans"));
    }

    @Test
    public void findsTheSubtitleInsideItsOwnGroup() {
        List<PendingSubtitleSelection.Candidate> tracks = List.of(
                candidate("first.srt", SRT, "en"),
                candidate("second.srt", ASS, "zh-Hans"));
        assertEquals(1, PendingSubtitleSelection.best(tracks, "second.srt", ASS, "zh-Hans"));
    }

    @Test
    public void refusesWhenOnlyANonLabelSignalMatches() {
        // MIME + language alone is 2 points, but the label is what identifies the
        // file. Acting on a weaker signal would silently replace the user's track.
        List<PendingSubtitleSelection.Candidate> tracks = List.of(candidate("English", SRT, "en"));
        assertEquals(-1, PendingSubtitleSelection.best(tracks, "nothing.matches.srt", SRT, "en"));
    }

    @Test
    public void acceptsALabelMatchEvenWithoutMimeOrLanguage() {
        List<PendingSubtitleSelection.Candidate> tracks = List.of(candidate("Inception.srt", null, null));
        assertEquals(0, PendingSubtitleSelection.best(tracks, "Inception.srt", SRT, "zh-Hans"));
    }

    @Test
    public void prefersTheTrackThatMatchesOnEverySignal() {
        List<PendingSubtitleSelection.Candidate> tracks = List.of(
                candidate("Inception.srt", ASS, "en"),
                candidate("Inception.srt", SRT, "zh-Hans"));
        assertEquals(1, PendingSubtitleSelection.best(tracks, "Inception.srt", SRT, "zh-Hans"));
    }

    @Test
    public void returnsMinusOneForMissingOrEmptyCandidates() {
        assertEquals(-1, PendingSubtitleSelection.best(null, "x.srt", SRT, "en"));
        assertEquals(-1, PendingSubtitleSelection.best(List.of(), "x.srt", SRT, "en"));
    }

    @Test
    public void ignoresCandidatesThatCarryNoLabel() {
        List<PendingSubtitleSelection.Candidate> tracks = List.of(candidate(null, SRT, "en"));
        assertEquals(-1, PendingSubtitleSelection.best(tracks, "x.srt", SRT, "en"));
    }
}
