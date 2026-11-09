package com.fongmi.android.tv.dlna;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.jupnp.support.model.ProtocolInfo;
import org.jupnp.support.model.ProtocolInfos;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * DLNA 协议声明测试。
 *
 * <p><b>为什么这组测试有价值。</b>声明内容会直接决定控制端「能不能搜到本机」以及
 * 「投过来能不能播」。而且这类问题在代码评审里看不出来——一串看似合理的 MIME 字符串
 * 实际可能完全不合法（字段位置放错、语法不符合 DPF），客户端只会默默把所有格式
 * 都忽略掉，现象是「开着接收投屏但手机里搜不到」。
 */
public class DLNAProtocolDeclarationTest {

    /**
     * 这是本测试最要紧的一条：jupnp 的无参构造会给空声明，
     * 而空声明会让控制端认为本机什么都不支持。
     */
    @Test
    public void sinkIsNotEmpty() {
        assertFalse(DLNAProtocolDeclaration.sink().isEmpty());
    }

    /** 四条字段都必须有值，缺一不可（空字段的声明会被控制端丢弃）。 */
    @Test
    public void everyEntryHasFourFields() {
        for (ProtocolInfo info : DLNAProtocolDeclaration.sink()) {
            assertTrue("protocol 不能为空", info.getProtocol() != null);
            assertFalse("network 不能为空", info.getNetwork() == null || info.getNetwork().isEmpty());
            assertFalse("contentFormat 不能为空", info.getContentFormat() == null || info.getContentFormat().isEmpty());
            assertFalse("additionalInfo 不能为空", info.getAdditionalInfo() == null || info.getAdditionalInfo().isEmpty());
        }
    }

    /**
     * 协议必须是 http-get。
     *
     * <p>本机的 {@code SocketHttpStreamServer} 只提供 HTTP 取流；声明
     * {@code rtsp-rtp-udp} 会让控制端去尝试 UDP 推送，而那条路径根本没实现。
     */
    @Test
    public void usesHttpGetNotRtsp() {
        for (ProtocolInfo info : DLNAProtocolDeclaration.sink()) {
            assertEquals("http-get", info.getProtocol().toString());
        }
    }

    /**
     * additionalInfo 只能是 {@code *} 或 {@code 名=值}。
     *
     * <p>把编解码器名字裸写进去（如 {@code H.264}）不符合该字段语法，
     * 严格的实现会丢弃整条声明。
     */
    @Test
    public void additionalInfoIsWildcardOrParameterList() {
        for (ProtocolInfo info : DLNAProtocolDeclaration.sink()) {
            String additional = info.getAdditionalInfo();
            assertTrue("非法 additionalInfo: " + additional,
                    "*".equals(additional) || additional.contains("="));
        }
    }

    /** 用户最可能投送的格式必须在列。 */
    @Test
    public void coversCommonFormats() {
        Set<String> formats = new HashSet<>();
        for (ProtocolInfo info : DLNAProtocolDeclaration.sink()) formats.add(info.getContentFormat());
        assertTrue(formats.contains("video/mp4"));
        assertTrue(formats.contains("video/x-matroska"));
        assertTrue(formats.contains("audio/mpeg"));
        assertTrue(formats.contains("audio/flac"));
    }

    /** 通配兜底：未逐条列举的格式也允许尝试，由播放内核决定成败。 */
    @Test
    public void hasWildcardFallback() {
        Set<String> formats = new HashSet<>();
        for (ProtocolInfo info : DLNAProtocolDeclaration.sink()) formats.add(info.getContentFormat());
        assertTrue(formats.contains("video/*"));
        assertTrue(formats.contains("audio/*"));
    }

    /** 不得出现重复条目：重复声明会浪费控制端的匹配时间，也可能被当成异常。 */
    @Test
    public void hasNoDuplicates() {
        ProtocolInfos infos = DLNAProtocolDeclaration.sink();
        Set<String> seen = new HashSet<>();
        for (ProtocolInfo info : infos) {
            assertTrue("重复条目: " + info.toString(), seen.add(info.toString()));
        }
        assertEquals(infos.size(), seen.size());
    }

    /**
     * source 必须返回空集合而不是 null。
     *
     * <p>「接收投屏」场景下本机不主动推流，留空是正确的；但回 null 会让控制端
     * 求值时直接抛空指针，表现为设备整个从列表里消失。
     */
    @Test
    public void sourceIsEmptyButNotNull() {
        assertTrue(DLNAProtocolDeclaration.source().isEmpty());
    }

    /** 描述串要与 ProtocolInfo 的文本格式一致，便于用日志对照。 */
    @Test
    public void describeMatchesProtocolInfoFormat() {
        String expected = DLNAProtocolDeclaration.describe("http-get", "*", "video/mp4", "*");
        assertEquals("http-get:*:video/mp4:*", expected);
        assertEquals(expected, new ProtocolInfo(org.jupnp.support.model.Protocol.HTTP_GET, "*", "video/mp4", "*").toString());
    }
}
