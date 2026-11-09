package com.fongmi.android.tv.webdav;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * WebDAV 服务端的请求路径解析与安全校验。
 *
 * <p>纯逻辑、不依赖 Android，便于单测。
 *
 * <p><b>为什么这一层必须单独写、单独测。</b>本服务通过局域网对其他设备开放，并且支持
 * 上传与建目录（写操作）。路径解析只要有一点疏漏，别人就能用 {@code ../} 之类的手段
 * 读到或写到应用不该暴露的目录（配置、数据库、口令文件）。这类漏洞不会在正常使用中
 * 显露，所以把校验集中在纯逻辑里、用单测把逃逸尝试全部钉住。
 *
 * <p><b>百分号解码必须在切段之后逐段做。</b>若先把整串解码再切段，{@code %2F} 会变成
 * 分隔符，{@code a%2F..%2F..%2Fb} 就能凭空造出上级目录；而 {@code %2E%2E} 也会变成
 * {@code ..}。逐段解码后每段都是不可再分的名字，{@code .} 与 {@code ..} 就在自己那一
 * 段里被识别并拦下。
 *
 * <p><b>反斜杠同样当分隔符。</b>客户端（尤其 Windows）可能把 {@code \} 当路径分隔符发
 * 过来；只按 {@code /} 切的话 {@code ..\..\x} 会整段保留成一个「文件名」，进而被当成
 * 正常名字通过校验。
 */
public final class WebDavPath {

    private WebDavPath() {
    }

    /**
     * 把请求 URI 解析成「相对根目录的规范路径」。
     *
     * @return 规范化的相对路径（例如 {@code Movies/a.mp4}）；根目录是空串。
     *         出现无法安全处理的段（双重编码、控制字符）时返回 {@code null}，由调用方回 403。
     */
    public static String normalize(String rawPath) {
        if (rawPath == null) return "";
        // 去掉查询串与片段：它们不属于路径。
        String path = cut(rawPath, '?');
        path = cut(path, '#');
        // 统一分隔符后再逐段处理，让反斜杠写法也走同一套校验。
        path = path.replace('\\', '/');

        List<String> segments = new ArrayList<>();
        for (String raw : path.split("/")) {
            if (raw.isEmpty() || ".".equals(raw)) continue;
            String decoded = decodeSegment(raw);
            if (decoded.isEmpty() || ".".equals(decoded)) continue;
            if ("..".equals(decoded)) {
                // 「..」回退一级，但永远不越过根目录（栈空时什么都不做）。
                //
                // 因为结果路径始终是「相对根目录」的，越界的回退已无从表达，
                // 所以两种做法（拒绝 / 压到根）都安全。这里选后者是为了兼容那些会发
                // a/../b 这类规范化请求的客户端。
                if (!segments.isEmpty()) segments.remove(segments.size() - 1);
                continue;
            }
            // 解码后仍含分隔符，或原样还留着编码过的分隔符，都说明客户端做了双重编码
            // （%252F → %2F → /）。这种段绝不是合法文件名，按逃逸处理。
            if (decoded.indexOf('/') >= 0 || decoded.indexOf('\\') >= 0) return null;
            if (hasEncodedSeparator(decoded)) return null;
            // NUL 与其它控制字符在文件系统上行为未定义，一律拒绝。
            if (containsControl(decoded)) return null;
            segments.add(decoded);
        }
        return join(segments);
    }

