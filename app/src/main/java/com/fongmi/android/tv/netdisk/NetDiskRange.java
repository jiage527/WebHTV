package com.fongmi.android.tv.netdisk;

/**
 * HTTP Range 头的解析。纯逻辑、不依赖 Android，便于单测覆盖。
 *
 * <p><b>为什么必须自己解析。</b>播放器拖动进度条时会发 {@code Range: bytes=N-}，
 * 如果代理端不回 206 也不回正确的 {@code Content-Range}，Exo 与 mpv 都会认为该源
 * 不可 seek，表现为拖动后重新从头播，或直接报错。网盘的读接口是按偏移量随机读的，
 * 天然支持区间，所以这里只要如实转达即可，不必缓存整文件。
 */
public final class NetDiskRange {

    /** 解析结果。{@link #unsatisfiable} 为真时应当回 416。 */
    public static final class Result {

        private final long start;
        private final long end;
        private final boolean valid;
        private final boolean unsatisfiable;

        private Result(long start, long end, boolean valid, boolean unsatisfiable) {
            this.start = start;
            this.end = end;
            this.valid = valid;
            this.unsatisfiable = unsatisfiable;
        }

        public long getStart() {
            return start;
        }

        public long getEnd() {
            return end;
        }

        /** 客户端是否真的带了 Range 头。 */
        public boolean isValid() {
            return valid;
        }

        public boolean isUnsatisfiable() {
            return unsatisfiable;
        }

        public boolean isPartial(long total) {
            return valid && (start > 0 || end < total - 1);
        }

        public long length() {
            return end - start + 1;
        }
    }

    private NetDiskRange() {
    }

    /**
     * 按 RFC 7233 解析单段 Range。
     *
     * <p>只支持单段：多段（{@code bytes=0-9,20-29}）需要 multipart 响应体，而播放器
     * 从不发这种请求（已核对 Exo 与 mpv 的请求），实现它只会引入没被走到的代码路径。
     * 多段与语法错误一律当作「没有 Range」处理，也就是回整个文件，这是 RFC 允许的降级。
     *
     * @param header 原始 Range 头，可为 null
     * @param total  资源总长度；未知时传 -1
     */
    public static Result parse(String header, long total) {
        long whole = total > 0 ? total : 0;
        Result wholeResult = new Result(0, Math.max(0, whole - 1), false, false);
        if (header == null) return wholeResult;
        String value = header.trim();
        if (!value.regionMatches(true, 0, "bytes=", 0, 6)) return wholeResult;
        String spec = value.substring(6).trim();
        if (spec.isEmpty() || spec.indexOf(',') >= 0) return wholeResult;

        int dash = spec.indexOf('-');
        if (dash < 0) return wholeResult;
        String left = spec.substring(0, dash).trim();
        String right = spec.substring(dash + 1).trim();

        if (left.isEmpty()) {
            // 后缀范围：bytes=-N 表示「最后 N 个字节」。
            long suffix;
            try {
                suffix = Long.parseLong(right);
            } catch (NumberFormatException e) {
                return wholeResult;
            }
            if (suffix <= 0) return new Result(0, -1, true, true);
            if (total <= 0) return wholeResult;
            long start = Math.max(0, total - suffix);
            return new Result(start, total - 1, true, false);
        }

        long start;
        try {
            start = Long.parseLong(left);
        } catch (NumberFormatException e) {
            return wholeResult;
        }
        if (start < 0) return wholeResult;

        long end;
        if (right.isEmpty()) {
            if (total <= 0) return new Result(start, Long.MAX_VALUE, true, false);
            end = total - 1;
        } else {
            try {
                end = Long.parseLong(right);
            } catch (NumberFormatException e) {
                return wholeResult;
            }
        }
        if (total > 0 && start >= total) return new Result(0, -1, true, true);
        if (end < start) return new Result(0, -1, true, true);
        if (total > 0 && end > total - 1) end = total - 1;
        if (total <= 0) return new Result(start, end, true, false);
        return new Result(start, end, true, false);
    }

    /** 生成 Content-Range 头。长度未知时不生成。 */
    public static String contentRange(long start, long end, long total) {
        if (total <= 0) return null;
        return "bytes " + start + "-" + end + "/" + total;
    }

    /** 生成 416 的 Content-Range 头。 */
    public static String unsatisfiedContentRange(long total) {
        return "bytes */" + Math.max(0, total);
    }
}
