package com.fongmi.android.tv.netdisk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 重点盯三件事：远端目录不许逃出账号根、云端列表的排序与裁剪不许删错份、配额读不到时不许误拦。
 *
 * <p>这三类错误在真机上都不好复现 —— 传到错误的目录、把最新的一份当旧的删掉、因为配额读不到
 * 就拒绝备份 —— 所以把它们钉在纯逻辑测试里。
 */
public class WebDavBackupStoreTest {

    // ------------------------------------------------------------------ 目录规整

    @Test
    public void normalizeKeepsNestedFolder() {
        assertEquals("a/b", WebDavBackupStore.normalizeDir("a/b"));
    }

    @Test
    public void normalizeUnifiesSeparatorsAndDropsEmptySegments() {
        assertEquals("a/b/c", WebDavBackupStore.normalizeDir("a//b\\c/"));
    }

    @Test
    public void normalizeDropsDotSegment() {
        assertEquals("a/b", WebDavBackupStore.normalizeDir("./a/./b"));
    }

    @Test
    public void normalizeNeverEscapesAccountRoot() {
        // 这是防「备份传到账号根之外的兄弟目录」的关键一步。
        assertEquals("c", WebDavBackupStore.normalizeDir("../../../c"));
        assertEquals("", WebDavBackupStore.normalizeDir(".."));
    }

    @Test
    public void normalizeHandlesNull() {
        assertEquals("", WebDavBackupStore.normalizeDir(null));
    }

    @Test
    public void effectiveDirFallsBackToDefault() {
        assertEquals(WebDavBackupStore.DEFAULT_DIR, WebDavBackupStore.effectiveDir(null));
        assertEquals(WebDavBackupStore.DEFAULT_DIR, WebDavBackupStore.effectiveDir("   "));
        assertEquals(WebDavBackupStore.DEFAULT_DIR, WebDavBackupStore.effectiveDir(".."));
        assertEquals("mine", WebDavBackupStore.effectiveDir("mine"));
    }

    @Test
    public void remotePathJoinsDirAndName() {
        assertEquals("WebHTV-Backups/bak-20261112-0830.zip",
                WebDavBackupStore.remotePath(null, "bak-20261112-0830.zip"));
        assertEquals("mine/bak-20261112-0830.zip",
                WebDavBackupStore.remotePath("/mine/", "bak-20261112-0830.zip"));
    }

    // ------------------------------------------------------------------ 名字识别

    @Test
    public void recognizesBackupNames() {
        assertTrue(WebDavBackupStore.isBackupName("bak-20261112-0830.zip"));
        assertTrue(WebDavBackupStore.isBackupName("webhtv-backup-20261112-0830.zip"));
        assertFalse(WebDavBackupStore.isBackupName("bak-20261112-0830.json"));
        assertFalse(WebDavBackupStore.isBackupName("movie.mp4"));
        assertFalse(WebDavBackupStore.isBackupName(null));
    }

    // ------------------------------------------------------------------ 排序与裁剪

    @Test
    public void backupsSortsNewestFirstAndFiltersOthers() {
        List<String> names = Arrays.asList(
                "bak-20261110-0900.zip",
                "movie.mp4",
                "bak-20261112-0830.zip",
                "bak-20261111-1200.json",
                "bak-20261111-1200.zip");
        assertEquals(Arrays.asList(
                        "bak-20261112-0830.zip",
                        "bak-20261111-1200.zip",
                        "bak-20261110-0900.zip"),
                WebDavBackupStore.backups(names));
    }

    @Test
    public void backupsHandlesNullAndEmpty() {
        assertEquals(new ArrayList<String>(), WebDavBackupStore.backups(null));
        assertEquals(new ArrayList<String>(), WebDavBackupStore.backups(new ArrayList<>()));
    }

    @Test
    public void backupsDeduplicates() {
        List<String> names = Arrays.asList("bak-20261112-0830.zip", "bak-20261112-0830.zip");
        assertEquals(1, WebDavBackupStore.backups(names).size());
    }

    @Test
    public void staleKeepsTheNewestAndReturnsTheRest() {
        // 列表是「新 → 旧」，keep=1 时必须留下 c，删掉 b 与 a。
        List<String> sorted = Arrays.asList("c", "b", "a");
        assertEquals(Arrays.asList("b", "a"), WebDavBackupStore.stale(sorted, 1));
    }

    @Test
    public void staleDeletesNothingWhenKeepIsNotPositive() {
        List<String> sorted = Arrays.asList("c", "b", "a");
        // 配置写错（keep=0）不该把云端清空。
        assertEquals(new ArrayList<String>(), WebDavBackupStore.stale(sorted, 0));
        assertEquals(new ArrayList<String>(), WebDavBackupStore.stale(sorted, -1));
        assertEquals(new ArrayList<String>(), WebDavBackupStore.stale(null, 3));
    }

    @Test
    public void staleKeepsEverythingWhenWithinLimit() {
        assertEquals(new ArrayList<String>(), WebDavBackupStore.stale(Arrays.asList("b", "a"), WebDavBackupStore.DEFAULT_KEEP));
    }

    // ------------------------------------------------------------------ 显示与配额

    @Test
    public void displayNameRewritesStamp() {
        assertEquals("2026-11-12 08:30", WebDavBackupStore.displayName("bak-20261112-0830.zip"));
    }

    @Test
    public void displayNameFallsBackToRawName() {
        assertEquals("whatever.zip", WebDavBackupStore.displayName("whatever.zip"));
        assertEquals("", WebDavBackupStore.displayName(null));
    }

    @Test
    public void fitsQuotaLeavesHeadroom() {
        assertTrue(WebDavBackupStore.fitsQuota(1000, 100));
        // 刚好放得下但在 10% 余量之内，按放不下处理。
        assertFalse(WebDavBackupStore.fitsQuota(105, 100));
        assertFalse(WebDavBackupStore.fitsQuota(50, 100));
    }

    @Test
    public void fitsQuotaNeverBlocksWhenQuotaUnknown() {
        // 很多服务端不实现 quota，读不到时不能误报「空间不足」。
        assertTrue(WebDavBackupStore.fitsQuota(-1, 100));
        assertTrue(WebDavBackupStore.fitsQuota(0, 100));
        assertTrue(WebDavBackupStore.fitsQuota(1000, 0));
    }
}
