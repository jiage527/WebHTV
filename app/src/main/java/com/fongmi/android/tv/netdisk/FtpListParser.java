package com.fongmi.android.tv.netdisk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 解析 FTP 的目录列举结果。纯逻辑、不依赖 Android，便于单测。
 *
 * <p><b>为什么要自己解析。</b>FTP 没有统一的目录列举格式。{@code MLSD} 是标准结构化
 * 格式（RFC 3659），但很多服务器（尤其是老 NAS 与 Windows IIS）不支持它，只能退回
 * {@code LIST}。而 {@code LIST} 的输出格式<b>完全由服务器实现决定</b>，世上至少有两套
 * 主流写法：
 *
 * <ul>
 *   <li><b>Unix / NAS</b>：{@code -rw-r--r-- 1 user group  1234567 Jan 10 12:34 name.mp4}</li>
 *   <li><b>DOS / Windows</b>：{@code 01-10-26  12:34PM  <DIR>  name} 或带尺寸的
 *       {@code 01-10-26  12:34PM        1234567 name.mp4}</li>
 * </ul>
 *
 * <p>只按其中一种写，遇到另一种就会整个目录都列不出来（或被解析成一堆空名字）。
 * 所以这里同时认这两种，并且都做成容错解析：认不出的行直接跳过，而不是抛异常
 * ——宁可少列一项，也好过因为一行格式奇怪就让用户完全打不开这个目录。
 *
 * <p><b>文件名的边界必须从「时间/尺寸字段结束之后」开始取。</b>Unix 格式里文件名
 * 可以包含空格（{@code My Movie 2024.mp4}），所以不能用 {@code split("\\s+")} 取末尾
 * 字段——那样只会拿到 {@code 2024.mp4}，把文件练成打不开的错误路径。
 */
final class FtpListParser {

    private FtpListParser() {
    }

