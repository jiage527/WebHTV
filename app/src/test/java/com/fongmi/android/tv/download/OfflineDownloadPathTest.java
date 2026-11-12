package com.fongmi.android.tv.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * 盯的是「文件路径那一行显示什么」。
 *
 * <p>两种真实情形必须分清：MP4 直链在缓存里是一个文件，可以给出完整路径；HLS 是几十个分片、
 * 被 {@code SimpleCache} 打散到若干编号目录里，这时给出「共同目录」才是诚实的——把其中任意
 * 一个分片的路径显示出来会让人以为那是一个能直接打开的完整视频。
 *
 * <p>纯 JVM，不依赖 Android。
 */
public class OfflineDownloadPathTest {

    /** 单个文件：显示它自己的完整路径。 */
    @Test
    public void singleFileShowsItsOwnPath() {
        OfflineDownloadPath.Location location = OfflineDownloadPath.locate(
                List.of("/data/user/0/com.fongmi.android.tv/files/offline-downloads/0/od-abc.0.42907426.v1.exo"),
                "/data/user/0/com.fongmi.android.tv/files/offline-downloads");
        assertTrue(location.hasPath());
        assertEquals(1, location.fileCount());
        assertEquals("/data/user/0/com.fongmi.android.tv/files/offline-downloads/0/od-abc.0.42907426.v1.exo", location.path());
        assertFalse(location.needsCount());
    }

    /** 多个分片：显示共同目录，并告诉用户一共几个文件。 */
    @Test
    public void multipleFilesShowTheCommonDirectory() {
        OfflineDownloadPath.Location location = OfflineDownloadPath.locate(Arrays.asList(
                "/files/offline-downloads/0/od-abc.0.5242880.v1.exo",
                "/files/offline-downloads/1/od-abc.5242880.5242880.v1.exo",
                "/files/offline-downloads/9/od-abc.47185920.964386.v1.exo"),
                "/files/offline-downloads");
        assertEquals("/files/offline-downloads", location.path());
        assertEquals(3, location.fileCount());
        assertTrue(location.needsCount());
    }

    /**
     * 共同目录要按「目录层级」算，不是按字符前缀硬切。
     *
     * <p>{@code /a/bc} 与 {@code /a/bd} 在字符上共享 {@code /a/b}，但 {@code bc}/{@code bd}
     * 是两个不同的目录名，共同目录只能是 {@code /a}。用 {@code startsWith} 会给出
     * {@code /a/b} —— 一个不存在的路径。
     */
    @Test
    public void commonDirectoryComparesPathSegmentsNotCharacters() {
        assertEquals("/a", OfflineDownloadPath.commonDirectory(List.of("/a/bc/x", "/a/bd/y")));
        assertEquals("/a/b", OfflineDownloadPath.commonDirectory(List.of("/a/b/x", "/a/b/y")));
    }

    /** 一个文件都没有时退回给定目录（例如刚入队、缓存里还什么都没有）。 */
    @Test
    public void emptyInputFallsBackToTheGivenDirectory() {
        OfflineDownloadPath.Location location = OfflineDownloadPath.locate(Collections.emptyList(), "/files/offline-downloads/");
        assertEquals("/files/offline-downloads", location.path());
        assertEquals(0, location.fileCount());
        assertFalse(location.needsCount());
    }

    /** 没有文件、也没给退回目录：不显示这一行，而不是显示一个空串路径。 */
    @Test
    public void emptyInputWithoutFallbackHasNoPath() {
        assertFalse(OfflineDownloadPath.locate(null, null).hasPath());
        assertFalse(OfflineDownloadPath.locate(Collections.emptyList(), "  ").hasPath());
    }

    /** 重复路径要当成一个文件，否则「（3 个文件）」会虚报。 */
    @Test
    public void duplicatePathsCountOnce() {
        OfflineDownloadPath.Location location = OfflineDownloadPath.locate(Arrays.asList(
                "/files/offline-downloads/0/od-abc.0.100.v1.exo",
                "/files/offline-downloads/0/od-abc.0.100.v1.exo"),
                "/files/offline-downloads");
        assertEquals(1, location.fileCount());
        assertEquals("/files/offline-downloads/0/od-abc.0.100.v1.exo", location.path());
    }

    /** 结尾斜杠不能让共同目录算出 {@code dir//name} 这种双斜杠。 */
    @Test
    public void trailingSlashesAreTrimmedBeforeComparing() {
        assertEquals("/a/b", OfflineDownloadPath.commonDirectory(List.of("/a/b/x/", "/a/b/y")));
        assertEquals("", OfflineDownloadPath.directoryOf("relative-file.exo"));
        assertEquals("/a/b", OfflineDownloadPath.directoryOf("/a/b/c.exo"));
    }

    /** 单个文件但没目录部分（相对名）：仍要给出这个文件本身，而不是空。 */
    @Test
    public void singleFileWithoutDirectoryStillShowsTheFile() {
        OfflineDownloadPath.Location location = OfflineDownloadPath.locate(List.of("od-abc.0.100.v1.exo"), "/files");
        assertEquals("od-abc.0.100.v1.exo", location.path());
    }
}
