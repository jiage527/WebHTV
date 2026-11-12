package com.fongmi.android.tv.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.fongmi.android.tv.download.OfflineDownloadStatus.OfflineDownloadRow;

/**
 * 重点盯三件事：
 * <ul>
 *   <li>Media3 的三个裸 int 要收敛成用户看得懂的一档状态——「出错停了」和「用户暂停了」
 *       都走 {@code STATE_STOPPED}，前者要能让用户重试，后者只需点继续。</li>
 *   <li>总长未知时不能返回 0%，否则界面永远停在 0% 像是卡死；要返回 -1 让面板显示不确定进度。</li>
 *   <li>硬编码的状态常量要跟随包 AAR 一致——写错了整张面板的状态都会错位，
 *       而这类错误编译期查不出来。</li>
 * </ul>
 *
 * <p>纯 JVM，不依赖 Android。
 */
public class OfflineDownloadStatusTest {

    /** 这组数值取自随包 media3-exoplayer-1.11.0-alpha01-fongmi 的 javap -constants；变了就该在这里报错。 */
    @Test
    public void stateConstantsMatchTheBundledMedia3() {
        assertEquals(0, OfflineDownloadStatus.STATE_QUEUED);
        assertEquals(1, OfflineDownloadStatus.STATE_STOPPED);
        assertEquals(2, OfflineDownloadStatus.STATE_DOWNLOADING);
        assertEquals(3, OfflineDownloadStatus.STATE_COMPLETED);
        assertEquals(4, OfflineDownloadStatus.STATE_FAILED);
        assertEquals(5, OfflineDownloadStatus.STATE_REMOVING);
        assertEquals(7, OfflineDownloadStatus.STATE_RESTARTING);
    }

    @Test
    public void mapsTheStraightforwardStates() {
        assertEquals(OfflineDownloadStatus.Phase.DOWNLOADING, OfflineDownloadStatus.phase(2, 0, 0));
        assertEquals(OfflineDownloadStatus.Phase.QUEUED, OfflineDownloadStatus.phase(0, 0, 0));
        assertEquals(OfflineDownloadStatus.Phase.COMPLETED, OfflineDownloadStatus.phase(3, 0, 0));
        assertEquals(OfflineDownloadStatus.Phase.FAILED, OfflineDownloadStatus.phase(4, 0, 1));
        assertEquals(OfflineDownloadStatus.Phase.REMOVING, OfflineDownloadStatus.phase(5, 0, 0));
        assertEquals(OfflineDownloadStatus.Phase.RESTARTING, OfflineDownloadStatus.phase(7, 0, 0));
    }

    /** 停止态要分得开：带失败原因的当失败（可重试），否则当暂停（可继续）。 */
    @Test
    public void stoppedSplitsIntoPausedAndFailed() {
        assertEquals(OfflineDownloadStatus.Phase.PAUSED, OfflineDownloadStatus.phase(1, 0, 0));
        assertEquals(OfflineDownloadStatus.Phase.PAUSED, OfflineDownloadStatus.phase(1, 2, 0));
        assertEquals(OfflineDownloadStatus.Phase.FAILED, OfflineDownloadStatus.phase(1, 0, 1));
        assertEquals(OfflineDownloadStatus.Phase.FAILED, OfflineDownloadStatus.phase(1, 2, 1));
    }

    /** 排队态却带着失败原因 = 上次失败后又被排回来，要显示成失败而不是「正在排队」。 */
    @Test
    public void queuedWithFailureReasonIsTreatedAsFailed() {
        assertEquals(OfflineDownloadStatus.Phase.FAILED, OfflineDownloadStatus.phase(0, 0, 1));
        assertEquals(OfflineDownloadStatus.Phase.QUEUED, OfflineDownloadStatus.phase(0, 0, 0));
    }

    /** 认不出的状态（未来 Media3 加了新值）不能猜，一律 UNKNOWN。 */
    @Test
    public void unknownStatesFallBackToUnknown() {
        assertEquals(OfflineDownloadStatus.Phase.UNKNOWN, OfflineDownloadStatus.phase(6, 0, 0));
        assertEquals(OfflineDownloadStatus.Phase.UNKNOWN, OfflineDownloadStatus.phase(99, 0, 0));
        assertEquals(OfflineDownloadStatus.Phase.UNKNOWN, OfflineDownloadStatus.phase(-1, 0, 0));
    }

