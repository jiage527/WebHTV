package com.fongmi.android.tv.player.gif;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * GIF 调色板：用中位切分（median cut）把一批画面压到有限色数。
 *
 * <p>GIF 每帧最多 256 色，录屏时先合并若干帧的像素统计出一张全局色表，再把每帧
 * 映射到该色表。只对<b>采样</b>像素做切分：调色板质量对采样量并不敏感，但构建
 * 速度会差一个量级。
 *
 * <p>不依赖 Android 类型（输入是 ARGB 数组），因此可以直接用 JVM 单测验证量化
 * 与映射结果。
 */
public final class GifPalette {

    /** 构建调色板时最多采样的像素数。 */
    private static final int SAMPLE_LIMIT = 1 << 15;
    /**
     * 颜色查找缓存的槽位数：每通道 5 位。
     *
     * <p>把颜色压缩到 5 位/通道再查表是 GIF 编码器的通行做法；同一槽内的颜色共用
     * 最近的调色板项，误差不超过每通道 8，对调色板有限的 GIF 不可见。这样每帧的映射
     * 是 O(像素) 而不是 O(像素 × 色数)。
     */
    private static final int CACHE_SIZE = 1 << 15;

    private final int[] colors;
    private final int[] cache;
    private final int cacheBits;

    private GifPalette(int[] colors) {
        this.colors = colors;
        // 颜色很少时缩小缓存，避免为几十色的小调色板白白分配 128KB。
        this.cacheBits = colors.length <= 16 ? 3 : 5;
        this.cache = new int[1 << (cacheBits * 3)];
        Arrays.fill(cache, -1);
    }

    /**
     * 构建调色板。
     *
     * @param frames    若干帧的 ARGB 像素
     * @param maxColors 目标色数，会被限制在 2–256
     */
    public static GifPalette build(List<int[]> frames, int maxColors) {
        int limit = Math.min(256, Math.max(2, maxColors));
        int[] samples = sample(frames);
        if (samples.length == 0) return new GifPalette(new int[]{0xFF000000});
        List<Box> boxes = medianCut(samples, limit);
        int[] colors = new int[boxes.size()];
        for (int i = 0; i < boxes.size(); i++) colors[i] = boxes.get(i).average(samples);
        return new GifPalette(colors);
    }

    /** 调色板颜色（不透明 ARGB）。 */
    public int[] colors() {
        return colors;
    }

    public int size() {
        return colors.length;
    }

    /** 表示全部下标所需的最小位数，GIF 要求色表尺寸至少为 2。 */
    public int bitsNeeded() {
        int bits = 1;
        while ((1 << bits) < colors.length) bits++;
        return Math.max(2, bits);
    }

