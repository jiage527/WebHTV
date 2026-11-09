package com.fongmi.android.tv.dlna;

import org.jupnp.support.model.Protocol;
import org.jupnp.support.model.ProtocolInfo;
import org.jupnp.support.model.ProtocolInfos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * DLNA 渲染器声明自己能播哪些内容（ConnectionManager 的 {@code GetProtocolInfo}）。
 *
 * <p><b>为什么必须显式声明。</b>jupnp 的 {@code ConnectionManagerService} 无参构造里
 * 传的是<b>空</b>的 {@code ProtocolInfos}（已用 {@code javap} 反编译随包
 * {@code org.jupnp.support-3.0.4.jar} 确认：无参构造最终走
 * {@code new ProtocolInfos(new ProtocolInfo[0])}）。空声明的后果是：DLNA 控制端
 * （手机上的投屏 App、Windows「播放到」等）在握手阶段问「你能播什么」，拿到空集合后
 * 会认为这台设备什么都不支持，于是干脆不把它列进可投屏目标，或选了之后报
 * 「格式不支持」。表现就是「明明开着接收投屏，手机却搜不到 / 投不过去」。
 *
 * <p><b>四个字段的正确写法。</b>UPnP 的 {@code ProtocolInfo} 是
 * {@code protocol:network:contentFormat:additionalInfo}：
 * <ul>
 *   <li>{@code protocol} 用 {@code http-get}，不用 {@code rtsp-rtp-udp}——本机的
 *       {@code SocketHttpStreamServer} 只提供 HTTP 取流，投屏方给的也是
 *       {@code setAVTransportURI} 里的 http 地址；声明 rtsp 会诱导控制端去尝试
 *       UDP 推送，而那条路径本机根本没有实现。</li>
 *   <li>{@code network} 用 {@code *}（不限 lan/wan）。</li>
 *   <li>{@code contentFormat} 是 MIME 类型。</li>
 *   <li>{@code additionalInfo} 只能是 {@code *} 或 {@code 名=值} 形式的参数串。
 *       这里一律用 {@code *}：把编解码器名字裸写进去（如 {@code H.264}）不符合
 *       该字段语法，严格的控制端会直接丢弃整条声明。</li>
 * </ul>
 *
 * <p><b>声明要覆盖用户真的会投过来的格式。</b>声明了却播不了，比不声明更糟——
 * 用户会觉得「投过去了但放不出来」。所以只列本机播放内核（Exo + mpv）确实支持的容器，
 * 另加一条 {@code video/*} / {@code audio/*} 通配兜底，让未逐条列举的格式仍可尝试，
 * 由播放内核在真正解码时决定成败。
 */
public final class DLNAProtocolDeclaration {

    /**
     * 本机确实支持的容器。
     *
     * <p>不列 {@code video/x-ms-wmv}/{@code rn-realmedia} 这类本机内核无法软解、
     * 又没有硬解保证的格式：声明它们只会把「控制端认为能投」变成
     * 「投过来之后播放失败」。
     */
    private static final String[] VIDEO = {
            "video/mp4",
            "video/x-matroska",
            "video/webm",
            "video/mpeg",
            "video/mp2t",
            "video/x-msvideo",
            "video/quicktime",
            "video/x-flv",
            "video/3gpp",
    };

    private static final String[] AUDIO = {
            "audio/mpeg",
            "audio/mp4",
            "audio/aac",
            "audio/flac",
            "audio/x-wav",
            "audio/ogg",
            "audio/x-ms-wma",
    };

    /** 通配用的字段值。 */
    private static final String WILDCARD = ProtocolInfo.WILDCARD;

    /**
     * 本机能接收的协议集合（渲染器角色下这才是决定性的那一份）。
     *
     * <p>先逐条列具体格式，再补两条类型通配兜底。去重保证不会重复出现同一串。
     */
    public static ProtocolInfos sink() {
        Set<String> seen = new LinkedHashSet<>();
        List<ProtocolInfo> infos = new ArrayList<>();
        for (String format : VIDEO) add(infos, seen, format);
        for (String format : AUDIO) add(infos, seen, format);
        // 类型通配放在最后：控制端一般按声明顺序匹配，具体格式优先命中更利于它选对 profile。
        add(infos, seen, "video/*");
        add(infos, seen, "audio/*");
        return new ProtocolInfos(infos.toArray(new ProtocolInfo[0]));
    }

    /**
     * 本机能对外提供的协议集合。
     *
     * <p>「接收投屏」场景下本机不主动推流，按 DLNA 惯例留空即可；仍返回空集合而不是
     * {@code null}，避免控制端求值时空指针。
     */
    public static ProtocolInfos source() {
        return new ProtocolInfos(new ProtocolInfo[0]);
    }

    /**
     * 生成协议串，便于单测直接断言文本。
     *
     * <p>与 {@code ProtocolInfo.toString()} 的输出格式一致
     * （{@code protocol:network:contentFormat:additionalInfo}），
     * 所以测试能独立验证我们声明的内容，而不必依赖 jupnp 的格式化行为。
     */
    public static String describe(String protocol, String network, String format, String additional) {
        return String.format(Locale.ROOT, "%s:%s:%s:%s", protocol, network, format, additional);
    }

    private static void add(List<ProtocolInfo> infos, Set<String> seen, String format) {
        String key = describe("http-get", WILDCARD, format, WILDCARD);
        if (!seen.add(key)) return;
        infos.add(new ProtocolInfo(Protocol.HTTP_GET, WILDCARD, format, WILDCARD));
    }

    private DLNAProtocolDeclaration() {
    }
}
