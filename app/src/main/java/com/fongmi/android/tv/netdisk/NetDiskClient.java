package com.fongmi.android.tv.netdisk;

import java.io.Closeable;
import java.io.InputStream;
import java.util.List;

/**
 * 网络存储客户端的统一接口。
 *
 * <p>SMB 与 WebDAV 的库完全不同（smbj 是句柄式、sardine 是 HTTP 式），
 * 但对上层只有三件事：列目录、读一段字节、关掉。把这三件事抽象出来，
 * 浏览器与代理端点就能共用一套代码；也便于在单测里换成假的实现。
 *
 * <p>实现都要是<b>可关且可重入</b>的：一次连接失败后如果对象没被清掉，
 * 下一次列举会复用坏掉的会话，表现为「改完密码还是连不上」。
 */
public interface NetDiskClient extends Closeable {

    /** 列目录。{@code path} 是账号内相对路径，空串表示账号根目录。 */
    List<NetDiskEntry> list(String path) throws Exception;

    /**
     * 读取 {@code [start, end]} 闭区间的一段字节。
     *
     * <p>返回的流由调用方负责关闭。区间必须先被压到文件长度内——不同实现越界时
     * 表现不一（有的抛异常、有的返回空），所以压缩放在调用方统一做。
     */
    InputStream open(String path, long start, long end) throws Exception;

    /** 取文件长度；取不到时返回 -1。 */
    long length(String path) throws Exception;

    /** 连通性自检，用于「测试连接」按钮。 */
    void connect() throws Exception;

    @Override
    void close();
}
