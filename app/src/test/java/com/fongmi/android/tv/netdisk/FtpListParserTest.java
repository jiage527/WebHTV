package com.fongmi.android.tv.netdisk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * {@link FtpListParser} 的目录列举解析。
 *
 * <p>FTP 没有统一的列举格式，{@code MLSD} 与 {@code LIST} 的输出差别很大，而
 * {@code LIST} 的格式还由服务器实现决定（Unix 与 DOS 两套写法）。这些用例盯的是
 * 「两种都要认出来」，以及文件名含空格时不能被截断。
 */
public class FtpListParserTest {

    // ------------------------------------------------------------------ MLSD

    @Test
    public void parsesMlsdFile() {
        NetDiskEntry entry = FtpListParser.parseMlsd("", "type=file;size=1234567;modify=20240110123456; movie.mp4");
        assertEquals("movie.mp4", entry.getName());
        assertEquals(1234567L, entry.getSize());
        assertFalse(entry.isDirectory());
        assertTrue(entry.isPlayable());
    }

    @Test
    public void parsesMlsdDirectory() {
        NetDiskEntry entry = FtpListParser.parseMlsd("", "type=dir;modify=20240110123456; Movies");
        assertEquals("Movies", entry.getName());
        assertTrue(entry.isDirectory());
        assertTrue(entry.getPath().equals("Movies"));
    }

    @Test
    public void mlsdCdirAndPdirAreDropped() {
        // cdir/pdir 是当前目录与父目录，列出来会让用户看到两个多余项。
        assertNull(FtpListParser.parseMlsd("", "type=cdir;modify=20240110123456; ."));
        assertNull(FtpListParser.parseMlsd("", "type=pdir;modify=20240110123456; .."));
    }

    @Test
    public void mlsdRequiresTypeFact() {
        // 没有 type 行无法判断目录还是文件，不能猜。
        assertNull(FtpListParser.parseMlsd("", "size=100; name.mp4"));
    }

    @Test
    public void mlsdKeepsSpacesInName() {
        NetDiskEntry entry = FtpListParser.parseMlsd("", "type=file;size=10; My Movie 2024.mp4");
        assertEquals("My Movie 2024.mp4", entry.getName());
    }

    // ------------------------------------------------------------------ Unix LIST

    @Test
    public void parsesUnixFile() {
        NetDiskEntry entry = FtpListParser.parseUnix("",
                "-rw-r--r--    1 user  group  1234567 Jan 10 12:34 movie.mp4");
        assertEquals("movie.mp4", entry.getName());
        assertEquals(1234567L, entry.getSize());
        assertFalse(entry.isDirectory());
    }

    @Test
    public void parsesUnixDirectory() {
        NetDiskEntry entry = FtpListParser.parseUnix("",
                "drwxr-xr-x    2 user  group     4096 Jan 10 12:34 Movies");
        assertEquals("Movies", entry.getName());
        assertTrue(entry.isDirectory());
        // 目录的尺寸字段是 inode 噪声，不该展示成 4096 字节。
        assertEquals(0L, entry.getSize());
    }

    @Test
    public void unixKeepsSpacesInFileName() {
        // 用 split 取末尾字段只会拿到 "2024.mp4"，把文件拧成打不开的错误路径。
        NetDiskEntry entry = FtpListParser.parseUnix("",
                "-rw-r--r-- 1 user group 1234 Jan 10 12:34 My Movie 2024.mp4");
        assertEquals("My Movie 2024.mp4", entry.getName());
    }

    @Test
    public void parsesUnixYearTimeFormat() {
        // 半年以前的文件，时间列是年份而不是 时:分。
        NetDiskEntry entry = FtpListParser.parseUnix("",
                "-rw-r--r-- 1 user group 99 Mar 05 2023 old.mp4");
        assertEquals("old.mp4", entry.getName());
        assertEquals(99L, entry.getSize());
    }

    @Test
    public void symlinkTakesNameBeforeArrow() {
        NetDiskEntry entry = FtpListParser.parseUnix("",
                "lrwxrwxrwx 1 user group 4 Jan 10 12:34 link.mp4 -> target.mp4");
        assertEquals("link.mp4", entry.getName());
    }

    @Test
    public void rejectsNonUnixLine() {
        assertNull(FtpListParser.parseUnix("", "total 42"));
        assertNull(FtpListParser.parseUnix("", "-rw"));
    }

    // ------------------------------------------------------------------ DOS LIST

    @Test
    public void parsesDosFile() {
        NetDiskEntry entry = FtpListParser.parseDos("",
                "01-10-26  12:34PM            1234567 movie.mp4");
        assertEquals("movie.mp4", entry.getName());
        assertEquals(1234567L, entry.getSize());
        assertFalse(entry.isDirectory());
    }

