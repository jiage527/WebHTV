package com.fongmi.android.tv.netdisk;

/**
 * 目录列举出来的一项。纯数据，不依赖 Android。
 *
 * <p>{@link #path} 一律是「账号内相对路径」且已用 {@link NetDiskType#separator()} 规范化，
 * 这样 SMB 与 WebDAV 共用同一套列表与浏览器逻辑，不必在界面层再区分协议。
 */
public final class NetDiskEntry {

    private final String name;
    private final String path;
    private final boolean directory;
    private final long size;
    private final long modified;

    public NetDiskEntry(String name, String path, boolean directory, long size, long modified) {
        this.name = name == null ? "" : name;
        this.path = path == null ? "" : path;
        this.directory = directory;
        this.size = size;
        this.modified = modified;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public boolean isDirectory() {
        return directory;
    }

    public long getSize() {
        return size;
    }

    public long getModified() {
        return modified;
    }

    /** 是否应当直接送去播放（视频/音频/字幕）。 */
    public boolean isPlayable() {
        return !directory && NetDiskCodec.isMediaName(name);
    }

    /**
     * 排序键：目录优先，然后按名称不区分大小写。
     *
     * <p>与本地文件浏览 {@code Path.sort} 的观感保持一致，避免同一用户在两处看到不同顺序。
     */
    public int compareTo(NetDiskEntry other) {
        if (other == null) return -1;
        if (directory != other.directory) return directory ? -1 : 1;
        return name.toLowerCase(java.util.Locale.ROOT).compareTo(other.name.toLowerCase(java.util.Locale.ROOT));
    }
}
