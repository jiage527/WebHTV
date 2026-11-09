package com.fongmi.android.tv.setting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放队列的顺序与循环语义验证。
 *
 * <p>重点在<b>下标维护</b>：排序、删除、插队都会改变「当前项」的位置，只要有一处算错，
 * 用户按下一集就会跳到别的剧集，而且很难复现。这些都是纯逻辑，直接在 JVM 里断言。
 */
public class PlayQueueTest {

    private static PlayQueue queue(String... urls) {
        PlayQueue queue = PlayQueue.empty();
        for (int i = 0; i < urls.length; i++) queue.add(new PlayQueue.Entry("第 " + (i + 1) + " 集", urls[i]));
        return queue;
    }

    private static List<String> urls(PlayQueue queue) {
        List<String> result = new ArrayList<>();
        for (PlayQueue.Entry entry : queue.entries()) result.add(entry.url);
        return result;
    }

    @Test
    public void addAppendsAndIgnoresDuplicates() {
        PlayQueue queue = queue("a", "b");
        assertEquals(List.of("a", "b"), urls(queue));
        assertFalse(queue.add(new PlayQueue.Entry("重复", "a")));
        assertEquals(2, queue.size());
    }

    @Test
    public void entriesWithoutUrlAreRejected() {
        PlayQueue queue = PlayQueue.empty();
        assertFalse(queue.add(new PlayQueue.Entry("没有地址", "")));
        assertFalse(queue.add(new PlayQueue.Entry("只有空白", "   ")));
        assertFalse(queue.add(null));
        assertTrue(queue.isEmpty());
    }

    @Test
    public void insertNextPutsEntryRightAfterCurrent() {
        PlayQueue queue = queue("a", "b", "c");
        queue.setCurrent("a");
        assertTrue(queue.insertNext(new PlayQueue.Entry("插队", "z")));
        assertEquals(List.of("a", "z", "b", "c"), urls(queue));
    }

    @Test
    public void insertNextMovesAnExistingEntryInsteadOfDuplicating() {
        // 已经在队列里的条目再插队应当是「移动」，否则会出现两条一样的而且下标错乱。
        PlayQueue queue = queue("a", "b", "c");
        queue.setCurrent("a");
        assertTrue(queue.insertNext(new PlayQueue.Entry("B", "b")));
        assertEquals(List.of("a", "b", "c"), urls(queue));
        assertEquals(3, queue.size());
    }

    @Test
    public void insertNextWithUnknownCurrentGoesFirst() {
        PlayQueue queue = queue("a", "b");
        assertTrue(queue.insertNext(new PlayQueue.Entry("插队", "z")));
        assertEquals(List.of("z", "a", "b"), urls(queue));
    }

    @Test
    public void moveKeepsCurrentOnTheSameEntry() {
        PlayQueue queue = queue("a", "b", "c");
        queue.setCurrent("b");
        assertTrue(queue.move(1, 2));
        assertEquals(List.of("a", "c", "b"), urls(queue));
        // 「当前」必须仍是 b，只是它换了位置。
        assertEquals(2, queue.currentIndex());
        assertEquals("b", queue.currentEntry().url);
    }

    @Test
    public void moveShiftsCurrentWhenAnotherEntryCrossesIt() {
        PlayQueue queue = queue("a", "b", "c");
        queue.setCurrent("b");
        // 把 a 拖到 b 后面，b 的位置前移一位，当前项必须跟着走。
        assertTrue(queue.move(0, 1));
        assertEquals(List.of("b", "a", "c"), urls(queue));
        assertEquals(0, queue.currentIndex());
        assertEquals("b", queue.currentEntry().url);
    }

    @Test
    public void moveRejectsOutOfRangeAndNoOps() {
        PlayQueue queue = queue("a", "b");
        assertFalse(queue.move(-1, 0));
        assertFalse(queue.move(0, 5));
        assertFalse(queue.move(1, 1));
        assertEquals(List.of("a", "b"), urls(queue));
    }

    @Test
    public void removeKeepsCurrentOnTheFollowingEntry() {
        PlayQueue queue = queue("a", "b", "c");
        queue.setCurrent("b");
        assertTrue(queue.remove(1));
        assertEquals(List.of("a", "c"), urls(queue));
        // 删掉当前项后应当落到原本紧随其后的那一项。
        assertEquals(1, queue.currentIndex());
        assertEquals("c", queue.currentEntry().url);
    }

