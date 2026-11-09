package com.fongmi.android.tv.webdav;

import com.github.catvod.utils.Path;

import java.io.File;

/**
 * WebDAV 服务端对外暴露的目录与文件解析。
 *
 * <p><b>为什么只暴露一个专用子目录，而不是整个存储。</b>本服务把文件开放给同一网络里的
 * 任何设备。若直接把 {@code /sdcard} 当根，等于把其它应用在共享存储上留下的数据
 * （下载内容、导出的配置、聊天缓存等）一并开放出去——用户点一次开关并不会预期到这一点。
 * 所以这里固定用 {@code /sdcard/WebDAV} 作为共享根，语义上就是「共享这一个文件夹」，
 * 与 SMB 的共享名是同一个心智模型，也让「能访问什么」有明确边界。
 *
 * <p>本类只做路径到 {@link File} 的映射，且<b>所有</b>映射都先经 {@link WebDavPath}
 * 校验；路径不再二次拼接外部输入，避免绕过校验。
 */
public final class WebDavRoot {

    /** 共享根目录名。 */
    public static final String NAME = "WebDAV";

    private WebDavRoot() {
    }

    /** 共享根目录；不存在时创建。 */
    public static File dir() {
        File base = new File(Path.root(), NAME);
        if (!base.isDirectory()) base.mkdirs();
        return base;
    }

    /**
     * 把已校验的相对路径映射成实际文件。
     *
     * <p><b>再验一次最终路径。</b>即使 {@link WebDavPath#normalize} 已经拦掉了逃逸段，
     * 这里仍用 {@code getCanonicalPath} 确认结果落在共享根之内。文件系统层面的软链接
     * 或路径别名仍可能把 {@code a/b} 指到根之外，这是纵深防御：一道校验出错时，
     * 第二道仍然能挡住。
     *
     * @param normalized {@link WebDavPath#normalize} 的输出
     * @return 对应文件；路径越界或非法时返回 {@code null}
     */
    public static File resolve(String normalized) {
        if (normalized == null) return null;
        File root = dir();
        File target = normalized.isEmpty() ? root : new File(root, normalized);
        try {
            String rootPath = root.getCanonicalPath();
            String targetPath = target.getCanonicalPath();
            if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) return null;
            return target;
        } catch (Exception e) {
            return null;
        }
    }

    /** 该文件是否就是共享根本身（根不允许被删除或重命名）。 */
    public static boolean isRoot(File file) {
        if (file == null) return false;
        try {
            return file.getCanonicalPath().equals(dir().getCanonicalPath());
        } catch (Exception e) {
            return false;
        }
    }

    /** 是否存在可用的共享根（无存储权限时创建会失败）。 */
    public static boolean isReady() {
        return dir().isDirectory();
    }
}
