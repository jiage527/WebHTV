package com.fongmi.android.tv.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class EpisodeSeasonGroupingTest {

    private static List<String> episodes(String... names) {
        return List.of(names);
    }

    @Test
    public void chineseSeasonsSplitIntoGroups() {
        List<EpisodeSeasonGrouping.Season> seasons = EpisodeSeasonGrouping.group(episodes(
                "第一季 01", "第一季 02", "第一季 03",
                "第二季 01", "第二季 02"));
        assertEquals(2, seasons.size());
        assertEquals("第一季", seasons.get(0).label());
        assertEquals(0, seasons.get(0).start());
        assertEquals(3, seasons.get(0).end());
        assertEquals("第二季", seasons.get(1).label());
        assertEquals(3, seasons.get(1).start());
        assertEquals(5, seasons.get(1).end());
    }

    @Test
    public void englishSeasonsAreNormalized() {
        List<EpisodeSeasonGrouping.Season> seasons = EpisodeSeasonGrouping.group(episodes(
                "Show S01E01", "Show S01E02", "Show S02E01"));
        assertEquals(2, seasons.size());
        assertEquals("S01", seasons.get(0).label());
        assertEquals("S02", seasons.get(1).label());
    }

    @Test
    public void seasonKeywordIsRecognized() {
        List<EpisodeSeasonGrouping.Season> seasons = EpisodeSeasonGrouping.group(episodes(
                "Show Season 1 Ep 1", "Show Season 1 Ep 2", "Show Season 2 Ep 1"));
        assertEquals(2, seasons.size());
        assertEquals("S01", seasons.get(0).label());
        assertEquals("S02", seasons.get(1).label());
    }

    @Test
    public void singleSeasonProducesNoGrouping() {
        // 只有一季时应保持原有的等分翻页行为。
        assertTrue(EpisodeSeasonGrouping.group(episodes("第一季 01", "第一季 02", "第一季 03")).isEmpty());
    }

    @Test
    public void noSeasonMarkersProducesNoGrouping() {
        assertTrue(EpisodeSeasonGrouping.group(episodes("01", "02", "03", "04")).isEmpty());
    }

    @Test
    public void plainNumbersAreNotMistakenForSeasons() {
        // 「S」后面必须紧跟数字且不能在单词内部，否则 01 会被误读成 S01。
        assertTrue(EpisodeSeasonGrouping.group(episodes("SP01", "SP02", "SP03")).isEmpty());
    }

    @Test
    public void resolutionIsNotMistakenForSeason() {
        // 1920x1080 里的 1080 不能当成季号。
        assertTrue(EpisodeSeasonGrouping.group(episodes(
                "Show 1920x1080 01", "Show 1920x1080 02", "Show 1920x1080 03")).isEmpty());
    }

    @Test
    public void hdrTagIsNotMistakenForSeason() {
        assertTrue(EpisodeSeasonGrouping.group(episodes(
                "Show HDR10 01", "Show HDR10 02", "Show HDR10 03")).isEmpty());
    }

    @Test
    public void unmarkedEpisodesInheritPrecedingSeason() {
        // 中间漏标的条目应归入前一季，而不是独立成组。
        List<EpisodeSeasonGrouping.Season> seasons = EpisodeSeasonGrouping.group(episodes(
                "第一季 01", "特别篇", "第一季 03",
                "第二季 01", "第二季 02"));
        assertEquals(2, seasons.size());
        assertEquals(0, seasons.get(0).start());
        assertEquals(3, seasons.get(0).end());
    }

    @Test
    public void leadingUnmarkedEpisodesInheritFollowingSeason() {
        List<EpisodeSeasonGrouping.Season> seasons = EpisodeSeasonGrouping.group(episodes(
                "前情提要", "第一季 01",
                "第二季 01", "第二季 02"));
        assertEquals(2, seasons.size());
        // 第一个条目并入之后出现的「第一季」。
        assertEquals(0, seasons.get(0).start());
        assertEquals(2, seasons.get(0).end());
    }

    @Test
    public void nonContiguousSameSeasonBecomesSeparateGroups() {
        // 「第一季」在「第二季」之后再次出现时，必须单独成组，否则跳转会跳到错误位置。
        List<EpisodeSeasonGrouping.Season> seasons = EpisodeSeasonGrouping.group(episodes(
                "第一季 01", "第二季 01", "第一季 02"));
        assertEquals(3, seasons.size());
        assertEquals(0, seasons.get(0).start());
        assertEquals(1, seasons.get(0).end());
        assertEquals(1, seasons.get(1).start());
        assertEquals(2, seasons.get(1).end());
        assertEquals(2, seasons.get(2).start());
        assertEquals(3, seasons.get(2).end());
    }

    @Test
    public void outOfOrderSeasonsStillGroup() {
        List<EpisodeSeasonGrouping.Season> seasons = EpisodeSeasonGrouping.group(episodes(
                "第二季 01", "第一季 01", "第一季 02"));
        assertEquals(2, seasons.size());
        assertEquals("第二季", seasons.get(0).label());
        assertEquals("第一季", seasons.get(1).label());
    }

    @Test
    public void twoDigitSeasonsAreSupported() {
        List<EpisodeSeasonGrouping.Season> seasons = EpisodeSeasonGrouping.group(episodes(
                "第十季 01", "第十季 02", "第十一季 01"));
        assertEquals(2, seasons.size());
        assertEquals("第十季", seasons.get(0).label());
        assertEquals("第十一季", seasons.get(1).label());
    }

    @Test
    public void adjacentChineseTensAreParsed() {
        // 十一~十九 是中文数字里最容易解析错的一段。
        assertEquals(10, EpisodeSeasonGrouping.parseNumber("十"));
        assertEquals(11, EpisodeSeasonGrouping.parseNumber("十一"));
        assertEquals(19, EpisodeSeasonGrouping.parseNumber("十九"));
        assertEquals(20, EpisodeSeasonGrouping.parseNumber("二十"));
        assertEquals(21, EpisodeSeasonGrouping.parseNumber("二十一"));
    }

    @Test
    public void arabicAndMixedSeasonsAreParsed() {
        assertEquals(1, EpisodeSeasonGrouping.parseNumber("1"));
        assertEquals(1, EpisodeSeasonGrouping.parseNumber("一"));
        assertEquals(5, EpisodeSeasonGrouping.parseNumber("5"));
        assertEquals(2, EpisodeSeasonGrouping.parseNumber("二"));
        assertEquals(2, EpisodeSeasonGrouping.parseNumber("两"));
    }

    @Test
    public void invalidSeasonNumbersAreRejected() {
        assertEquals(-1, EpisodeSeasonGrouping.parseNumber(""));
        assertEquals(-1, EpisodeSeasonGrouping.parseNumber(null));
        // 超出合理季数范围，大概率是误匹配。
        assertEquals(-1, EpisodeSeasonGrouping.parseNumber("一千二百三十四"));
    }

    @Test
    public void indexOfFindsOwningGroup() {
        List<EpisodeSeasonGrouping.Season> seasons = EpisodeSeasonGrouping.group(episodes(
                "第一季 01", "第一季 02",
                "第二季 01", "第二季 02"));
        assertEquals(0, EpisodeSeasonGrouping.indexOf(seasons, 0));
        assertEquals(0, EpisodeSeasonGrouping.indexOf(seasons, 1));
        assertEquals(1, EpisodeSeasonGrouping.indexOf(seasons, 2));
        assertEquals(1, EpisodeSeasonGrouping.indexOf(seasons, 3));
        assertEquals(-1, EpisodeSeasonGrouping.indexOf(seasons, 4));
        assertEquals(-1, EpisodeSeasonGrouping.indexOf(null, 0));
    }

    @Test
    public void oversizedAndEmptyInputsAreSafe() {
        assertTrue(EpisodeSeasonGrouping.group(episodes()).isEmpty());
        assertTrue(EpisodeSeasonGrouping.group(episodes("第一季 01")).isEmpty());
        assertTrue(EpisodeSeasonGrouping.group(null).isEmpty());
    }

    @Test
    public void realWorldBracketedNamesGroupBySeason() {
        // 取自 EpisodeTitleCompactTest 的真实片名写法。
        List<EpisodeSeasonGrouping.Season> seasons = EpisodeSeasonGrouping.group(episodes(
                "[01.2005 牙狼 第一季] [光の影字幕組][牙狼GARO][01].mp4",
                "[01.2005 牙狼 第一季] [光の影字幕組][牙狼GARO][02].mp4",
                "[05.2011 牙狼 第二季 魔戒闪骑] [光の影字幕组][牙狼GARO][01].mp4",
                "[05.2011 牙狼 第二季 魔戒闪骑] [光の影字幕组][牙狼GARO][02].mp4"));
        assertEquals(2, seasons.size());
        assertEquals("第一季", seasons.get(0).label());
        assertEquals(0, seasons.get(0).start());
        assertEquals(2, seasons.get(0).end());
        assertEquals("第二季", seasons.get(1).label());
        assertEquals(2, seasons.get(1).start());
        assertEquals(4, seasons.get(1).end());
    }

    @Test
    public void labelsAndStartsAlignWithGroups() {
        List<EpisodeSeasonGrouping.Season> seasons = EpisodeSeasonGrouping.group(episodes(
                "第一季 01", "第一季 02",
                "第二季 01", "第二季 02"));
        assertEquals(List.of("第一季", "第二季"), EpisodeSeasonGrouping.labels(seasons));
        assertEquals(List.of(0, 2), EpisodeSeasonGrouping.starts(seasons));
    }

    @Test
    public void repeatedSeasonLabelsGetAnOrdinal() {
        // 同一季被切成两段时必须可区分，否则分组栏会出现两个「第一季」。
        List<EpisodeSeasonGrouping.Season> seasons = EpisodeSeasonGrouping.group(episodes(
                "第一季 01", "第二季 01", "第一季 02"));
        assertEquals(List.of("第一季 (1)", "第二季", "第一季 (2)"), EpisodeSeasonGrouping.labels(seasons));
        assertEquals(List.of(0, 1, 2), EpisodeSeasonGrouping.starts(seasons));
    }

    @Test
    public void labelsAndStartsTolerateEmptyInput() {
        assertTrue(EpisodeSeasonGrouping.labels(null).isEmpty());
        assertTrue(EpisodeSeasonGrouping.starts(null).isEmpty());
    }

    @Test
    public void seasonZeroIsTreatedAsUnmarkedAndInheritsNeighbour() {
        // S00 是极罕见的非标准写法。当作无季标记、并入相邻分组，避免为它单开一组；
        // 结果是只剩一个真实季（S01），因此不产生分季。
        assertTrue(EpisodeSeasonGrouping.group(episodes("Show S00E01", "Show S01E01")).isEmpty());
    }
}
