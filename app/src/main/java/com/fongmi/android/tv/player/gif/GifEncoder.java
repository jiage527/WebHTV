package com.fongmi.android.tv.player.gif;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 最小 GIF89a 编码器：单张全局色表 + 多帧 + 无限循环。
 *
 * <p>只实现录屏需要的部分：不做局部色表、不做透明色、不做隔行扫描。GIF 的文件结构
 * 允许边编码边写流，所以帧数据不必全部留在内存里；但色表是全局的，必须<b>先</b>由
 * 全部帧统计出来（见 {@link GifPalette}），因此调用方需要先收集帧再写文件。
 *
 * <p>LZW 部分按 GIF 规范实现：码字 LSB 优先、字典上限 4096、写满后发 Clear Code
 * 重置字典。这里不复用 {@code java.util.zip} 的 Deflate —— GIF 用的是 LZW 而不是
 * Deflate，两者不兼容。
 *
 * <p>不依赖 Android 类型，可直接在 JVM 单测里做往返解码验证。
 */
public final class GifEncoder {

    private static final int MAX_CODE = 4096;
    /** 字典哈希表大小，取质数以减少线性探测的聚集。 */
    private static final int HASH_SIZE = 9973;

    private final OutputStream output;
    private final int width;
    private final int height;
    private final int minCodeSize;
    private final int transparentIndex;
    private final byte[] subBlock = new byte[255];

    private int subBlockLength;
    private int bitBuffer;
    private int bitCount;
    private boolean headerWritten;
    private boolean finished;

    /**
     * @param output     目标流；由调用方负责关闭
     * @param width      画布宽（像素）
     * @param height     画布高（像素）
     * @param palette    全局色表
     * @param loop       true 表示无限循环
     */
    public GifEncoder(OutputStream output, int width, int height, GifPalette palette, boolean loop) throws IOException {
        if (width <= 0 || height <= 0) throw new IOException("Invalid GIF size " + width + "x" + height);
        this.output = output;
        this.width = width;
        this.height = height;
        this.minCodeSize = palette.bitsNeeded();
        this.transparentIndex = 0;
        writeHeader(palette, loop);
    }

    private void writeHeader(GifPalette palette, boolean loop) throws IOException {
        int bits = palette.bitsNeeded();
        int tableSize = 1 << bits;
        output.write('G');
        output.write('I');
        output.write('F');
        output.write('8');
        output.write('9');
        output.write('a');
        writeShort(width);
        writeShort(height);
        // 位 7: 有全局色表；位 6-4: 颜色深度；位 3: 未排序；位 2-0: 色表尺寸
        output.write(0x80 | (bits - 1));
        output.write(0);
        output.write(0);
        int[] colors = palette.colors();
        for (int i = 0; i < tableSize; i++) {
            int color = i < colors.length ? colors[i] : 0xFF000000;
            output.write(color >> 16 & 0xFF);
            output.write(color >> 8 & 0xFF);
            output.write(color & 0xFF);
        }
        if (loop) writeLoopExtension();
        headerWritten = true;
    }

    /** NETSCAPE2.0 应用扩展：循环次数 0 表示无限。 */
    private void writeLoopExtension() throws IOException {
        output.write(0x21);
        output.write(0xFF);
        output.write(11);
        for (byte value : "NETSCAPE2.0".getBytes(java.nio.charset.StandardCharsets.US_ASCII)) output.write(value);
        output.write(3);
        output.write(1);
        writeShort(0);
        output.write(0);
    }

    /**
     * 写入一帧。
     *
     * @param indices 调色板下标，长度必须等于 width × height
     * @param delayCs 该帧显示时长（1/100 秒）
     */
    public void writeFrame(int[] indices, int delayCs) throws IOException {
        if (finished) throw new IOException("GIF already finished");
        if (indices.length != width * height) throw new IOException("Frame size " + indices.length + " != " + width * height);
        writeGraphicControl(delayCs);
        writeImageDescriptor();
        writeLzw(indices);
    }

    /** 写结束符。必须调用，否则文件在多数解码器里都是截断的。 */
    public void finish() throws IOException {
        if (finished) return;
        output.write(0x3B);
        output.flush();
        finished = true;
    }

