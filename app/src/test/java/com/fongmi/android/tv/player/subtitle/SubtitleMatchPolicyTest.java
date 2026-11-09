package com.fongmi.android.tv.player.subtitle;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the query rules and the ranking order. The fixtures mirror the shapes the
 * two providers actually returned while the feature was built: OpenSubtitles
 * reports both a file name and a release name, SubHD reports a release name and
 * Chinese language markers.
 */
public class SubtitleMatchPolicyTest {

    private static SubtitleMatchPolicy.MediaQuery movie() {
        return new SubtitleMatchPolicy.MediaQuery("盗梦空间", null, false, 8880);
    }

    private static SubtitleMatchPolicy.MediaQuery englishMovie() {
        return new SubtitleMatchPolicy.MediaQuery("Inception", null, false, 8880);
    }

    private static SubtitleMatchPolicy.MediaQuery series() {
        return new SubtitleMatchPolicy.MediaQuery("Breaking Bad", "S01E02", true, 2820);
    }

    private static OnlineSubtitle openSubtitles(String name, String release, String lang, String link, long downloads) {
        return OnlineSubtitle.builder()
                .name(name).release(release).language(lang).format("srt")
                .source("OpenSubtitles").downloadUrl(link).downloads(downloads).gzip(true)
                .build();
    }

    private static OnlineSubtitle subtitle(String release, String link, long downloads) {
        return openSubtitles(release + ".srt", release, "chi", link, downloads);
    }

    @Test
    public void profilesExposeOnlySupportedLanguages() {
        assertEquals(List.of("zh-CN", "zh-TW", "en", "ja", "ko"), SubtitleMatchPolicy.profiles());
        assertTrue(SubtitleMatchPolicy.isKnownProfile("zh-CN"));
        assertFalse(SubtitleMatchPolicy.isKnownProfile("de"));
        assertFalse(SubtitleMatchPolicy.isKnownProfile(null));
    }

    @Test
    public void openSubtitlesIdsAreDeduplicatedAcrossProfiles() {
        assertEquals(List.of("chi", "zhe", "zht"), SubtitleMatchPolicy.openSubtitlesIds(List.of("zh-CN", "zh-TW")));
        assertEquals(List.of("chi", "zhe"), SubtitleMatchPolicy.openSubtitlesIds(List.of("zh-CN", "zh-CN", "de")));
        assertTrue(SubtitleMatchPolicy.openSubtitlesIds(null).isEmpty());
    }

    @Test
    public void queryDropsYearAnnotationsAndBracketsButKeepsEpisodeForSeries() {
        assertEquals("盗梦空间", SubtitleMatchPolicy.buildQuery(new SubtitleMatchPolicy.MediaQuery("盗梦空间 (2010)", null, false, 0)));
        assertEquals("Inception", SubtitleMatchPolicy.buildQuery(new SubtitleMatchPolicy.MediaQuery("【高清】Inception 2010", null, false, 0)));
        assertEquals("Breaking Bad 02", SubtitleMatchPolicy.buildQuery(series()));
        assertEquals("", SubtitleMatchPolicy.buildQuery(new SubtitleMatchPolicy.MediaQuery("  ", null, false, 0)));
        assertEquals("", SubtitleMatchPolicy.buildQuery(null));
    }

    @Test
    public void titleNormalisationIgnoresCaseWidthPunctuationAndVariants() {
        assertEquals(SubtitleMatchPolicy.normalizeTitle("Inception 2010"), SubtitleMatchPolicy.normalizeTitle("ＩＮＣＥＰＴＩＯＮ，2010"));
        assertEquals(SubtitleMatchPolicy.normalizeTitle("盗梦空间"), SubtitleMatchPolicy.normalizeTitle("盜夢空間"));
        assertEquals("inception", SubtitleMatchPolicy.normalizeTitle("Inception.2010.1080p.srt"));
        assertEquals("inception", SubtitleMatchPolicy.normalizeTitle("Inception [2010] (BluRay)"));
    }

    @Test
    public void exactTitleOutranksPopularButUnrelatedResult() {
        List<OnlineSubtitle> items = new ArrayList<>();
        // High download count but a different film: it must not be ranked in at all.
        items.add(subtitle("Other.Movie.2024.1080p", "https://x/other.gz", 900000));
        items.add(subtitle("Inception.2010.BluRay.1080p", "https://x/good.gz", 800));

        List<OnlineSubtitle> ranked = SubtitleMatchPolicy.rank(englishMovie(), items, 10);
        assertEquals(1, ranked.size());
        assertEquals("https://x/good.gz", ranked.get(0).getDownloadUrl());
    }

