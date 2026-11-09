package com.fongmi.android.tv.player.gif;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * GIF 编码器的往返验证。
 *
 * <p>关键点是<b>用独立的解码实现读回自己写出的字节</b>。只断言「写出来的字节长什么样」
 * 发现不了 LZW 位序、码宽切换或子块分块错误，而这些错误会让文件在真实解码器里打不开
 * 或画面错位。下面的 {@link GifReader} 是按 GIF 规范独立写的解码端，不复用编码端逻辑。
 *
 * <p>另有一次性交叉验证：把编码结果交给 JDK 的 {@code javax.imageio} 解码（Android
 * 单测的 android.jar 里没有 AWT，因此那条验证在 Gradle 之外单独执行）。
 */
public class GifEncoderTest {

    private static final int WIDTH = 8;
    private static final int HEIGHT = 6;

    /** 生成带渐变的像素，便于验证颜色是否被正确还原。 */
    private static int[] frame(int base) {
        int[] pixels = new int[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int r = (base + x * 8) & 0xFF;
                int g = (base + y * 8) & 0xFF;
                int b = (base + (x + y) * 4) & 0xFF;
                pixels[y * WIDTH + x] = 0xFF000000 | r << 16 | g << 8 | b;
            }
        }
        return pixels;
    }

    private static byte[] encode(List<int[]> frames, int maxColors, int delayCs) throws Exception {
        return encode(frames, maxColors, delayCs, WIDTH, HEIGHT);
    }

    private static byte[] encode(List<int[]> frames, int maxColors, int delayCs, int width, int height) throws Exception {
        GifPalette palette = GifPalette.build(frames, maxColors);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        GifEncoder encoder = new GifEncoder(output, width, height, palette, true);
        for (int[] pixels : frames) encoder.writeFrame(palette.index(pixels), delayCs);
        encoder.finish();
        return output.toByteArray();
    }

    @Test
    public void headerIsAGif89aWithGlobalColorTable() throws Exception {
        byte[] bytes = encode(List.of(frame(0)), 256, 10);
        assertEquals("GIF89a", new String(bytes, 0, 6, StandardCharsets.US_ASCII));
        // 位 7 置位表示存在全局色表。
        assertTrue((bytes[10] & 0x80) != 0);
        assertEquals(0x3B, bytes[bytes.length - 1] & 0xFF);
    }

    @Test
    public void decoderReadsBackFrameCountAndSize() throws Exception {
        GifReader gif = GifReader.parse(encode(List.of(frame(0), frame(30), frame(60)), 128, 8));
        assertEquals(WIDTH, gif.width);
        assertEquals(HEIGHT, gif.height);
        assertEquals(3, gif.frames.size());
        assertEquals(8, gif.delays.get(0).intValue());
    }

    @Test
    public void decodedPixelsMatchTheOriginalWithinQuantizationError() throws Exception {
        // 逐像素比较才能真正验证 LZW 位流（长度对但内容错也能骗过长度断言）。
        List<int[]> frames = List.of(frame(0), frame(40));
        GifPalette palette = GifPalette.build(frames, 256);
        int[] expected = palette.index(frames.get(0));
        GifReader gif = GifReader.parse(encode(frames, 256, 10));
        assertArrayEquals(expected, gif.frames.get(0));
    }

    @Test
    public void bothFramesDecodeToTheirOwnContent() throws Exception {
        List<int[]> frames = List.of(frame(0), frame(90));
        GifReader gif = GifReader.parse(encode(frames, 256, 10));
        assertArrayEquals(GifPalette.build(frames, 256).index(frames.get(0)), gif.frames.get(0));
        assertArrayEquals(GifPalette.build(frames, 256).index(frames.get(1)), gif.frames.get(1));
        // 两帧确实不同，避免「两帧都写成了同一张」也能通过。
        assertTrue(!Arrays.equals(gif.frames.get(0), gif.frames.get(1)));
    }

    @Test
    public void everyFrameDecodesWhenDictionaryOverflows() throws Exception {
        // 大图 + 噪声会把 LZW 字典写满，专门覆盖 Clear Code 重置路径。
        int size = 120;
        int[] noisy = new int[size * size];
        for (int i = 0; i < noisy.length; i++) {
            long hash = (i * 2654435761L) >>> 8;
            noisy[i] = 0xFF000000 | (int) (hash & 0xFF) << 16 | (int) (hash >> 8 & 0xFF) << 8 | (int) (hash >> 16 & 0xFF);
        }
        List<int[]> frames = new ArrayList<>();
        for (int i = 0; i < 3; i++) frames.add(noisy);
        GifPalette palette = GifPalette.build(frames, 256);
        byte[] bytes = encode(frames, 256, 5, size, size);
        GifReader gif = GifReader.parse(bytes);
        assertEquals(3, gif.frames.size());
        assertArrayEquals(palette.index(noisy), gif.frames.get(2));
    }

    @Test
    public void loopExtensionIsPresentForLoopingGifs() throws Exception {
        byte[] bytes = encode(List.of(frame(0), frame(20)), 64, 10);
        assertTrue(new String(bytes, StandardCharsets.ISO_8859_1).contains("NETSCAPE2.0"));
    }

    @Test
    public void singleColorImageStillEncodesAndDecodes() throws Exception {
        int[] flat = new int[WIDTH * HEIGHT];
        Arrays.fill(flat, 0xFF3366CC);
        GifReader gif = GifReader.parse(encode(List.of(flat), 2, 100));
        assertEquals(1, gif.frames.size());
        int index = gif.frames.get(0)[0];
        int actual = gif.palette[index];
        assertEquals(0x33, actual >> 16 & 0xFF, 4);
        assertEquals(0x66, actual >> 8 & 0xFF, 4);
        assertEquals(0xCC, actual & 0xFF, 4);
    }

    @Test
    public void mismatchedFrameSizeIsRejected() throws Exception {
        GifPalette palette = GifPalette.build(List.of(frame(0)), 64);
        GifEncoder encoder = new GifEncoder(new ByteArrayOutputStream(), WIDTH, HEIGHT, palette, true);
        try {
            encoder.writeFrame(new int[]{1, 2, 3}, 10);
            throw new AssertionError("expected IOException");
        } catch (java.io.IOException expected) {
            // 尺寸不符必须直接报错，否则会写出解码器吃不下的文件。
        }
    }

    @Test
    public void invalidCanvasSizeIsRejected() {
        try {
            new GifEncoder(new ByteArrayOutputStream(), 0, HEIGHT, GifPalette.build(List.of(frame(0)), 64), true);
            throw new AssertionError("expected IOException");
        } catch (java.io.IOException expected) {
            // 宽或高为 0 无法生成有效 GIF。
        }
    }

    /**
     * 独立的 GIF 解码端：只按规范实现录屏所需的子集。
     *
     * <p>刻意与编码端分开写（不同的位读取方向、不同的字典结构），这样任一侧写错都会
     * 导致往返失败，而不是两边一致地错。
     */
    static final class GifReader {

        final int width;
        final int height;
        final int[] palette;
        final List<int[]> frames = new ArrayList<>();
        final List<Integer> delays = new ArrayList<>();

        private GifReader(int width, int height, int[] palette) {
            this.width = width;
            this.height = height;
            this.palette = palette;
        }

        static GifReader parse(byte[] bytes) throws Exception {
            Cursor cursor = new Cursor(bytes);
            assertEquals("GIF89a", cursor.string(6));            int width = cursor.shortLE();
            int height = cursor.shortLE();
            int packed = cursor.byteValue();
            cursor.byteValue();
            cursor.byteValue();
            if ((packed & 0x80) == 0) throw new IllegalStateException("No global color table");
            int tableSize = 1 << ((packed & 0x07) + 1);
            int[] palette = new int[tableSize];
            for (int i = 0; i < tableSize; i++) {
                int r = cursor.byteValue();
                int g = cursor.byteValue();
                int b = cursor.byteValue();
                palette[i] = 0xFF000000 | r << 16 | g << 8 | b;
            }
            GifReader reader = new GifReader(width, height, palette);
            int delay = 0;
            while (true) {
                int marker = cursor.byteValue();
                if (marker == 0x3B) break;
                if (marker == 0x21) {
                    int label = cursor.byteValue();
                    if (label == 0xF9) {
                        // 图形控制扩展：块长 + 打包位 + 延时(2) + 透明色索引 + 终止符
                        cursor.byteValue();
                        cursor.byteValue();
                        delay = cursor.shortLE();
                        cursor.byteValue();
                        cursor.byteValue();
                    } else {
                        // 其它扩展（含 NETSCAPE2.0 循环）由子块自描述，一次跳完。
                        cursor.skipSubBlocks();
                    }
                    continue;
                }
                if (marker != 0x2C) throw new IllegalStateException("Unexpected marker " + marker);
                cursor.shortLE();
                cursor.shortLE();
                cursor.shortLE();
                cursor.shortLE();
                int flags = cursor.byteValue();
                if ((flags & 0x80) != 0) throw new IllegalStateException("Local color table unsupported");
                if ((flags & 0x40) != 0) throw new IllegalStateException("Interlace unsupported");
                reader.frames.add(reader.readImage(cursor));
                reader.delays.add(delay);
            }
            return reader;
        }

        private int[] readImage(Cursor cursor) throws Exception {
            int minCodeSize = cursor.byteValue();
            byte[] data = cursor.subBlocks();
            int clearCode = 1 << minCodeSize;
            int eoiCode = clearCode + 1;
            int codeSize = minCodeSize + 1;
            List<int[]> table = new ArrayList<>();
            reset(table, clearCode);
            int[] output = new int[width * height];
            int position = 0;
            int[] previous = null;
            BitCursor bits = new BitCursor(data);
            while (bits.remaining() >= codeSize) {
                int code = bits.read(codeSize);
                if (code == clearCode) {
                    reset(table, clearCode);
                    codeSize = minCodeSize + 1;
                    previous = null;
                    continue;
                }
                if (code == eoiCode) break;
                // code == table.size() 是 KwKwK：该码字刚由上一轮前缀定义，
                // 解码端必须先把它推出来，才能补上对应的字典项。
                int[] entry = code < table.size() ? table.get(code) : concat(previous, previous[0]);
                for (int value : entry) {
                    if (position >= output.length) throw new IllegalStateException("Too much pixel data");
                    output[position++] = value;
                }
                if (previous != null && table.size() < 4096) {
                    table.add(concat(previous, entry[0]));
                    // 与编码端逐步对齐：编码端写完 C(i) 后定义条目编号 k=i+5，
                    // 并把 nextCode 推到 i+6，判据是 nextCode > 2^codeSize。
                    // 解码端读完 C(i) 时 table.size() 正好是 i+5，
                    // 因此等价判据是 table.size() + 1 > 2^codeSize。
                    if (table.size() >= (1 << codeSize) && codeSize < 12) codeSize++;
                }
                previous = entry;
            }
            if (position != output.length) throw new IllegalStateException("Decoded " + position + " of " + output.length);
            return output;
        }

        /** 重置字典到初始状态。 */
        private static void reset(List<int[]> table, int clearCode) {
            table.clear();
            for (int i = 0; i < clearCode; i++) table.add(new int[]{i});
            table.add(new int[0]);
            table.add(new int[0]);
        }

        private static int[] concat(int[] prefix, int suffix) {
            int[] result = Arrays.copyOf(prefix, prefix.length + 1);
            result[prefix.length] = suffix;
            return result;
        }

        /** 字节级游标。 */
        static final class Cursor {

            private final byte[] bytes;
            private int position;

            Cursor(byte[] bytes) {
                this.bytes = bytes;
            }

            int byteValue() {
                if (position >= bytes.length) throw new IllegalStateException("Unexpected end of GIF");
                return bytes[position++] & 0xFF;
            }

            int shortLE() {
                int low = byteValue();
                return low | byteValue() << 8;
            }

            String string(int length) {
                StringBuilder builder = new StringBuilder();
                for (int i = 0; i < length; i++) builder.append((char) byteValue());
                return builder.toString();
            }

            void skipSubBlocks() {
                while (true) {
                    int size = byteValue();
                    if (size == 0) return;
                    position += size;
                }
            }

            byte[] subBlocks() {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                while (true) {
                    int size = byteValue();
                    if (size == 0) break;
                    output.write(bytes, position, size);
                    position += size;
                }
                return output.toByteArray();
            }
        }

        /** 按 LSB 优先读取变长码。 */
        static final class BitCursor {

            private final byte[] data;
            private int bitPosition;

            BitCursor(byte[] data) {
                this.data = data;
            }

            int remaining() {
                return data.length * 8 - bitPosition;
            }

            int read(int count) {
                int value = 0;
                for (int i = 0; i < count; i++) {
                    int bit = data[bitPosition >> 3] >> (bitPosition & 7) & 1;
                    value |= bit << i;
                    bitPosition++;
                }
                return value;
            }
        }
    }
}
