package com.fongmi.android.tv.netdisk;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 网络存储的路径与百分号编解码工具。纯逻辑、不依赖 Android，便于单测覆盖。
 *
 * <p>刻意不用 {@link java.net.URLEncoder}：它把空格编成 {@code +}，在路径段里
 * 会被服务端当成加号，导致文件名带空格时打不开。这里按 RFC 3986 只保留非保留字符。
 *
 * <p>另一处必须自己实现的是路径规范化：SMB 的路径分隔符是反斜杠、WebDAV 是正斜杠，
 * 而且 NAS 上真实存在反斜杠或 {@code ..} 的文件名，所以规范化必须在「按分隔符切段
 * 之后再逐段判断」这一层做，不能简单地对整串做字符串替换。
 */
public final class NetDiskCodec {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private NetDiskCodec() {
    }

    // ------------------------------------------------------------------ 百分号编解码

    /** 编码单个路径段（不含分隔符）。 */
    public static String encodeSegment(String segment) {
        if (segment == null || segment.isEmpty()) return "";
        StringBuilder builder = new StringBuilder(segment.length() + 8);
        for (byte value : segment.getBytes(StandardCharsets.UTF_8)) {
            int ch = value & 0xff;
            if (isUnreserved(ch)) {
                builder.append((char) ch);
            } else {
                builder.append('%').append(HEX[ch >> 4]).append(HEX[ch & 0x0f]);
            }
        }
        return builder.toString();
    }

    /**
     * 解码百分号转义。非法转义（例如 {@code %zz} 或结尾的单个 {@code %}）原样保留，
     * 因为真实文件名里确实可能出现百分号，直接丢弃会让路径指向别的文件。
     */
    public static String decodeSegment(String text) {
        if (text == null || text.isEmpty()) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length());
        int index = 0;
        while (index < text.length()) {
            char ch = text.charAt(index);
            if (ch == '%' && index + 2 < text.length()) {
                int high = hex(text.charAt(index + 1));
                int low = hex(text.charAt(index + 2));
                if (high >= 0 && low >= 0) {
                    out.write((high << 4) | low);
                    index += 3;
                    continue;
                }
            }
            byte[] bytes = String.valueOf(ch).getBytes(StandardCharsets.UTF_8);
            out.write(bytes, 0, bytes.length);
            index++;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int hex(char ch) {
        if (ch >= '0' && ch <= '9') return ch - '0';
        if (ch >= 'a' && ch <= 'f') return ch - 'a' + 10;
        if (ch >= 'A' && ch <= 'F') return ch - 'A' + 10;
        return -1;
    }

    private static boolean isUnreserved(int ch) {
        return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                || ch == '-' || ch == '.' || ch == '_' || ch == '~';
    }

    // ------------------------------------------------------------------ 路径规范化

    /**
     * 统一分隔符、去掉空段与 {@code .}，并把 {@code ..} 弹出上一级。
     *
     * <p>{@code ..} 永远不会被弹到根之外：这是防止网盘路径逃出账号根目录的关键一步，
     * 因为代理端点最终会按账号去做鉴权与列目录。
     */
    public static String normalize(String path, char separator) {
        if (path == null) return "";
        String unified = path.replace('\\', '/').replace('/', '/');
        List<String> segments = new ArrayList<>();
        for (String raw : unified.split("/")) {
            if (raw.isEmpty() || ".".equals(raw)) continue;
            if ("..".equals(raw)) {
                if (!segments.isEmpty()) segments.remove(segments.size() - 1);
                continue;
            }
            segments.add(raw);
        }
        return join(segments, separator);
    }

    public static String join(List<String> segments, char separator) {
        StringBuilder builder = new StringBuilder();
        for (String segment : segments) {
            if (segment == null || segment.isEmpty()) continue;
            if (builder.length() > 0) builder.append(separator);
            builder.append(segment);
        }
        return builder.toString();
    }

    public static String join(String parent, String child, char separator) {
        String left = normalize(parent, separator);
        String right = normalize(child, separator);
        if (left.isEmpty()) return right;
        if (right.isEmpty()) return left;
        return left + separator + right;
    }

    /** 取父目录；根目录的父目录为空串（用 {@link #isRoot} 区分「根」与「空路径」）。 */
    public static String parent(String path, char separator) {
        String normalized = normalize(path, separator);
        int index = normalized.lastIndexOf(separator);
        return index < 0 ? "" : normalized.substring(0, index);
    }

    public static String name(String path, char separator) {
        String normalized = normalize(path, separator);
        int index = normalized.lastIndexOf(separator);
        return index < 0 ? normalized : normalized.substring(index + 1);
    }

    public static List<String> split(String path, char separator) {
        String normalized = normalize(path, separator);
        List<String> segments = new ArrayList<>();
        if (normalized.isEmpty()) return segments;
        for (String segment : normalized.split(String.valueOf(separator))) {
            if (!segment.isEmpty()) segments.add(segment);
        }
        return segments;
    }

    public static boolean isRoot(String path) {
        return normalize(path, '/').isEmpty();
    }

    /** 文件名是否像视频/字幕，用于浏览列表里区分可播放项。 */
    public static boolean isMediaName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (String extension : MEDIA_EXTENSIONS) if (lower.endsWith(extension)) return true;
        return false;
    }

    private static final String[] MEDIA_EXTENSIONS = {
            ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".ts", ".m2ts", ".m4v", ".mpg", ".mpeg",
            ".webm", ".rmvb", ".rm", ".vob", ".iso", ".3gp", ".mp3", ".flac", ".wav", ".m4a", ".aac",
            ".srt", ".ass", ".ssa", ".vtt", ".sub",
    };
}
