package com.fongmi.android.tv.download;

import java.util.ArrayList;
import java.util.List;

/**
 * 把「这条下载落在磁盘上的哪里」算成一句能显示的话。
 *
 * <p><b>为什么要单独抽出来。</b>一个 MP4 直链在缓存里就是一个文件，名字由 Media3 按
 * 「缓存键 + 起点 + 长度 + 版本」生成（形如 {@code od-xxxx.0.42907426.v1.exo}），直接显示它
 * 对用户没什么意义；而 HLS 是几十个分片、还被 {@code SimpleCache} 打散到若干分片目录里
 * （实测见到过 {@code offline-downloads/0/1/2/4/5/9/}），这时把其中任意一个的路径显示出来
 * 都是误导。所以规则是：<b>只有一个文件时显示它的完整路径，多个文件时显示它们共同的目录</b>，
 * 两种情况都给出「去文件管理器里找得到」的位置。
 *
 * <p>路径不是编出来的：调用方传入的是缓存里真实的 {@code File} 绝对路径。
 *
 * <p>本类不碰 Android API，可直接跑 JVM 单测。
 */
public final class OfflineDownloadPath {

    /** 一个可以显示的位置：唯一文件的完整路径，或者多个文件共同的目录。 */
    public record Location(String path, int fileCount) {

        public boolean hasPath() {
            return path != null && !path.isEmpty();
        }

        /** 文件多于一个时要额外告诉用户「一共几个」。 */
        public boolean needsCount() {
            return fileCount > 1;
        }
    }

    private OfflineDownloadPath() {
    }

    /**
     * @param filePaths   这条下载在缓存里实际占用的文件绝对路径，顺序不重要
     * @param fallbackDir 一个文件都没有时退回去显示的目录（例如下载根目录）
     */
    public static Location locate(List<String> filePaths, String fallbackDir) {
        List<String> files = clean(filePaths);
        if (files.isEmpty()) return new Location(trimSlash(fallbackDir), 0);
        if (files.size() == 1) return new Location(files.get(0), 1);
        String common = commonDirectory(files);
        return new Location(common.isEmpty() ? trimSlash(fallbackDir) : common, files.size());
    }

    /**
     * 这批文件共同的目录。
     *
     * <p>按「目录层级」逐段比较，不是按字符比较：{@code /a/bc/x} 与 {@code /a/bd/y} 在字符上
     * 共享 {@code /a/b}，但 {@code bc} 与 {@code bd} 是两个不同的目录名，共同目录只能是
     * {@code /a}。所以切段比、拼回，而不是 {@code startsWith} 硬切。
     */
    public static String commonDirectory(List<String> filePaths) {
        List<String> files = clean(filePaths);
        if (files.isEmpty()) return "";
        List<String> common = split(directoryOf(files.get(0)));
        for (int i = 1; i < files.size() && !common.isEmpty(); i++) {
            List<String> other = split(directoryOf(files.get(i)));
            int keep = 0;
            while (keep < common.size() && keep < other.size() && common.get(keep).equals(other.get(keep))) keep++;
            common = new ArrayList<>(common.subList(0, keep));
        }
        if (common.isEmpty()) return "";
        return "/" + String.join("/", common);
    }

    /** 取一个文件的所在目录（不带结尾斜杠）；没有目录部分时返回空串。 */
    public static String directoryOf(String path) {
        if (path == null) return "";
        String value = trimSlash(path);
        int slash = value.lastIndexOf('/');
        if (slash <= 0) return "";
        return value.substring(0, slash);
    }

    /** 把绝对路径切成非空的层级片段（丢掉开头的空串）。 */
    private static List<String> split(String directory) {
        List<String> parts = new ArrayList<>();
        if (directory == null) return parts;
        for (String part : directory.split("/")) {
            if (!part.isEmpty()) parts.add(part);
        }
        return parts;
    }

    /** 去掉结尾的斜杠，避免拼出 {@code dir//name}。 */
    private static String trimSlash(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        while (trimmed.endsWith("/") || trimmed.endsWith("\\")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * 去掉 null、空串与结尾斜杠，并去重。
     *
     * <p>去重是必要的：{@code SimpleCache} 里同一段内容可能被多个 span 指向同一个文件，
     * 不去重就会把「1 个文件」算成 2 个，于是本来能显示完整路径的变成只显示目录，
     * 「（2 个文件）」也是虚报。
     */
    private static List<String> clean(List<String> filePaths) {
        List<String> files = new ArrayList<>();
        if (filePaths == null) return files;
        for (String file : filePaths) {
            if (file == null) continue;
            String value = trimSlash(file);
            if (!value.isEmpty() && !files.contains(value)) files.add(value);
        }
        return files;
    }
}
