package com.fongmi.android.tv.netdisk;

import java.util.List;

/**
 * FTP 服务器应答的解析。纯逻辑、不依赖 Android，便于单测。
 *
 * <p><b>为什么单独抽出来。</b>FTP 的应答不是简单的一行：多行应答用
 * {@code 211-} 开头续行、以相同三位码加空格收尾，例如：
 *
 * <pre>
 * 211-Features:
 *  UTF8
 *  MDTM
 * 211 End
 * </pre>
 *
 * 如果只读一行就当读完，后面那些续行会被当成下一条命令的应答，从那一刻起
 * 整个控制连接的命令与应答就错位了——表现为「列目录时拿到的是上一条命令的结果」，
 * 很难查。所以续行规则必须按协议实现，并且要能单测。
 *
 * <p>所有三位码语义按 RFC 959：1xx 是中间态（还要继续读）、2xx 成功、3xx 需要更多
 * 信息、4xx 暂时失败、5xx 永久失败。
 */
final class FtpReply {

    private final int code;
    private final String text;

    FtpReply(int code, String text) {
        this.code = code;
        this.text = text == null ? "" : text;
    }

    int getCode() {
        return code;
    }

    String getText() {
        return text;
    }

    /** 是否属于某一类（例如 {@code isType(2)} 判断 2xx）。 */
    boolean isType(int hundred) {
        return code / 100 == hundred;
    }

    boolean isPositive() {
        return code >= 200 && code < 300;
    }

    /**
     * 是否是中间态应答（1xx）。
     *
     * <p>传数据时服务器会先回 125/150 表示「通道已打开、马上开始传」，传完再回 226。
     * 这两条都必须读掉，少读一条后面就错位。
     */
    boolean isPreliminary() {
        return isType(1);
    }

    /** 三位码是否是合法的应答开头。 */
    static boolean hasCode(String line) {
        if (line == null || line.length() < 4) return false;
        for (int i = 0; i < 3; i++) if (!Character.isDigit(line.charAt(i))) return false;
        // 第 4 个字符必须是 '-'（续行）或 ' '（结束行），否则不是合法应答。
        return line.charAt(3) == '-' || line.charAt(3) == ' ';
    }

    /** 是否是多行应答的首行：{@code 211-...}。 */
    static boolean isMultilineStart(String line) {
        return hasCode(line) && line.charAt(3) == '-';
    }

    /** 是否是多行应答的结束行：与首行同码、且第 4 个字符是空格。 */
    static boolean isMultilineEnd(String code, String line) {
        return line != null && line.length() >= 4 && line.startsWith(code) && line.charAt(3) == ' ';
    }

    static String codeOf(String line) {
        return line == null || line.length() < 3 ? "" : line.substring(0, 3);
    }

    /**
     * 合并一次应答的所有行。
     *
     * <p>文本取<b>结束行</b>的内容（协议里结束行承载人类可读的结论，续行多是特性列表
     * 之类的补充信息）；同时把续行也拼进去，好让调用方能在日志里看到全部。
     * 无法识别首位三位码时返回 {@code code = 0}，由调用方按失败处理。
     */
    static FtpReply parse(List<String> lines) {
        if (lines == null || lines.isEmpty()) return new FtpReply(0, "");
        String first = lines.get(0);
        if (!hasCode(first)) return new FtpReply(0, first == null ? "" : first.trim());
        int code = Integer.parseInt(codeOf(first));
        StringBuilder builder = new StringBuilder();
        for (int i = 1; i < lines.size(); i++) {
            // 续行可能以单个空格缩进，这里统一去掉首尾空白，避免拼接出难读的文本。
            String trimmed = lines.get(i) == null ? "" : lines.get(i).trim();
            if (trimmed.isEmpty()) continue;
            if (builder.length() > 0) builder.append(' ');
            builder.append(trimmed);
        }
        String tail = tailOf(lines.get(lines.size() - 1), code);
        if (!tail.isEmpty()) {
            if (builder.length() > 0) builder.append(' ');
            builder.append(tail);
        }
        return new FtpReply(code, builder.toString().trim());
    }

    /** 取结束行里三位码之后的描述部分。 */
    private static String tailOf(String line, int code) {
        if (line == null) return "";
        String prefix = String.valueOf(code);
        return line.length() > 4 && line.startsWith(prefix) ? line.substring(4).trim() : "";
    }

    @Override
    public String toString() {
        return code + " " + text;
    }
}