    @Test
    public void progressIsShownOnlyForMovingStates() {
        assertTrue(OfflineDownloadStatus.showsProgress(OfflineDownloadStatus.Phase.DOWNLOADING));
        assertTrue(OfflineDownloadStatus.showsProgress(OfflineDownloadStatus.Phase.QUEUED));
        assertTrue(OfflineDownloadStatus.showsProgress(OfflineDownloadStatus.Phase.PAUSED));
        assertFalse(OfflineDownloadStatus.showsProgress(OfflineDownloadStatus.Phase.COMPLETED));
        assertFalse(OfflineDownloadStatus.showsProgress(OfflineDownloadStatus.Phase.FAILED));
        assertFalse(OfflineDownloadStatus.showsProgress(OfflineDownloadStatus.Phase.UNKNOWN));
    }

    /** 正在删的不再重复删；其余都能删。 */
    @Test
    public void onlyRemovingIsNotRemovable() {
        assertFalse(OfflineDownloadStatus.removable(OfflineDownloadStatus.Phase.REMOVING));
        assertTrue(OfflineDownloadStatus.removable(OfflineDownloadStatus.Phase.DOWNLOADING));
        assertTrue(OfflineDownloadStatus.removable(OfflineDownloadStatus.Phase.COMPLETED));
        assertTrue(OfflineDownloadStatus.removable(OfflineDownloadStatus.Phase.FAILED));
    }

    /** 只有下完的能播：下了一半点开，播放器会一边读缓存一边回源，用户以为在看离线。 */
    @Test
    public void onlyCompletedIsPlayable() {
        assertTrue(OfflineDownloadStatus.playable(OfflineDownloadStatus.Phase.COMPLETED));
        assertFalse(OfflineDownloadStatus.playable(OfflineDownloadStatus.Phase.DOWNLOADING));
        assertFalse(OfflineDownloadStatus.playable(OfflineDownloadStatus.Phase.PAUSED));
        assertFalse(OfflineDownloadStatus.playable(OfflineDownloadStatus.Phase.FAILED));
    }

    /** 总长未知或已下载字节为 0 都要返回 -1，让界面显示不确定进度而不是 0%。 */
    @Test
    public void percentReturnsMinusOneWhenUnknown() {
        assertEquals(-1, OfflineDownloadStatus.percent(0, 100));
        assertEquals(-1, OfflineDownloadStatus.percent(10, 0));
        assertEquals(-1, OfflineDownloadStatus.percent(10, -1));
        assertEquals(-1, OfflineDownloadStatus.percent(-5, 100));
    }

    @Test
    public void percentClampsToHundred() {
        assertEquals(50, OfflineDownloadStatus.percent(50, 100));
        assertEquals(33, OfflineDownloadStatus.percent(1, 3));
        assertEquals(100, OfflineDownloadStatus.percent(100, 100));
        assertEquals(100, OfflineDownloadStatus.percent(200, 100));
    }

    /** 体量文字：B / KB / MB 一位小数 / GB 两位小数。 */
    @Test
    public void sizeTextSwitchesUnits() {
        assertEquals("0 B", OfflineDownloadStatus.sizeText(0));
        assertEquals("512 B", OfflineDownloadStatus.sizeText(512));
        assertEquals("1 KB", OfflineDownloadStatus.sizeText(1024));
        assertEquals("1.5 MB", OfflineDownloadStatus.sizeText(1024L * 1024 + 512 * 1024));
        assertEquals("2.00 GB", OfflineDownloadStatus.sizeText(2L * 1024 * 1024 * 1024));
    }