    /** 把整帧像素映射成调色板下标。 */
    public int[] index(int[] pixels) {
        int[] result = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) result[i] = indexOf(pixels[i]);
        return result;
    }

    /** 找出与给定颜色最接近的调色板下标。 */
    public int indexOf(int argb) {
        int slot = slot(argb);
        int cached = cache[slot];
        if (cached >= 0) return cached;
        int best = nearest(argb);
        cache[slot] = best;
        return best;
    }

    private int slot(int argb) {
        int mask = (1 << cacheBits) - 1;
        int shift = 8 - cacheBits;
        int r = ((argb >> 16) & 0xFF) >> shift & mask;
        int g = ((argb >> 8) & 0xFF) >> shift & mask;
        int b = (argb & 0xFF) >> shift & mask;
        return (r << (cacheBits * 2)) | (g << cacheBits) | b;
    }

    private int nearest(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < colors.length; i++) {
            int color = colors[i];
            int dr = r - ((color >> 16) & 0xFF);
            int dg = g - ((color >> 8) & 0xFF);
            int db = b - (color & 0xFF);
            // 人眼对绿色最敏感，按亮度权重加权比纯欧氏距离更贴近观感。
            int distance = dr * dr * 2 + dg * dg * 4 + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /** 等步长采样：帧再多也只需均匀抽样反映整体配色。 */
    private static int[] sample(List<int[]> frames) {
        if (frames == null || frames.isEmpty()) return new int[0];
        int total = 0;
        for (int[] frame : frames) if (frame != null) total += frame.length;
        if (total == 0) return new int[0];
        int step = Math.max(1, total / Math.min(total, SAMPLE_LIMIT));
        int[] result = new int[(total + step - 1) / step];
        int index = 0;
        int counter = 0;
        for (int[] frame : frames) {
            if (frame == null) continue;
            for (int pixel : frame) {
                if (counter++ % step != 0) continue;
                if (index == result.length) return result;
                result[index++] = pixel;
            }
        }
        return index == result.length ? result : Arrays.copyOf(result, index);
    }

    /** 反复切开跨度最大的盒，直到达到色数上限或无法再分。 */
    private static List<Box> medianCut(int[] pixels, int limit) {
        List<Box> boxes = new ArrayList<>();
        boxes.add(new Box(pixels, 0, pixels.length));
        while (boxes.size() < limit) {
            Box target = widest(boxes);
            if (target == null) break;
            Box right = target.split();
            if (right == null) break;
            boxes.add(right);
        }
        return boxes;
    }

    /** 找单通道跨度最大的可切分盒；没有则返回 null。 */
    private static Box widest(List<Box> boxes) {
        Box best = null;
        for (Box box : boxes) {
            if (box.size() < 2) continue;
            if (best == null || box.range() > best.range()) best = box;
        }
        return best;
    }

    /**
     * 中位切分的一个颜色盒，持有样本数组上的一段区间。
     *
     * <p>跨度按<b>单通道</b>计算而不是三维距离：切分只能沿一个通道做，
     * 取该通道的实际取值范围才能保证切开后两半都有意义。
     */
    private static final class Box {

        private final int[] pixels;
        private int start;
        private int end;

        Box(int[] pixels, int start, int end) {
            this.pixels = pixels;
            this.start = start;
            this.end = end;
        }

        int size() {
            return end - start;
        }

        int range() {
            int red = rangeOf(16);
            int green = rangeOf(8);
            int blue = rangeOf(0);
            return Math.max(red, Math.max(green, blue));
        }

        /** 沿跨度最大的通道排序后从中间切开；无法再分时返回 null。 */
        Box split() {
            if (size() < 2) return null;
            int shift = widestChannel();
            if (rangeOf(shift) == 0) return null;
            sortByChannel(shift);
            int mid = start + size() / 2;
            if (mid <= start || mid >= end) return null;
            Box right = new Box(pixels, mid, end);
            this.end = mid;
            return right;
        }

        private int widestChannel() {
            int red = rangeOf(16);
            int green = rangeOf(8);
            int blue = rangeOf(0);
            if (red >= green && red >= blue) return 16;
            return green >= blue ? 8 : 0;
        }

        int rangeOf(int shift) {
            int min = 255;
            int max = 0;
            for (int i = start; i < end; i++) {
                int value = pixels[i] >> shift & 0xFF;
                if (value < min) min = value;
                if (value > max) max = value;
            }
            return max - min;
        }

        /**
         * 按单通道做计数排序。颜色值只有 256 档，计数排序是 O(n) 且不需要装箱，
         * 比通用比较排序快得多。
         */
        private void sortByChannel(int shift) {
            int length = size();
            int[] count = new int[256];
            for (int i = start; i < end; i++) count[pixels[i] >> shift & 0xFF]++;
            int sum = 0;
            for (int i = 0; i < count.length; i++) {
                int bucket = count[i];
                count[i] = sum;
                sum += bucket;
            }
            int[] sorted = new int[length];
            for (int i = start; i < end; i++) {
                int key = pixels[i] >> shift & 0xFF;
                sorted[count[key]++] = pixels[i];
            }
            System.arraycopy(sorted, 0, pixels, start, length);
        }

        /** 盒内像素的均值颜色。 */
        int average(int[] source) {
            long red = 0;
            long green = 0;
            long blue = 0;
            for (int i = start; i < end; i++) {
                int pixel = source[i];
                red += (pixel >> 16) & 0xFF;
                green += (pixel >> 8) & 0xFF;
                blue += pixel & 0xFF;
            }
            int count = Math.max(1, size());
            return 0xFF000000 | (int) (red / count) << 16 | (int) (green / count) << 8 | (int) (blue / count);
        }
    }
}
