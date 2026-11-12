package com.fongmi.android.tv.netdisk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.thegrizzlylabs.sardineandroid.impl.SardineException;

import org.junit.Test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * 重点盯两件事：状态码要分到正确的分区（用户看到的文案全靠它）、认证失败不许被判成可重试。
 *
 * <p>「点了没反应」是这个功能最容易挨骂的地方，而分区错的代价很直接 ——
 * 密码错了却提示「网络不通」，用户会一直去换网络。
 */
public class WebDavBackupErrorTest {

    // ------------------------------------------------------------------ 状态码分区

    @Test
    public void mapsCommonStatusCodes() {
        assertEquals(WebDavBackupError.AUTH, WebDavBackupError.ofStatus(401));
        assertEquals(WebDavBackupError.FORBIDDEN, WebDavBackupError.ofStatus(403));
        assertEquals(WebDavBackupError.NOT_FOUND, WebDavBackupError.ofStatus(404));
        assertEquals(WebDavBackupError.CONFLICT, WebDavBackupError.ofStatus(409));
        assertEquals(WebDavBackupError.QUOTA, WebDavBackupError.ofStatus(507));
    }

    @Test
    public void mapsUnsupportedMethods() {
        // 405/501 通常是服务端没实现 MKCOL 或 PUT，文案要能提示换服务端，而不是「重试」。
        assertEquals(WebDavBackupError.UNSUPPORTED, WebDavBackupError.ofStatus(405));
        assertEquals(WebDavBackupError.UNSUPPORTED, WebDavBackupError.ofStatus(501));
    }

    @Test
    public void mapsServerErrorsAndUnknownCodes() {
        assertEquals(WebDavBackupError.SERVER, WebDavBackupError.ofStatus(500));
        assertEquals(WebDavBackupError.SERVER, WebDavBackupError.ofStatus(502));
        assertEquals(WebDavBackupError.SERVER, WebDavBackupError.ofStatus(503));
        assertEquals(WebDavBackupError.UNKNOWN, WebDavBackupError.ofStatus(400));
        assertEquals(WebDavBackupError.UNKNOWN, WebDavBackupError.ofStatus(0));
    }

    // ------------------------------------------------------------------ 异常分类

    @Test
    public void readsStatusCodeFromSardineException() {
        SardineException error = new SardineException("上传备份失败", 401, "Unauthorized");
        assertEquals(WebDavBackupError.AUTH, WebDavBackupError.of(error));
    }

    @Test
    public void classifiesNetworkFailures() {
        assertEquals(WebDavBackupError.NETWORK, WebDavBackupError.of(new UnknownHostException("dav.example.com")));
        assertEquals(WebDavBackupError.NETWORK, WebDavBackupError.of(new ConnectException("failed to connect")));
        assertEquals(WebDavBackupError.NETWORK, WebDavBackupError.of(new SocketTimeoutException("timeout")));
    }

    @Test
    public void classifiesLocalFileFailures() {
        assertEquals(WebDavBackupError.NOT_FOUND, WebDavBackupError.of(new FileNotFoundException("bak.zip")));
        assertEquals(WebDavBackupError.FORBIDDEN, WebDavBackupError.of(new SecurityException("denied")));
    }

    @Test
    public void looksIntoTheCauseChain() {
        // OkHttp 常把真实原因包一层，只看最外层会一律落到 UNKNOWN。
        Throwable wrapped = new IOException("request failed", new UnknownHostException("dav.example.com"));
        assertEquals(WebDavBackupError.NETWORK, WebDavBackupError.of(wrapped));
    }

    @Test
    public void fallsBackToMessageKeywords() {
        assertEquals(WebDavBackupError.AUTH, WebDavBackupError.of(new IOException("401 Unauthorized")));
        assertEquals(WebDavBackupError.NOT_FOUND, WebDavBackupError.of(new IOException("404 Not Found")));
        assertEquals(WebDavBackupError.QUOTA, WebDavBackupError.of(new IOException("507 Insufficient Storage")));
        assertEquals(WebDavBackupError.NETWORK, WebDavBackupError.of(new IOException("unable to resolve host dav")));
    }

    @Test
    public void handlesNullAndOpaqueFailures() {
        assertEquals(WebDavBackupError.UNKNOWN, WebDavBackupError.of(null));
        assertEquals(WebDavBackupError.UNKNOWN, WebDavBackupError.of(new IllegalStateException()));
    }

    @Test
    public void walksDeepCauseChains() {
        // 真实链路常是 IOException → IOException → SocketTimeoutException，只看一层会漏。
        Throwable deep = new IOException("upload failed",
                new IOException("request failed",
                        new SocketTimeoutException("timeout")));
        assertEquals(WebDavBackupError.NETWORK, WebDavBackupError.of(deep));
    }

    @Test
    public void unknownCauseStillFallsBackToOuterMessage() {
        // 内层认不出来时，不能因为「有 cause」就丢掉外层的状态码线索。
        Throwable error = new IOException("507 Insufficient Storage", new IllegalStateException("nested"));
        assertEquals(WebDavBackupError.QUOTA, WebDavBackupError.of(error));
    }

    // ------------------------------------------------------------------ 可重试

    @Test
    public void onlyNetworkAndServerFailuresAreRetryable() {
        assertTrue(WebDavBackupError.NETWORK.retryable());
        assertTrue(WebDavBackupError.SERVER.retryable());
        // 认证失败重试会被部分服务端锁号，必须不可重试。
        assertFalse(WebDavBackupError.AUTH.retryable());
        assertFalse(WebDavBackupError.FORBIDDEN.retryable());
        assertFalse(WebDavBackupError.NOT_FOUND.retryable());
        assertFalse(WebDavBackupError.QUOTA.retryable());
        assertFalse(WebDavBackupError.UNSUPPORTED.retryable());
        assertFalse(WebDavBackupError.CONFLICT.retryable());
        assertFalse(WebDavBackupError.UNKNOWN.retryable());
    }
}