    @Test
    public void parsesDosDirectory() {
        NetDiskEntry entry = FtpListParser.parseDos("",
                "01-10-26  12:34PM       <DIR>          Movies");
        assertEquals("Movies", entry.getName());
        assertTrue(entry.isDirectory());
    }

    @Test
    public void dosKeepsSpacesInFileName() {
        NetDiskEntry entry = FtpListParser.parseDos("",
                "01-10-26  12:34PM       <DIR>          My Movies 2024");
        assertEquals("My Movies 2024", entry.getName());
    }

    @Test
    public void rejectsNonDosLine() {
        assertNull(FtpListParser.parseDos("", "hello world"));
        assertNull(FtpListParser.parseDos("", "01-10-26"));
    }

    // ------------------------------------------------------------------ 统一入口与容错

    @Test
    public void dispatchesByFirstCharacter() {
        assertTrue(FtpListParser.parseLine("", "-rw-r--r-- 1 u g 1 Jan 10 12:34 a.mp4").getName().equals("a.mp4"));
        assertTrue(FtpListParser.parseLine("", "drwxr-xr-x 2 u g 1 Jan 10 12:34 d").isDirectory());
        assertTrue(FtpListParser.parseLine("", "01-10-26  12:34PM  <DIR>  d").isDirectory());
        assertTrue(FtpListParser.parseLine("", "type=file;size=1; a.mp4").getName().equals("a.mp4"));
    }

    @Test
    public void skipsUnparsableLinesInsteadOfFailing() {
        List<NetDiskEntry> entries = FtpListParser.parse("", String.join("\n",
                "total 8",
                "-rw-r--r-- 1 user group 1234 Jan 10 12:34 good.mp4",
                "some banner text",
                ""));
        // 认不出的行被跳过，而不是让整个目录都打不开。
        assertEquals(1, entries.size());
        assertEquals("good.mp4", entries.get(0).getName());
    }

    @Test
    public void dropsDotEntries() {
        List<NetDiskEntry> entries = FtpListParser.parse("", String.join("\n",
                "drwxr-xr-x 2 u g 1 Jan 10 12:34 .",
                "drwxr-xr-x 2 u g 1 Jan 10 12:34 ..",
                "-rw-r--r-- 1 u g 1 Jan 10 12:34 a.mp4"));
        assertEquals(1, entries.size());
    }

    @Test
    public void buildsPathsUnderParent() {
        List<NetDiskEntry> entries = FtpListParser.parse("Movies", "-rw-r--r-- 1 u g 1 Jan 10 12:34 a.mp4");
        assertEquals("Movies/a.mp4", entries.get(0).getPath());
    }

    @Test
    public void directoriesSortFirst() {
        List<NetDiskEntry> entries = FtpListParser.parse("", String.join("\n",
                "-rw-r--r-- 1 u g 1 Jan 10 12:34 z.mp4",
                "drwxr-xr-x 2 u g 1 Jan 10 12:34 aaa"));
        assertTrue(entries.get(0).isDirectory());
    }

    @Test
    public void emptyInputYieldsEmptyList() {
        assertTrue(FtpListParser.parse("", null).isEmpty());
        assertTrue(FtpListParser.parse("", "").isEmpty());
    }

    // ------------------------------------------------------------------ 时间

    @Test
    public void mlsdTimeIsParsedToEpoch() {
        NetDiskEntry entry = FtpListParser.parseMlsd("", "type=file;size=1;modify=20240110123456; a.mp4");
        // 2024-01-10 12:34:56 本地时间
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTimeInMillis(entry.getModified());
        assertEquals(2024, calendar.get(java.util.Calendar.YEAR));
        assertEquals(java.util.Calendar.JANUARY, calendar.get(java.util.Calendar.MONTH));
        assertEquals(10, calendar.get(java.util.Calendar.DAY_OF_MONTH));
    }

    @Test
    public void dosPmHourIsConverted() {
        // 12PM 是 12 点（不是 24 点），1PM 是 13 点；算错会让时间整段偏。
        NetDiskEntry noon = FtpListParser.parseDos("", "01-10-26  12:00PM  <DIR>  d");
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTimeInMillis(noon.getModified());
        assertEquals(12, calendar.get(java.util.Calendar.HOUR_OF_DAY));

        NetDiskEntry afternoon = FtpListParser.parseDos("", "01-10-26  01:00PM  <DIR>  d");
        calendar.setTimeInMillis(afternoon.getModified());
        assertEquals(13, calendar.get(java.util.Calendar.HOUR_OF_DAY));
    }

    @Test
    public void dosAmTwelveIsMidnight() {
        NetDiskEntry midnight = FtpListParser.parseDos("", "01-10-26  12:00AM  <DIR>  d");
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTimeInMillis(midnight.getModified());
        assertEquals(0, calendar.get(java.util.Calendar.HOUR_OF_DAY));
    }
}
