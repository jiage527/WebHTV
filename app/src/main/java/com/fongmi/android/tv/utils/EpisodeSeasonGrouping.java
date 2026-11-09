package com.fongmi.android.tv.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 剧集「季」结构识别与分组。
 *
 * <p>很多 CSP 会把同一部剧的所有季平铺成一个剧集列表（例如「第一季 01…12」紧接着
 * 「第二季 01…12」），用户在几十上百个条目里很难定位。本类把带季标记的连续条目归成
 * 一组，供剧集分组栏按季跳转。
 *
 * <p>刻意不依赖 Android 类型，只在原始名称字符串上工作，因此可以直接用 JVM 单测覆盖
 * 各种命名（中文季、S01、SEASON 2）。只有在「确实存在两个及以上不同季」时才返回结果；
 * 否则返回空列表，让调用方沿用原有的等分翻页行为，保证不影响现有体验。
 *
 * <p>分组只取<b>连续区间</b>：跳转按条目下标执行，若把不相邻的同类季并成一组，
 * 跳转位置会指向错误内容。
 */
public final class EpisodeSeasonGrouping {

    /** 中文季标记，如「第一季」「第 10 季」。 */
    private static final Pattern CHINESE_SEASON = Pattern.compile("第\\s*([0-9一二三四五六七八九十百千零〇两兩]+)\\s*季");
    /**
     * 英文季标记，如「S01」「SEASON 2」。
     *
     * <p>前后断言用于排除嵌在其它单词或编码串里的伪匹配（如 {@code XPS15}、{@code HDR10}）。
     * 刻意不支持 {@code 1x02} 这类写法：它与分辨率（{@code 1920x1080}）无法可靠区分，
     * 误判会把整季切碎。
     */
    private static final Pattern ENGLISH_SEASON = Pattern.compile("(?i)(?<![A-Za-z0-9])(?:SEASON\\s*|S)([0-9]{1,2})(?![0-9])");

    /** 没有识别出季标记时使用的内部键。 */
    private static final int NO_SEASON = -1;

    private EpisodeSeasonGrouping() {
    }

    /**
     * 一个季分组。
     *
     * @param label 显示名，取自片名原文（「第一季」保持中文，英文统一为 {@code S%02d}）
     * @param start 起始条目下标（含）
     * @param end   结束条目下标（不含）
     */
    public record Season(String label, int start, int end) {

        public boolean contains(int position) {
            return position >= start && position < end;
        }

        public int size() {
            return end - start;
        }
    }

    /**
     * 按季分组。
     *
     * @param rawNames 剧集原始名称，顺序必须与界面展示顺序一致
     * @return 分组结果；识别出的不同季少于两个时返回空列表
     */
    public static List<Season> group(List<String> rawNames) {
        if (rawNames == null || rawNames.size() < 2) return new ArrayList<>();
        int size = rawNames.size();
        int[] keys = new int[size];
        String[] labels = new String[size];
        for (int i = 0; i < size; i++) {
            SeasonInfo info = parse(rawNames.get(i));
            keys[i] = info == null ? NO_SEASON : info.number();
            labels[i] = info == null ? null : info.label();
        }
        // 漏标的条目并进相邻分组：它们通常属于同一季，或是番外、特别篇这类附属内容，
        // 单独成组反而把列表割裂。先继承左侧，再退化为右侧（用于首个条目就没有标记的情况）。
        for (int i = 0; i < size; i++) {
            if (keys[i] != NO_SEASON) continue;
            keys[i] = i > 0 && keys[i - 1] != NO_SEASON ? keys[i - 1] : nextKey(keys, i);
        }
        List<Season> seasons = new ArrayList<>();
        List<Integer> seen = new ArrayList<>();
        for (int start = 0; start < size; ) {
            int key = keys[start];
            int end = start;
            while (end < size && keys[end] == key) end++;
            if (key != NO_SEASON) {
                if (!seen.contains(key)) seen.add(key);
                seasons.add(new Season(label(labels, start, end), start, end));
            }
            start = end;
        }
        return seen.size() >= 2 ? seasons : new ArrayList<>();
    }