    private void writeGraphicControl(int delayCs) throws IOException {
        output.write(0x21);
        output.write(0xF9);
        output.write(4);
        // 位 2: 保留处理方式（1 = 恢复背景色），其余为 0
        output.write(0x04);
        writeShort(Math.max(0, delayCs));
        output.write(transparentIndex);
        output.write(0);
    }

    private void writeImageDescriptor() throws IOException {
        output.write(0x2C);
        writeShort(0);
        writeShort(0);
        writeShort(width);
        writeShort(height);
        // 无局部色表，不隔行扫描
        output.write(0);
    }

    /**
     * LZW 压缩一帧像素。
     *
     * <p>字典用「前缀码 + 后缀字节」作键。因为最多 4096 个码字，键可以放进一个 int，
     * 用开放寻址的定长数组做字典，避免每个像素都产生装箱对象。
     */
    private void writeLzw(int[] indices) throws IOException {
        output.write(minCodeSize);
        int clearCode = 1 << minCodeSize;
        int eoiCode = clearCode + 1;
        int nextCode = eoiCode + 1;
        int codeSize = minCodeSize + 1;
        int[] keys = new int[HASH_SIZE];
        int[] values = new int[HASH_SIZE];
        java.util.Arrays.fill(keys, -1);

        bitBuffer = 0;
        bitCount = 0;
        subBlockLength = 0;

        writeCode(clearCode, codeSize);
        if (indices.length == 0) {
            writeCode(eoiCode, codeSize);
            flushBits();
            flushSubBlock(true);
            return;
        }
        int prefix = indices[0] & 0xFF;
        for (int i = 1; i < indices.length; i++) {
            int suffix = indices[i] & 0xFF;
            int key = (prefix << 8) | suffix;
            int slot = key % HASH_SIZE;
            int found = -1;
            while (keys[slot] != -1) {
                if (keys[slot] == key) {
                    found = values[slot];
                    break;
                }
                slot = (slot + 1) % HASH_SIZE;
            }
            if (found >= 0) {
                prefix = found;
                continue;
            }
            writeCode(prefix, codeSize);
            if (nextCode < MAX_CODE) {
                keys[slot] = key;
                values[slot] = nextCode++;
                // 码字数量超过当前位宽能表示的范围时加宽，注意是在<b>新增之后</b>判断。
                if (nextCode > (1 << codeSize) && codeSize < 12) codeSize++;
            } else {
                // 字典写满：发 Clear Code 让解码端同步重置，随后继续编码。
                writeCode(clearCode, codeSize);
                java.util.Arrays.fill(keys, -1);
                nextCode = eoiCode + 1;
                codeSize = minCodeSize + 1;
            }
            prefix = suffix;
        }
        writeCode(prefix, codeSize);
        writeCode(eoiCode, codeSize);
        flushBits();
        flushSubBlock(true);
    }

    /** 按 LSB 优先写入变长码字。 */
    private void writeCode(int code, int codeSize) throws IOException {
        bitBuffer |= code << bitCount;
        bitCount += codeSize;
        while (bitCount >= 8) {
            appendByte(bitBuffer & 0xFF);
            bitBuffer >>= 8;
            bitCount -= 8;
        }
    }

    /** 补齐最后一个字节。 */
    private void flushBits() throws IOException {
        if (bitCount > 0) {
            appendByte(bitBuffer & 0xFF);
            bitBuffer = 0;
            bitCount = 0;
        }
    }

    /** 累积到 255 字节就发一个子块，这是 GIF 的分块格式要求。 */
    private void appendByte(int value) throws IOException {
        subBlock[subBlockLength++] = (byte) value;
        if (subBlockLength == subBlock.length) flushSubBlock(false);
    }

    private void flushSubBlock(boolean force) throws IOException {
        if (subBlockLength == 0) {
            if (force) output.write(0);
            return;
        }
        output.write(subBlockLength);
        output.write(subBlock, 0, subBlockLength);
        subBlockLength = 0;
        if (force) output.write(0);
    }

    private void writeShort(int value) throws IOException {
        output.write(value & 0xFF);
        output.write(value >> 8 & 0xFF);
    }
}