    @Test
    public void removeBeforeCurrentShiftsCurrentBack() {
        PlayQueue queue = queue("a", "b", "c");
        queue.setCurrent("c");
        assertTrue(queue.remove(0));
        assertEquals(1, queue.currentIndex());
        assertEquals("c", queue.currentEntry().url);
    }

    @Test
    public void removingTheLastEntryLeavesNoCurrent() {
        PlayQueue queue = queue("a");
        queue.setCurrent("a");
        queue.remove(0);
        assertTrue(queue.isEmpty());
        assertEquals(-1, queue.currentIndex());
        assertNull(queue.currentEntry());
    }

    @Test
    public void unknownUrlLeavesCurrentUnsetRatherThanPointingAtTheFirstEntry() {
        PlayQueue queue = queue("a", "b");
        queue.setCurrent("不存在");
        assertEquals(-1, queue.currentIndex());
        assertNull(queue.currentEntry());
    }

    @Test
    public void nextIndexFollowsOffModeAndStopsAtTheEnd() {
        PlayQueue queue = queue("a", "b");
        queue.setCurrent("a");
        assertEquals(1, queue.nextIndex());
        queue.setCurrent("b");
        // 不循环时队尾没有下一项，播放器据此结束连播。
        assertEquals(-1, queue.nextIndex());
        assertTrue(queue.isLast());
    }

    @Test
    public void nextIndexWrapsAroundInAllMode() {
        PlayQueue queue = queue("a", "b");
        queue.setMode(PlayQueue.MODE_ALL);
        queue.setCurrent("b");
        assertEquals(0, queue.nextIndex());
        assertEquals("a", queue.nextEntry().url);
    }

    @Test
    public void nextIndexStaysOnCurrentInOneMode() {
        PlayQueue queue = queue("a", "b");
        queue.setMode(PlayQueue.MODE_ONE);
        queue.setCurrent("a");
        assertEquals(0, queue.nextIndex());
        assertEquals("a", queue.nextEntry().url);
    }

    @Test
    public void nextIndexStartsFromTheHeadWhenCurrentIsUnknown() {
        // 刚导入队列就按下一集，应当从第一项开始播而不是毫无反应。
        PlayQueue queue = queue("a", "b");
        assertEquals(0, queue.nextIndex());
        assertEquals(0, queue.prevIndex());
    }

    @Test
    public void prevIndexWrapsOnlyInAllMode() {
        PlayQueue queue = queue("a", "b", "c");
        queue.setCurrent("a");
        assertEquals(-1, queue.prevIndex());
        queue.setMode(PlayQueue.MODE_ALL);
        assertEquals(2, queue.prevIndex());
    }

    @Test
    public void modeNormalizationRejectsGarbage() {
        PlayQueue queue = queue("a");
        queue.setMode("nonsense");
        assertEquals(PlayQueue.MODE_OFF, queue.mode());
        queue.setMode(null);
        assertEquals(PlayQueue.MODE_OFF, queue.mode());
        queue.setMode(PlayQueue.MODE_ALL);
        assertEquals(PlayQueue.MODE_ALL, queue.mode());
    }

    @Test
    public void cycleModeRotatesThroughAllThreeStates() {
        assertEquals(PlayQueue.MODE_ALL, PlayQueue.cycleMode(PlayQueue.MODE_OFF));
        assertEquals(PlayQueue.MODE_ONE, PlayQueue.cycleMode(PlayQueue.MODE_ALL));
        assertEquals(PlayQueue.MODE_OFF, PlayQueue.cycleMode(PlayQueue.MODE_ONE));
        assertEquals(PlayQueue.MODE_ALL, PlayQueue.cycleMode("garbage"));
    }

    @Test
    public void describeModeReturnsShortButtonLabels() {
        assertEquals("OFF", PlayQueue.describeMode(PlayQueue.MODE_OFF));
        assertEquals("ALL", PlayQueue.describeMode(PlayQueue.MODE_ALL));
        assertEquals("ONE", PlayQueue.describeMode(PlayQueue.MODE_ONE));
    }

    @Test
    public void jsonRoundTripKeepsEntriesModeAndCurrent() {
        PlayQueue queue = queue("a", "b", "c");
        queue.setMode(PlayQueue.MODE_ALL);
        queue.setCurrent("b");
        PlayQueue parsed = PlayQueue.fromJson(queue.toJson());
        assertEquals(List.of("a", "b", "c"), urls(parsed));
        assertEquals(PlayQueue.MODE_ALL, parsed.mode());
        // 丢掉当前下标会让重启后从队首重播，所以必须一起恢复。
        assertEquals(1, parsed.currentIndex());
        assertEquals("b", parsed.currentEntry().url);
    }

