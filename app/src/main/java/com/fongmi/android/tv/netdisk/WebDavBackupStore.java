package com.fongmi.android.tv.netdisk;

import com.fongmi.android.tv.utils.AppBackup;

import java.util.ArrayList;
import java.util.List;

/**
 * WebDAV 云备份的纯逻辑：远端目录、远端文件名、排序与保留份数。
 *
 * <p><b>为什么抽出来。</b>「路径拼错导致备份传到了另一个目录」「保留份数算错把最新的一份删了」
 * 这类问题在真机上很难复现，只有把判断从对话框里抽出来才盯得住。
 *
 * <p><b>命名沿用 {@code AppBackup}。</b>云端列表要能同时认出本通道上传的包与
 * {@code GitCloud} 通道上传的包（同一个目录里可能两者都有），所以识别规则一律用
 * {@link AppBackup#isBackupZipName}，连 {@code webhtv-backup-} 旧前缀也算 —— 不新增版本字段，
 * 也不做数据迁移。
 */
public final class WebDavBackupStore {

    /** 远端默认目录：放在账号根之下，用户自己的其它文件不会被波及。 */
    public static final String DEFAULT_DIR = "WebHTV-Backups";

    /** 云端保留份数。比本地 7 份少，因为云端是跨设备共享的。 */
    public static final int DEFAULT_KEEP = 5;

    private WebDavBackupStore() {
    }

    /**
     * 规整远端目录：统一分隔符、去掉空段与 {@code .}，并弹掉 {@code ..}。
     *
     * <p>{@code ..} 不会逃出账号根：这里的返回值随后会被当作账号内相对路径使用。
     */
    public static String normalizeDir(String dir) {
        if (dir == null) return "";
        String unified = dir.trim().replace('\\', '/');
        List<String> segments = new ArrayList<>();
        for (String raw : unified.split("/")) {
            if (raw.isEmpty() || ".".equals(raw)) continue;
            if ("..".equals(raw)) {
                if (!segments.isEmpty()) segments.remove(segments.size() - 1);
                continue;
            }
            segments.add(raw);
        }
        return NetDiskCodec.join(segments, NetDiskType.WEBDAV.separator());
    }

    /** 用户没填目录时用默认目录，避免把备份散落在账号根。 */
    public static String effectiveDir(String dir) {
        String normalized = normalizeDir(dir);
        return normalized.isEmpty() ? DEFAULT_DIR : normalized;
    }

    /** 备份包在账号内的相对路径。 */
    public static String remotePath(String dir, String name) {
        return NetDiskCodec.join(effectiveDir(dir), name, NetDiskType.WEBDAV.separator());
    }

    /** 云端列表里的名字是否是需要展示的备份包（清单 json 会被过滤掉）。 */
    public static boolean isBackupName(String name) {
        return name != null && AppBackup.isBackupZipName(name);
    }

    /**
     * 从远端目录列表里筛出备份包，按时间从新到旧。
     *
     * <p>排序用 {@link AppBackup#backupSortKey} 的字符串比较而不是文件时间：部分 WebDAV
     * 服务端（尤其套了对象存储的那些）返回的修改时间是上传时刻，同名重传会让新旧顺序颠倒。
     */
    public static List<String> backups(List<String> names) {
        List<String> result = new ArrayList<>();
        if (names == null) return result;
        for (String name : names) {
            if (!isBackupName(name)) continue;
            if (result.contains(name)) continue;
            result.add(name);
        }
        result.sort((left, right) -> AppBackup.backupSortKey(right).compareToIgnoreCase(AppBackup.backupSortKey(left)));
        return result;
    }

    /**
     * 超出保留份数时应当删掉的远端文件名。
     *
     * <p>{@code sortedNewestFirst} 必须是 {@link #backups} 的输出；{@code keep <= 0} 时不做任何删除，
     * 避免配置写错就把云端清空。
     */
    public static List<String> stale(List<String> sortedNewestFirst, int keep) {
        List<String> result = new ArrayList<>();
        if (sortedNewestFirst == null || keep <= 0) return result;
        for (int index = keep; index < sortedNewestFirst.size(); index++) result.add(sortedNewestFirst.get(index));
        return result;
    }

    /** 列表里显示的名字：把 {@code bak-20261112-0830.zip} 还原成好读的时间。 */
    public static String displayName(String name) {
        if (name == null) return "";
        String key = AppBackup.backupSortKey(name);
        if (key.isEmpty()) return name;
        if (key.length() == 13 && key.charAt(8) == '-') {
            return key.substring(0, 4) + "-" + key.substring(4, 6) + "-" + key.substring(6, 8)
                    + " " + key.substring(9, 11) + ":" + key.substring(11, 13);
        }
        return key;
    }

    /**
     * 服务端剩余空间是否放得下这份备份。
     *
     * <p>配额读不到时（{@code freeBytes <= 0}）不拦：很多 WebDAV 服务端不实现 quota，
     * 宁可让上传自己去失败，也不要在这里误报「空间不足」。
     */
    public static boolean fitsQuota(long freeBytes, long fileBytes) {
        if (freeBytes <= 0 || fileBytes <= 0) return true;
        return freeBytes > fileBytes + fileBytes / 10;
    }
}
