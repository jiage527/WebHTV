package com.fongmi.android.tv.player.anime4k;

import com.fongmi.android.tv.App;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 把打包在 assets 里的 Anime4K 着色器落到私有目录，并给出绝对路径。
 *
 * <p><b>为什么必须落盘、不能直接用 assets 路径。</b>mpv 的 {@code glsl-shaders} 需要
 * 真实文件系统路径才能打开并编译；{@code file:///android_asset/...} 在 native 侧打不开
 * （assets 是 APK 内的压缩条目，不是普通文件）。Kazumi 同样是把 shader 拷到
 * {@code getApplicationSupportDirectory()/anime_shaders/} 再交给 mpv。
 *
 * <p><b>为什么按长度校验而不是每次重写。</b>每个文件几百 KB，质量档一次要落 6 个
 * （约 380 KB）。只在长度不一致时才重写，正常播放路径上不会反复产生 IO。
 * 用长度而非 mtime：assets 里的文件没有可信的时间戳。
 *
 * <p>命名空间独立于 LUT 生成的那些 shader（{@code cacheDir/mpv_lut_shaders}），
 * 互不覆盖。
 */
public final class Anime4KShaderStore {

    private static final String DIR_NAME = "anime4k_shaders";
    private static final int BUFFER_SIZE = 32 * 1024;

    private static File directory;

    private Anime4KShaderStore() {
    }

    /** 目标目录（惰性创建，失败返回 null）。 */
    public static synchronized File directory() {
        if (directory != null) return directory;
        File dir = new File(App.get().getFilesDir(), DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) return null;
        directory = dir;
        return dir;
    }

    /**
     * 把一档需要的着色器全部落盘。
     *
     * @return 绝对路径列表；任何一步失败都返回空表（调用方据此保持原档位，不半途换链）
     */
    public static List<String> pathsFor(int mode) {
        List<String> names = Anime4KShaderChain.names(mode);
        if (names.isEmpty()) return List.of();
        File dir = directory();
        if (dir == null) return List.of();
        List<String> paths = new ArrayList<>(names.size());
        for (String name : names) {
            File out = new File(dir, name);
            if (!ensure(name, out)) return List.of();
            paths.add(out.getAbsolutePath());
        }
        return paths;
    }

    private static boolean ensure(String assetName, File out) {
        long expected = assetLength(assetName);
        if (expected > 0 && out.exists() && out.length() == expected) return true;
        return copy(Anime4KShaderChain.DIRECTORY + "/" + assetName, out);
    }

    /** assets 里该条目的字节数；取不到返回 -1。 */
    private static long assetLength(String name) {
        try (InputStream in = App.get().getAssets().open(Anime4KShaderChain.DIRECTORY + "/" + name, android.content.res.AssetManager.ACCESS_STREAMING)) {
            int available = in.available();
            return available > 0 ? available : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean copy(String assetPath, File out) {
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
        try (InputStream in = App.get().getAssets().open(assetPath, android.content.res.AssetManager.ACCESS_STREAMING);
             OutputStream writer = new FileOutputStream(out)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) writer.write(buffer, 0, read);
            return true;
        } catch (IOException e) {
            // 落盘失败就把半截文件删掉，免得下次被长度校验当成「已就绪」。
            if (out.exists() && !out.delete()) out.deleteOnExit();
            return false;
        }
    }
}
