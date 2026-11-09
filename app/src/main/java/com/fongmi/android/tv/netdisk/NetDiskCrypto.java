package com.fongmi.android.tv.netdisk;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Path;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 密码的加密保存。方案与 {@code GitCloudTokenStore} 一致：优先用 AndroidKeyStore，
 * 在缺少该提供者的 OEM 上回退到应用私有目录里的随机密钥。
 *
 * <p>单独成类而不是复用 {@code GitCloudTokenStore}，是因为后者的密钥别名与首选项
 * 文件是为 Git 账号服务的；混用会让「清掉网盘账号」意外清掉 Git 登录态。
 */
final class NetDiskCrypto {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "webhtv_net_disk";
    private static final String LOCAL_KEY_FILE = "net_disk.key";
    private static final String LOCAL_PREFIX = "local-v1:";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private NetDiskCrypto() {
    }

    static String encrypt(String text) throws Exception {
        SecretKey secret;
        String prefix = "";
        try {
            secret = keyStoreKey();
        } catch (KeyStoreException error) {
            // 提供者存在但密钥不可用，与提供者本身缺失是两回事：后者才允许回退。
            if (java.security.Security.getProvider(ANDROID_KEYSTORE) != null) throw error;
            secret = localKey(true);
            prefix = LOCAL_PREFIX;
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secret);
        byte[] iv = cipher.getIV();
        byte[] body = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + body.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(body, 0, out, iv.length, body.length);
        return prefix + Base64.encodeToString(out, Base64.NO_WRAP);
    }

    static String decrypt(String value) throws Exception {
        if (value == null || value.isEmpty()) return "";
        boolean local = value.startsWith(LOCAL_PREFIX);
        byte[] input = Base64.decode(local ? value.substring(LOCAL_PREFIX.length()) : value, Base64.DEFAULT);
        if (input.length <= IV_BYTES) return "";
        byte[] iv = new byte[IV_BYTES];
        byte[] body = new byte[input.length - IV_BYTES];
        System.arraycopy(input, 0, iv, 0, IV_BYTES);
        System.arraycopy(input, IV_BYTES, body, 0, body.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, local ? localKey(false) : keyStoreKey(), new GCMParameterSpec(TAG_BITS, iv));
        return new String(cipher.doFinal(body), StandardCharsets.UTF_8);
    }

    private static SecretKey keyStoreKey() throws Exception {
        KeyStore store = KeyStore.getInstance(ANDROID_KEYSTORE);
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            java.security.Key key = store.getKey(KEY_ALIAS, null);
            if (key instanceof SecretKey secret) return secret;
            throw new KeyStoreException("网盘密码密钥类型异常");
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static SecretKey localKey(boolean create) throws Exception {
        File path = new File(App.get().getNoBackupFilesDir(), LOCAL_KEY_FILE);
        if (!path.exists()) {
            if (!create) throw new KeyStoreException("网盘密码密钥不可用，请重新填写密码");
            byte[] bytes = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(bytes);
            try (FileOutputStream output = new FileOutputStream(path)) {
                output.write(bytes);
                output.getFD().sync();
            }
            return new SecretKeySpec(bytes, "AES");
        }
        byte[] bytes = Path.readToByte(path);
        if (bytes.length != KEY_BYTES) throw new KeyStoreException("网盘密码密钥损坏，请重新填写密码");
        return new SecretKeySpec(bytes, "AES");
    }

    static Context context() {
        return App.get();
    }
}