    /** 一行都认不出来时返回空列表（不是 null），调用方按「空目录」处理。 */
    static List<NetDiskEntry> parse(String parent, String text) {
        List<NetDiskEntry> entries = new ArrayList<>();
        if (text == null || text.isEmpty()) return entries;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw;
            if (line == null || line.trim().isEmpty()) continue;
            NetDiskEntry entry = parseLine(parent, line);
            if (entry == null) continue;
            if (entry.getName().isEmpty() || ".".equals(entry.getName()) || "..".equals(entry.getName())) continue;
            entries.add(entry);
        }
        entries.sort(NetDiskEntry::compareTo);
        return entries;
    }

    /** 解析一行；认不出来返回 {@code null}。 */
    static NetDiskEntry parseLine(String parent, String line) {
        if (line == null) return null;
        NetDiskEntry byMlsd = parseMlsd(parent, line);
        if (byMlsd != null) return byMlsd;
        if (line.startsWith("-") || line.startsWith("l") || line.startsWith("d")
                || line.startsWith("b") || line.startsWith("c") || line.startsWith("p")) {
            return parseUnix(parent, line);
        }
        return parseDos(parent, line);
    }

    /**
     * MLSD 格式：{@code type=file;size=1234;modify=20240110123456; name}。
     *
     * <p>结构化的，优先用它。注意分隔符是分号，且名字与属性之间正好隔一个空格，
     * 名字本身可以含空格，所以从第一个空格之后整体取名。
     */
    static NetDiskEntry parseMlsd(String parent, String line) {
        if (line == null || !line.contains(";")) return null;
        int space = line.indexOf(' ');
        if (space <= 0) return null;
        String facts = line.substring(0, space);
        if (!facts.contains("=")) return null;
        String name = line.substring(space + 1);
        boolean directory = false;
        boolean sawType = false;
        long size = 0;
        long modified = 0;
        for (String fact : facts.split(";")) {
            int eq = fact.indexOf('=');
            if (eq <= 0) continue;
            String key = fact.substring(0, eq).toLowerCase(Locale.ROOT);
            String value = fact.substring(eq + 1);
            if ("type".equals(key)) {
                sawType = true;
                // cdir/pdir 是当前目录与父目录，不该出现在列表里。
                if ("dir".equalsIgnoreCase(value)) directory = true;
                else if ("cdir".equalsIgnoreCase(value) || "pdir".equalsIgnoreCase(value)) return null;
            } else if ("size".equals(key)) {
                size = parseLong(value);
            } else if ("modify".equals(key)) {
                modified = parseMlsdTime(value);
            }
        }
        if (!sawType) return null;
        return new NetDiskEntry(name, NetDiskCodec.join(parent, name, NetDiskType.FTP.separator()),
                directory, directory ? 0 : size, modified);
    }

    /**
     * Unix 格式：{@code -rw-r--r-- 1 user group 1234567 Jan 10 12:34 name.mp4}。
     *
     * <p>字段数是可变的（权限、硬链接数、属主、属组），而且时间有两种写法：
     * 半年内是 {@code Jan 10 12:34}，更久以前是 {@code Jan 10 2024}。所以这里
     * 不数字段个数，而是<b>从左边跳过固定几段，再按「下一个像时间的片段之后」定位名字</b>。
     */
    static NetDiskEntry parseUnix(String parent, String line) {
        if (line == null || line.length() < 10) return null;
        char type = line.charAt(0);
        boolean directory = type == 'd';
        // 链接行形如 "lrwxrwxrwx 1 u g 4 Jan 1 00:00 link -> target"，
        // 取箭头左边才是名字，否则会把目标路径也当成文件名的一部分。
        String rest = line.substring(1).trim();
        String[] parts = rest.split("\\s+");
        // 字段布局：权限 链接数 属主 属组 尺寸 月 日 时间/年 名字...
        // 第 0 段是权限（首字符已在上方剥掉），所以月是第 5 段、尺寸是第 4 段。
        // 必须以「月」而不是尺寸作锚点：部分服务器把尺寸写成 '-'，
        // 否则整行都认不出来。
        final int monthIndex = 5;
        if (parts.length < monthIndex + 4) return null;
        if (!looksLikeMonth(parts[monthIndex]) || !isNumeric(parts[monthIndex + 1])) return null;
        // 名字从「时间字段之后」开始，并保留内部空格：
        // My Movie 2024.mp4 用取末尾字段的办法只会得到 2024.mp4。
        String name = joinFrom(parts, monthIndex + 3);
        if (name.isEmpty()) return null;
        // 链接行要把 " -> 目标" 去掉；链接指向目录时也当文件处理，
        // 至少不会把用户带进一个打不开的目录。
        int arrow = name.indexOf(" -> ");
        if (arrow >= 0) name = name.substring(0, arrow).trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) return null;
        long modified = parseUnixTime(parts[monthIndex], parts[monthIndex + 1], parts[monthIndex + 2]);
        // 目录的尺寸字段是 inode 噪声，不该展示。
        return new NetDiskEntry(name, NetDiskCodec.join(parent, name, NetDiskType.FTP.separator()),
                directory, directory ? 0 : parseLong(parts[4]), modified);
    }

    /**
     * DOS / Windows IIS 格式：{@code 01-10-26  12:34PM  <DIR>  name}。
     *
     * <p>目录用字面量 {@code <DIR>} 标记，文件则是一串数字尺寸。日期是
     * {@code MM-dd-yy} 或 {@code MM-dd-yyyy}，时间带 AM/PM；有些实现写成 24 小时制。
     */
    static NetDiskEntry parseDos(String parent, String line) {
        if (line == null) return null;
        String trimmed = line.trim();
        if (trimmed.length() < 10) return null;
        String[] parts = trimmed.split("\\s+");
        if (parts.length < 3) return null;
        // 前两段必须是日期与时间，否则这行不是 DOS 格式（也可能是 MLSD 之外的怪东西）。
        if (!looksLikeDate(parts[0]) || !looksLikeTime(parts[1])) return null;
        int index = 2;
        boolean directory;
        long size = 0;
        if ("<DIR>".equalsIgnoreCase(parts[index])) {
            directory = true;
            index++;
        } else {
            directory = false;
            size = parseLong(parts[index]);
            index++;
        }
        if (index >= parts.length) return null;
        String name = joinFrom(parts, index);
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) return null;
        long modified = parseDosTime(parts[0], parts[1]);
        return new NetDiskEntry(name, NetDiskCodec.join(parent, name, NetDiskType.FTP.separator()),
                directory, directory ? 0 : size, modified);
    }

    /** 把从 {@code index} 起的片段用单个空格拼回去——文件名里的空格要保留。 */
    private static String joinFrom(String[] parts, int index) {
        if (parts == null || index >= parts.length) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = index; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            if (builder.length() > 0) builder.append(' ');
            builder.append(parts[i]);
        }
        return builder.toString().trim();
    }

    private static boolean looksLikeMonth(String text) {
        if (text == null || text.length() < 3) return false;
        switch (text.substring(0, 3).toLowerCase(Locale.ROOT)) {
            case "jan": case "feb": case "mar": case "apr": case "may": case "jun":
            case "jul": case "aug": case "sep": case "oct": case "nov": case "dec":
                return true;
            default:
                return false;
        }
    }

    /** 日期形如 {@code MM-dd-yy} 或 {@code MM-dd-yyyy}。 */
    private static boolean looksLikeDate(String text) {
        if (text == null || text.length() < 6) return false;
        int dashes = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '-') {
                dashes++;
                continue;
            }
            if (!Character.isDigit(ch)) return false;
        }
        return dashes == 2;
    }

    /** 时间形如 {@code 12:34}、{@code 12:34PM}、{@code 1:02 AM}。 */
    private static boolean looksLikeTime(String text) {
        if (text == null) return false;
        String value = text.trim().toUpperCase(Locale.ROOT);
        if (value.endsWith("AM") || value.endsWith("PM")) value = value.substring(0, value.length() - 2).trim();
        int colon = value.indexOf(':');
        if (colon <= 0) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == ':') continue;
            if (!Character.isDigit(ch)) return false;
        }
        return true;
    }

    private static boolean isNumeric(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) if (!Character.isDigit(text.charAt(i))) return false;
        return true;
    }

    private static long parseLong(String text) {
        if (text == null) return 0;
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseMlsdTime(String text) {
        // 形如 20240110123456 或 20240110123456.789
        if (text == null || text.length() < 8) return 0;
        try {
            String digits = text.trim();
            int dot = digits.indexOf('.');
            if (dot > 0) digits = digits.substring(0, dot);
            if (digits.length() < 8) return 0;
            int year = Integer.parseInt(digits.substring(0, 4));
            int month = Integer.parseInt(digits.substring(4, 6));
            int day = Integer.parseInt(digits.substring(6, 8));
            int hour = digits.length() >= 10 ? Integer.parseInt(digits.substring(8, 10)) : 0;
            int minute = digits.length() >= 12 ? Integer.parseInt(digits.substring(10, 12)) : 0;
            return toMillis(year, month, day, hour, minute);
        } catch (Throwable e) {
            return 0;
        }
    }

    /**
     * Unix 时间字段转毫秒。
     *
     * <p>只有「月 日 时:分」时没有年份，按协议惯例这类文件是近期的，取当前年；
     * 若这样算出来的时间比当前还晚，说明其实是去年的（跨年边界），回退一年。
     */
    private static long parseUnixTime(String month, String day, String time) {
        int monthIndex = monthIndexOf(month);
        int dayOfMonth = (int) parseLong(day);
        if (monthIndex <= 0 || dayOfMonth <= 0) return 0;
        try {
            if (time != null && time.contains(":")) {
                String[] parts = time.split(":");
                int hour = Integer.parseInt(parts[0].trim());
                int minute = Integer.parseInt(parts[1].trim());
                int year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
                long value = toMillis(year, monthIndex, dayOfMonth, hour, minute);
                if (value > System.currentTimeMillis()) value = toMillis(year - 1, monthIndex, dayOfMonth, hour, minute);
                return value;
            }
            int year = (int) parseLong(time);
            if (year < 100) year += year < 70 ? 2000 : 1900;
            return toMillis(year, monthIndex, dayOfMonth, 0, 0);
        } catch (Throwable e) {
            return 0;
        }
    }

    /** DOS 时间：{@code MM-dd-yy[yy]} + {@code hh:mm[AM/PM]}。 */
    private static long parseDosTime(String date, String time) {
        try {
            String[] dateParts = date.split("-");
            if (dateParts.length != 3) return 0;
            int month = Integer.parseInt(dateParts[0]);
            int day = Integer.parseInt(dateParts[1]);
            int year = Integer.parseInt(dateParts[2]);
            if (year < 100) year += year < 70 ? 2000 : 1900;
            String value = time.trim().toUpperCase(Locale.ROOT);
            boolean pm = value.endsWith("PM");
            boolean am = value.endsWith("AM");
            if (pm || am) value = value.substring(0, value.length() - 2).trim();
            String[] timeParts = value.split(":");
            int hour = Integer.parseInt(timeParts[0]);
            int minute = timeParts.length > 1 ? Integer.parseInt(timeParts[1]) : 0;
            // 12 小时制：12AM 是 0 点，12PM 是 12 点，其余 PM 加 12。
            if (pm && hour < 12) hour += 12;
            if (am && hour == 12) hour = 0;
            return toMillis(year, month, day, hour, minute);
        } catch (Throwable e) {
            return 0;
        }
    }

    private static int monthIndexOf(String text) {
        if (text == null || text.length() < 3) return 0;
        switch (text.substring(0, 3).toLowerCase(Locale.ROOT)) {
            case "jan": return 1;
            case "feb": return 2;
            case "mar": return 3;
            case "apr": return 4;
            case "may": return 5;
            case "jun": return 6;
            case "jul": return 7;
            case "aug": return 8;
            case "sep": return 9;
            case "oct": return 10;
            case "nov": return 11;
            case "dec": return 12;
            default: return 0;
        }
    }

    private static long toMillis(int year, int month, int day, int hour, int minute) {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }
}
