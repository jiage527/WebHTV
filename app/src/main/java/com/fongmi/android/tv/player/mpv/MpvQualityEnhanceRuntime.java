package com.fongmi.android.tv.player.mpv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 画质增强选项的「运行时下发 / 还原」账本。
 *
 * <p>纯逻辑、不依赖 Android，便于单测。
 *
 * <p><b>为什么需要这层。</b>画质增强原先只能在构造 {@code MpvPlayer} 时下发选项，
 * 所以用户每点一次档位按钮，程序就把整台播放器拆掉重建——重新拉流、丢缓冲、
 * 丢掉已解码的帧，用户看到的是「从头加载一遍再跳回进度」。
 * 而 mpv 的这批渲染属性（{@code scale}/{@code scale-blur}/{@code deband}/…）属于
 * {@code gl_video_conf}，其 {@code change_flags} 是 {@code UPDATE_VIDEO}，
 * 运行时改会即时生效，本来不需要重建。
 *
 * <p><b>为什么不能只写不还原。</b>用户可能在 {@code mpv.conf} 里自己设过缩放核。
 * 如果我们下了 {@code scale=ewa_lanczossharp}，用户关掉增强后我们却不把原值写回去，
 * 就等于永久改掉了用户的配置——而且没有任何提示。所以第一次写某个键之前先记下它
 * 当时的原值，关闭时写回。这一步在 {@link Entry#captured()} 上有明确标记：
 * <b>没记到原值的键不会被还原</b>（宁可不写，也不写一个猜出来的值）。
 */
public final class MpvQualityEnhanceRuntime {

    /**
     * mpv 对这批选项的默认值（取自 {@code gl_video_opts_def}）。
     *
     * <p>只在「原值没读到」时当作兜底，正常情况下还原用的是实测到的原值。
     * 这里的每一项都必须与随包 mpv 的默认值一致，否则就会误改用户配置。
     */
    private static final Map<String, String> DEFAULTS = buildDefaults();

    private MpvQualityEnhanceRuntime() {
    }

    /**
     * 一次下发的账本条目。
     *
     * @param name     选项名
     * @param value    本次要写入的值
     * @param original 首次写入前读到的原值；{@code null} 表示还没读到
     */
    public record Entry(String name, String value, String original) {

        public boolean captured() {
            return original != null;
        }
    }

    /**
     * 算出这次要写入的原生键值对，并给出应该还原的键。
     *
     * @param previous 上一次下发留下的账本（键 → 原值）；可为空
     * @param target   这次希望生效的选项；空表示「全部关掉」
     * @param read     从 mpv 读当前值的函数；返回 {@code null} 表示读不到
     */
    public static Plan plan(Map<String, String> previous,
                            Map<String, String> target,
                            Reader read) {
        Map<String, String> wanted = target == null ? Map.of() : target;
        Map<String, String> kept = new LinkedHashMap<>();
        List<Entry> writes = new ArrayList<>();

        // applied = 上一次下发时记下的「键 → 该键的原值」。
        Map<String, String> applied = new LinkedHashMap<>();
        if (previous != null) {
            for (Map.Entry<String, String> entry : previous.entrySet()) {
                if (isBlank(entry.getKey()) || isBlank(entry.getValue())) continue;
                applied.put(entry.getKey().trim().toLowerCase(Locale.ROOT), entry.getValue());
            }
        }

        for (Map.Entry<String, String> entry : wanted.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (isBlank(name) || isBlank(value)) continue;
            String key = name.trim().toLowerCase(Locale.ROOT);
            String original = applied.get(key);
            if (original == null && read != null) original = read.read(key);
            if (original == null) original = DEFAULTS.get(key);
            // 记不到原值就不写这个键：写下去之后没法还原，等于改坏用户配置。
            if (original == null) continue;
            kept.put(key, original);
            writes.add(new Entry(key, value, original));
        }

        List<Entry> restore = new ArrayList<>();
        for (Map.Entry<String, String> entry : applied.entrySet()) {
            String name = entry.getKey();
            if (kept.containsKey(name)) continue;
            restore.add(new Entry(name, entry.getValue(), entry.getValue()));
        }
        return new Plan(kept, writes, restore);
    }

    /**
     * 一次下发的执行计划。
     *
     * @param bookkeeping 执行后应保留的账本（键 → 原值）
     * @param writes      要写入的原生键值对
     * @param restore     要写回原值的键值对（这些键本次不再生效）
     */
    public record Plan(Map<String, String> bookkeeping,
                       List<Entry> writes,
                       List<Entry> restore) {

        public Plan {
            bookkeeping = bookkeeping == null ? Map.of() : Map.copyOf(bookkeeping);
            writes = writes == null ? List.of() : List.copyOf(writes);
            restore = restore == null ? List.of() : List.copyOf(restore);
        }

        /** 本次是否什么都没做（调用方据此跳过所有原生调用）。 */
        public boolean isEmpty() {
            return writes.isEmpty() && restore.isEmpty();
        }
    }

    /** 从 mpv 读当前属性值的读口。 */
    public interface Reader {

        /** @return 当前值；读不到返回 {@code null} */
        String read(String name);
    }

    /** mpv 默认值表。键名必须与实际发出的选项名逐字一致。 */
    private static Map<String, String> buildDefaults() {
        Map<String, String> defaults = new LinkedHashMap<>();
        // gl_video_opts_def：SCALER_SCALE 默认 lanczos，SCALER_CSCALE 默认 inherit
        // （inherit = 跟随 scale），SCALER_DSCALE 默认 hermite。
        defaults.put("scale", "lanczos");
        defaults.put("cscale", "inherit");
        defaults.put("dscale", "hermite");
        defaults.put("scale-antiring", "0");
        defaults.put("cscale-antiring", "0");
        // kernel blur / taper 默认 0；文档说明「设为 0 表示使用该核自己推荐的系数」。
        defaults.put("scale-blur", "0");
        defaults.put("cscale-blur", "0");
        // 旧 vo=gpu 的 unsharp mask 默认关闭。
        defaults.put("sharpen", "0");
        // gl_video_opts_def：dither_algo 默认 fruit、dither_size 默认 6、
        // temporal_dither 默认关（下面的 temporal-dither 是它的开关）。
        defaults.put("deband", "no");
        defaults.put("temporal-dither", "no");
        defaults.put("dither-size-fruit", "6");
        return Map.copyOf(defaults);
    }

    /**
     * 一个键是否属于本账本管理的选项。
     *
     * <p>用来兜住「有人往 {@code build()} 里加了新选项却忘了加默认值」：
     * 那样还原时会因为找不到默认值而跳过，用户的配置就被悄悄改掉了。
     */
    public static boolean hasDefault(String name) {
        return name != null && DEFAULTS.containsKey(name.trim().toLowerCase(Locale.ROOT));
    }

    public static Set<String> managedNames() {
        return new LinkedHashSet<>(DEFAULTS.keySet());
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