    @Test
    public void releaseNoiseDoesNotPreventAMatch() {
        assertTrue(SubtitleMatchPolicy.isAcceptable(englishMovie(),
                subtitle("Inception.2010.2160p.BluRay.x265.10bit.DTS-HD.MA.5.1", "https://x/1.gz", 10)));
        assertTrue(SubtitleMatchPolicy.isAcceptable(movie(), subtitle("Inception.2010", "https://x/2.gz", 10)));
        assertTrue(SubtitleMatchPolicy.isAcceptable(movie(), subtitle("盗梦空间.Inception.2010", "https://x/3.gz", 10)));
    }

    @Test
    public void crossScriptPairIsKeptButNeverOutranksAVerifiedTitle() {
        // A Chinese query against English release names cannot be compared by text,
        // so such results survive (dubbed releases are common) but stay behind any
        // result whose title could actually be matched.
        OnlineSubtitle chineseRelease = subtitle("盗梦空间.2010.BluRay", "https://x/zh.gz", 10);
        OnlineSubtitle englishRelease = subtitle("Inception.2010.BluRay", "https://x/en.gz", 900000);

        assertTrue(SubtitleMatchPolicy.isAcceptable(movie(), englishRelease));
        List<OnlineSubtitle> ranked = SubtitleMatchPolicy.rank(movie(), List.of(englishRelease, chineseRelease), 10);
        assertEquals(2, ranked.size());
        assertEquals("https://x/zh.gz", ranked.get(0).getDownloadUrl());

        // The reverse must hold too, otherwise the rule would just be "prefer
        // whatever the query language happens to be".
        List<OnlineSubtitle> reversed = SubtitleMatchPolicy.rank(englishMovie(), List.of(englishRelease, chineseRelease), 10);
        assertEquals("https://x/en.gz", reversed.get(0).getDownloadUrl());
    }

    @Test
    public void wrongEpisodeIsRejected() {
        OnlineSubtitle other = subtitle("Breaking.Bad.S01E05.720p", "https://x/e5.gz", 5000);
        OnlineSubtitle wanted = subtitle("Breaking.Bad.S01E02.720p", "https://x/e2.gz", 50);
        assertFalse(SubtitleMatchPolicy.isAcceptable(series(), other));
        assertTrue(SubtitleMatchPolicy.isAcceptable(series(), wanted));

        List<OnlineSubtitle> ranked = SubtitleMatchPolicy.rank(series(), List.of(other, wanted), 10);
        assertEquals(1, ranked.size());
        assertEquals("https://x/e2.gz", ranked.get(0).getDownloadUrl());
    }

    @Test
    public void episodeNumbersCompareNumerically() {
        assertEquals(100, SubtitleMatchPolicy.episodeFit("02", "Show.S01E2.1080p"));
        assertEquals(100, SubtitleMatchPolicy.episodeFit("2", "Show.S01E02.1080p"));
        assertEquals(0, SubtitleMatchPolicy.episodeFit("2", "Show.S01E10.1080p"));
        // A release with no episode marker is neither confirmed nor rejected:
        // a bare number in a release name is as likely to be a resolution.
        assertEquals(50, SubtitleMatchPolicy.episodeFit("2", "Show.BluRay.1080p"));
    }

    @Test
    public void durationAgreementRewardsCloseMatches() {
        assertEquals(100, SubtitleMatchPolicy.durationFit(2820, 2821));
        assertEquals(85, SubtitleMatchPolicy.durationFit(2820, 2824));
        assertEquals(65, SubtitleMatchPolicy.durationFit(2820, 2828));
        assertEquals(0, SubtitleMatchPolicy.durationFit(2820, 3000));
    }

    @Test
    public void unknownDurationIsNeutralRatherThanPenalised() {
        OnlineSubtitle noDuration = subtitle("Inception.2010", "https://x/a.gz", 100).toBuilder()
                .matchedSeconds(0).build();
        OnlineSubtitle close = subtitle("Inception.2010", "https://x/b.gz", 100).toBuilder()
                .matchedSeconds(8881).build();
        OnlineSubtitle far = subtitle("Inception.2010", "https://x/c.gz", 100).toBuilder()
                .matchedSeconds(5000).build();
        assertTrue(SubtitleMatchPolicy.score(englishMovie(), noDuration) > SubtitleMatchPolicy.score(englishMovie(), far));
        assertTrue(SubtitleMatchPolicy.score(englishMovie(), close) > SubtitleMatchPolicy.score(englishMovie(), noDuration));
    }