    /** 找出条目位置所属的分组下标，找不到返回 -1。 */
    public static int indexOf(List<Season> seasons, int position) {
        if (seasons == null) return -1;
        for (int i = 0; i < seasons.size(); i++) if (seasons.get(i).contains(position)) return i;
        return -1;
    }

    /**
     * 分组标签，顺序与 {@link #group} 结果一致。
     *
     * <p>同一季因不相邻而被切成多段时，给标签补上段序号（如「第一季 (2)」），
     * 否则分组栏会出现两个同名标签。只有真的重名才加后缀。
     */
    public static List<String> labels(List<Season> seasons) {
        List<String> labels = new ArrayList<>();
        if (seasons == null) return labels;
        for (int i = 0; i < seasons.size(); i++) labels.add(disambiguate(seasons, i));
        return labels;
    }

    /** 分组跳转目标下标（即每个分组的起始条目），顺序与 {@link #group} 一致。 */
    public static List<Integer> starts(List<Season> seasons) {
        List<Integer> starts = new ArrayList<>();
        if (seasons == null) return starts;
        for (Season season : seasons) starts.add(season.start());
        return starts;
    }

    private static String disambiguate(List<Season> seasons, int index) {
        String label = seasons.get(index).label();
        int ordinal = 0;
        for (int i = 0; i <= index; i++) if (seasons.get(i).label().equals(label)) ordinal++;
        long total = seasons.stream().filter(season -> season.label().equals(label)).count();
        return total > 1 ? label + " (" + ordinal + ")" : label;
    }

    /** 取分组内第一个非空标签；整组都没标签时返回空串。 */
    private static String label(String[] labels, int start, int end) {
        for (int i = start; i < end; i++) if (labels[i] != null) return labels[i];
        return "";
    }

    /** 向右找第一个有季标记的键，用于补全开头处没有标记的条目。 */
    private static int nextKey(int[] keys, int from) {
        for (int i = from + 1; i < keys.length; i++) if (keys[i] != NO_SEASON) return keys[i];
        return NO_SEASON;
    }

    private static SeasonInfo parse(String name) {
        if (name == null || name.isEmpty()) return null;
        Matcher chinese = CHINESE_SEASON.matcher(name);
        if (chinese.find()) {
            int number = parseNumber(chinese.group(1));
            if (number > 0) return new SeasonInfo(number, chinese.group().replaceAll("\\s+", ""));
        }
        Matcher english = ENGLISH_SEASON.matcher(name);
        if (english.find()) {
            int number = Integer.parseInt(english.group(1));
            if (number > 0) return new SeasonInfo(number, String.format(Locale.ROOT, "S%02d", number));
        }
        return null;
    }

    /**
     * 把中文数字转成整数，支持 {@code 一二三…十百千} 与阿拉伯数字混写。
     *
     * <p>无法解析或超出合理范围时返回 -1。
     */
    static int parseNumber(String text) {
        if (text == null || text.isEmpty()) return -1;
        if (text.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        int section = 0;
        int digit = 0;
        for (char ch : text.toCharArray()) {
            int value = digitValue(ch);
            if (value >= 0) {
                digit = value;
                continue;
            }
            int unit = unitValue(ch);
            if (unit <= 0) return -1;
            // 「十二」的十前面没有数字，按 1 计。
            section += (digit == 0 ? 1 : digit) * unit;
            digit = 0;
        }
        int result = section + digit;
        return result > 0 && result <= 999 ? result : -1;
    }

    private static int digitValue(char ch) {
        if (ch >= '0' && ch <= '9') return ch - '0';
        return switch (ch) {
            case '零', '〇' -> 0;
            case '一' -> 1;
            case '二', '两', '兩' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> -1;
        };
    }

    private static int unitValue(char ch) {
        return switch (ch) {
            case '十' -> 10;
            case '百' -> 100;
            case '千' -> 1000;
            default -> -1;
        };
    }

    private record SeasonInfo(int number, String label) {
    }

    /** 便于测试与少量条目的调用。 */
    public static List<Season> groupNames(String... rawNames) {
        return group(Arrays.asList(rawNames));
    }
}