    @Test
    public void corruptJsonDegradesToAnEmptyQueue() {
        assertTrue(PlayQueue.fromJson(null).isEmpty());
        assertTrue(PlayQueue.fromJson("").isEmpty());
        assertTrue(PlayQueue.fromJson("   ").isEmpty());
        assertTrue(PlayQueue.fromJson("{").isEmpty());
        assertTrue(PlayQueue.fromJson("null").isEmpty());
        assertTrue(PlayQueue.fromJson("\"nope\"").isEmpty());
    }

    @Test
    public void restoringDeduplicatesAndDropsInvalidEntries() {
        PlayQueue parsed = PlayQueue.fromJson("{\"entries\":[{\"url\":\"a\"},{\"url\":\"a\"},{\"url\":\"\"}],\"mode\":\"all\",\"current\":5}");
        assertEquals(List.of("a"), urls(parsed));
        // 越界下标必须被收敛，否则读取当前项会崩。
        assertEquals(-1, parsed.currentIndex());
    }

    @Test
    public void sizeIsCapped() {
        PlayQueue queue = PlayQueue.empty();
        for (int i = 0; i < PlayQueue.MAX_ENTRIES + 20; i++) queue.add(new PlayQueue.Entry("第 " + i + " 集", "u" + i));
        assertEquals(PlayQueue.MAX_ENTRIES, queue.size());
        assertFalse(queue.add(new PlayQueue.Entry("超出", "overflow")));
        assertFalse(queue.insertNext(new PlayQueue.Entry("超出", "overflow2")));
    }

    @Test
    public void longNamesAreTruncatedAndTrimmed() {
        PlayQueue queue = PlayQueue.empty();
        queue.add(new PlayQueue.Entry("  " + "长".repeat(200) + "  ", "  a  "));
        PlayQueue.Entry entry = queue.entryAt(0);
        assertEquals("a", entry.url);
        assertTrue(entry.name.length() <= 80);
    }

    @Test
    public void displayNameFallsBackToTheUrl() {
        PlayQueue queue = PlayQueue.empty();
        queue.add(new PlayQueue.Entry("", "https://example.com/a.m3u8"));
        assertEquals("https://example.com/a.m3u8", queue.entryAt(0).displayName());
    }

    @Test
    public void clearResetsEverything() {
        PlayQueue queue = queue("a", "b");
        queue.setMode(PlayQueue.MODE_ALL);
        queue.setCurrent("a");
        queue.clear();
        assertTrue(queue.isEmpty());
        assertEquals(-1, queue.currentIndex());
        // 循环模式是用户的播放偏好，清空队列不应顺带把它重置。
        assertEquals(PlayQueue.MODE_ALL, queue.mode());
    }

    @Test
    public void ofBuildsAQueueFromAnEpisodeList() {
        List<PlayQueue.Entry> source = List.of(new PlayQueue.Entry("第 1 集", "a"), new PlayQueue.Entry("第 2 集", "b"));
        PlayQueue queue = PlayQueue.of(source);
        assertEquals(2, queue.size());
        assertEquals(List.of("a", "b"), urls(queue));
    }

    @Test
    public void entriesViewIsReadOnly() {
        PlayQueue queue = queue("a");
        try {
            queue.entries().add(new PlayQueue.Entry("x", "x"));
            throw new AssertionError("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // 外部只能通过队列方法改动，避免绕过下标维护。
        }
    }

    @Test
    public void singleEntryQueueDoesNotReplayItselfWhenCurrentIsKnown() {
        // 只有一集且不循环时必须结束连播；若「当前项」未知而回落到队首，
        // 就会把这一集反复重播。
        PlayQueue queue = queue("a");
        queue.setCurrent("a");
        assertEquals(-1, queue.nextIndex());
        assertNull(queue.nextEntry());
    }

    @Test
    public void containsMatchesByUrl() {
        PlayQueue queue = queue("a", "b");
        assertTrue(queue.contains("b"));
        assertFalse(queue.contains("z"));
        assertFalse(queue.contains(null));
        assertFalse(queue.contains(""));
    }

    @Test
    public void removeByUrlUsesTheSameIdentityAsLookup() {
        PlayQueue queue = queue("a", "b");
        assertTrue(queue.removeByUrl("a"));
        assertEquals(List.of("b"), urls(queue));
        assertFalse(queue.removeByUrl("a"));
    }
}