    @Test
    public void popularityAndRatingAreBoundedAndMonotonic() {
        assertEquals(0, SubtitleMatchPolicy.popularityFit(0));
        assertEquals(100, SubtitleMatchPolicy.popularityFit(100000));
        assertEquals(100, SubtitleMatchPolicy.popularityFit(Long.MAX_VALUE));
        assertTrue(SubtitleMatchPolicy.popularityFit(1000) > SubtitleMatchPolicy.popularityFit(10));
        assertEquals(0, SubtitleMatchPolicy.ratingFit(-1));
        assertEquals(100, SubtitleMatchPolicy.ratingFit(10));
        assertEquals(85, SubtitleMatchPolicy.ratingFit(8.5));
    }

    @Test
    public void duplicatesByDownloadLinkCollapseToTheBetterOne() {
        List<OnlineSubtitle> items = List.of(
                openSubtitles("a.srt", "Inception.2010.HDR", "chi", "https://x/same.gz", 10),
                openSubtitles("b.srt", "Inception.2010.HDR", "chi", "https://x/same.gz", 999999));
        assertEquals(1, SubtitleMatchPolicy.rank(englishMovie(), items, 10).size());
    }

    @Test
    public void unusableEntriesWithoutLinkAreDropped() {
        OnlineSubtitle broken = OnlineSubtitle.builder().name("x.srt").release("Inception.2010").language("chi").format("srt").source("OpenSubtitles").build();
        assertFalse(broken.isUsable());
        assertTrue(SubtitleMatchPolicy.rank(englishMovie(), List.of(broken), 10).isEmpty());
        assertTrue(SubtitleMatchPolicy.rank(null, List.of(broken), 10).isEmpty());
        assertTrue(SubtitleMatchPolicy.rank(englishMovie(), null, 10).isEmpty());
    }

    @Test
    public void limitCapsTheListAndZeroMeansNoCap() {
        List<OnlineSubtitle> items = new ArrayList<>();
        for (int i = 0; i < 30; i++) items.add(subtitle("Inception.2010.Release." + i, "https://x/" + i + ".gz", i));
        assertEquals(5, SubtitleMatchPolicy.rank(englishMovie(), items, 5).size());
        assertEquals(30, SubtitleMatchPolicy.rank(englishMovie(), items, 0).size());
    }

    @Test
    public void languageLabelsCoverBothProviderStyles() {
        assertEquals("简体中文", SubtitleMatchPolicy.languageLabel("chi"));
        assertEquals("繁體中文", SubtitleMatchPolicy.languageLabel("zht"));
        assertEquals("简体中文", SubtitleMatchPolicy.languageLabel("简体"));
        assertEquals("英语", SubtitleMatchPolicy.languageLabel("ENG"));
        // An unknown marker is shown as-is rather than dropped.
        assertEquals("xyz", SubtitleMatchPolicy.languageLabel("xyz"));
        assertEquals("", SubtitleMatchPolicy.languageLabel(null));
    }

    @Test
    public void subHdKeywordsFollowTheChosenProfiles() {
        assertEquals(List.of("简体", "双语", "中文", "繁体", "雙語"), SubtitleMatchPolicy.subHdKeywords(List.of("zh-CN", "zh-TW")));
        assertEquals(List.of("英语", "英文", "英語"), SubtitleMatchPolicy.subHdKeywords(List.of("en", "de")));
    }

    @Test
    public void itemReportsFormatMimeAndLabel() {
        OnlineSubtitle ass = OnlineSubtitle.builder().name("x.ass").release("Inception").language("简体").format("ass").downloadUrl("https://x/a").build();
        OnlineSubtitle srt = OnlineSubtitle.builder().name("x.srt").release("Inception").language("chi").format("srt").downloadUrl("https://x/b").build();
        OnlineSubtitle vtt = OnlineSubtitle.builder().name("x.vtt").release("Inception").language("eng").format("vtt").downloadUrl("https://x/c").build();
        assertEquals("text/x-ssa", ass.mimeType());
        assertEquals("application/x-subrip", srt.mimeType());
        assertEquals("text/vtt", vtt.mimeType());
        // The label lists what is known and omits what is not: no download count
        // is invented for a result that reported none.
        assertEquals("简体中文 · ASS", ass.describe());
        assertEquals("简体中文 · SRT", srt.describe());
        assertEquals("英语 · VTT", vtt.describe());
    }

    @Test
    public void itemKeepsTheRawLanguageMarkerForThePlayer() {
        // The Sub entry is built from fields the player reads directly; the getters
        // on Sub itself go through Android text helpers, so the mapping is asserted
        // on the value object instead.
        OnlineSubtitle item = openSubtitles("x.srt", "Inception.2010", "chi", "https://x/a.gz", 5);
        assertEquals("chi", item.getLanguage());
        assertEquals("x.srt", item.getName());
        assertEquals("https://x/a.gz", item.getDownloadUrl());
    }
}