    /** 列表顺序：正在下 → 排队 → 暂停 → 已完成 → 失败 → 重启中 → 移除中 → 未知。 */
    @Test
    public void sortPutsMovingRowsFirst() {
        List<OfflineDownloadRow> rows = new ArrayList<>(Arrays.asList(
                row("e", OfflineDownloadStatus.Phase.FAILED, 100),
                row("d", OfflineDownloadStatus.Phase.COMPLETED, 100),
                row("c", OfflineDownloadStatus.Phase.PAUSED, 100),
                row("b", OfflineDownloadStatus.Phase.QUEUED, 100),
                row("a", OfflineDownloadStatus.Phase.DOWNLOADING, 100)));
        OfflineDownloadStatus.sort(rows);
        assertEquals(Arrays.asList("a", "b", "c", "d", "e"), ids(rows));
    }

    /** 同档内新的在前：刚点的那一条要在顶上。 */
    @Test
    public void sortPutsNewestFirstWithinSamePhase() {
        List<OfflineDownloadRow> rows = new ArrayList<>(Arrays.asList(
                row("old", OfflineDownloadStatus.Phase.COMPLETED, 100),
                row("new", OfflineDownloadStatus.Phase.COMPLETED, 300),
                row("mid", OfflineDownloadStatus.Phase.COMPLETED, 200)));
        OfflineDownloadStatus.sort(rows);
        assertEquals(Arrays.asList("new", "mid", "old"), ids(rows));
    }

    /** 同档同时刻时按 id 兜底，保证顺序稳定（否则每次刷新列表都在跳）。 */
    @Test
    public void sortIsStableWhenPhaseAndTimeMatch() {
        List<OfflineDownloadRow> rows = new ArrayList<>(Arrays.asList(
                row("b", OfflineDownloadStatus.Phase.COMPLETED, 100),
                row("a", OfflineDownloadStatus.Phase.COMPLETED, 100)));
        OfflineDownloadStatus.sort(rows);
        assertEquals(Arrays.asList("a", "b"), ids(rows));
    }

    /** 空表、单条、null 都不能抛。 */
    @Test
    public void sortHandlesDegenerateInput() {
        List<OfflineDownloadRow> empty = new ArrayList<>();
        OfflineDownloadStatus.sort(empty);
        assertTrue(empty.isEmpty());
        OfflineDownloadStatus.sort(null);
        List<OfflineDownloadRow> single = new ArrayList<>(List.of(row("a", OfflineDownloadStatus.Phase.QUEUED, 1)));
        OfflineDownloadStatus.sort(single);
        assertEquals(List.of("a"), ids(single));
    }

    /** sorted 返回副本，不改入参（面板传进来的可能是自己的列表）。 */
    @Test
    public void sortedReturnsACopyAndKeepsInput() {
        List<OfflineDownloadRow> rows = new ArrayList<>(Arrays.asList(
                row("b", OfflineDownloadStatus.Phase.FAILED, 100),
                row("a", OfflineDownloadStatus.Phase.DOWNLOADING, 100)));
        List<OfflineDownloadRow> result = OfflineDownloadStatus.sorted(rows);
        assertEquals(Arrays.asList("a", "b"), ids(result));
        assertEquals(Arrays.asList("b", "a"), ids(rows));
        assertTrue(OfflineDownloadStatus.sorted(null).isEmpty());
    }

    /** 未知相位排在最后，不是随便插在中间。 */
    @Test
    public void unknownPhaseSortsLast() {
        List<OfflineDownloadRow> rows = new ArrayList<>(Arrays.asList(
                row("u", OfflineDownloadStatus.Phase.UNKNOWN, 999),
                row("r", OfflineDownloadStatus.Phase.REMOVING, 1)));
        OfflineDownloadStatus.sort(rows);
        assertEquals(Arrays.asList("r", "u"), ids(rows));
    }

    private static OfflineDownloadRow row(String id, OfflineDownloadStatus.Phase phase, long createdAt) {
        return new OfflineDownloadRow() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public OfflineDownloadStatus.Phase phase() {
                return phase;
            }

            @Override
            public long createdAt() {
                return createdAt;
            }
        };
    }

    private static List<String> ids(List<OfflineDownloadRow> rows) {
        List<String> result = new ArrayList<>();
        for (OfflineDownloadRow row : rows) result.add(row.id());
        return result;
    }
}