    /**
     * 把规范路径转回实际路径段列表。
     *
     * <p>调用方拿到的是已经过 {@link #normalize} 的路径，所以这里只做切分；
     * 但仍逐段确认不含逃逸，避免有调用方拿未校验的值直接进来。
     */
    public static List<String> segmentsOf(String normalized) {
        List<String> segments = new ArrayList<>();
        if (normalized == null || normalized.isEmpty()) return segments;
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) continue;
            if (segment.indexOf('\\') >= 0 || containsControl(segment)) continue;
            segments.add(segment);
        }
        return segments;
    }

    public static String join(List<String> segments) {
        StringBuilder builder = new StringBuilder();
        for (String segment : segments) {
            if (segment == null || segment.isEmpty()) continue;
            if (builder.length() > 0) builder.append('/');
            builder.append(segment);
        }
        return builder.toString();
    }

    /** 取父路径；根目录的父路径是空串。 */
    public static String parent(String normalized) {
        if (normalized == null || normalized.isEmpty()) return "";
        int index = normalized.lastIndexOf('/');
        return index < 0 ? "" : normalized.substring(0, index);
    }

    /** 取末段名字；根目录返回空串。 */
    public static String name(String normalized) {
        if (normalized == null || normalized.isEmpty()) return "";
        int index = normalized.lastIndexOf('/');
        return index < 0 ? normalized : normalized.substring(index + 1);
    }

    /**
     * 把规范路径编成 URI 路径（每段各自转义）。
     *
     * <p>用于生成 PROPFIND 响应里的 {@code href}：文件名可能含空格、中文、{@code #}，
     * 直接拼进 XML/URI 会让客户端解析出别的路径。
     */
    public static String encode(String normalized) {
        StringBuilder builder = new StringBuilder();
        for (String segment : segmentsOf(normalized)) {
            builder.append('/').append(encodeSegment(segment));
        }
        return builder.length() == 0 ? "/" : builder.toString();
    }

    public static String encodeSegment(String segment) {
        if (segment == null || segment.isEmpty()) return "";
        StringBuilder builder = new StringBuilder(segment.length() + 8);
        for (byte value : segment.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int ch = value & 0xff;
            if (isUnreserved(ch)) builder.append((char) ch);
            else builder.append('%').append(HEX[ch >> 4]).append(HEX[ch & 0x0f]);
        }
        return builder.toString();
    }

    /**
     * 解码单个路径段。
     *
     * <p>非法转义（{@code %zz}、结尾的单个 {@code %}）原样保留：真实文件名里确实可能
     * 有百分号，丢掉会让路径指向另一个文件。
     */
    public static String decodeSegment(String text) {
        if (text == null || text.isEmpty()) return "";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(text.length());
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
            byte[] bytes = String.valueOf(ch).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.write(bytes, 0, bytes.length);
            index++;
        }
        return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static boolean isUnreserved(int ch) {
        return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                || ch == '-' || ch == '.' || ch == '_' || ch == '~';
    }

    private static int hex(char ch) {
        if (ch >= '0' && ch <= '9') return ch - '0';
        if (ch >= 'a' && ch <= 'f') return ch - 'a' + 10;
        if (ch >= 'A' && ch <= 'F') return ch - 'A' + 10;
        return -1;
    }

    /** 是否还残留编码过的路径分隔符（双重编码的痕迹）。 */
    private static boolean hasEncodedSeparator(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("%2f") || lower.contains("%5c");
    }

    /** 控制字符（含 NUL、CR、LF）在文件名里没有合法用途。 */
    private static boolean containsControl(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch < 0x20 || ch == 0x7f) return true;
        }
        return false;
    }

    private static String cut(String text, char delimiter) {
        int index = text.indexOf(delimiter);
        return index < 0 ? text : text.substring(0, index);
    }

    /** 文件名是否为目录列举里应当展示的项（挡掉以点开头的隐藏文件与临时文件）。 */
    public static boolean isVisibleName(String name) {
        if (name == null || name.isEmpty()) return false;
        if (".".equals(name) || "..".equals(name)) return false;
        // 以点开头的文件多为系统或临时文件，展示出来只会让用户困惑。
        return !name.startsWith(".");
    }

    /** 排序：目录在前，然后按名称不区分大小写。与本地文件浏览观感一致。 */
    public static int compare(boolean directoryA, String nameA, boolean directoryB, String nameB) {
        if (directoryA != directoryB) return directoryA ? -1 : 1;
        String left = nameA == null ? "" : nameA.toLowerCase(Locale.ROOT);
        String right = nameB == null ? "" : nameB.toLowerCase(Locale.ROOT);
        return left.compareTo(right);
    }
}
