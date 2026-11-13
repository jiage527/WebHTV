package com.fongmi.android.tv.webdav;

/**
 * 「共享文件（WebDAV）」的三个状态，以及下标与状态的互转。
 *
 * <p><b>为什么单独抽一个枚举。</b>对话框里它是 {@code RadioGroup} 的第几项，
 * 落盘时又要还原成「开不开 + 能不能写」两个布尔值。三处各写一遍下标就一定会
 * 有一天对不上（本次故障正是下标与状态之间的关系只存在于代码里、无人能测）。
 * 抽出来之后这段映射是纯逻辑，可以脱离 Android 直接单测。
 *
 * <p><b>顺序即下标。</b>{@link #OFF} 在最前面：状态好对，且用户打开对话框第一眼
 * 看到的就是「关」。
 */
public enum WebDavShareOption {

    /** 不开共享。 */
    OFF,
    /** 开着，但对方只能看。 */
    READ_ONLY,
    /** 开着，且对方能上传、建目录、删除。 */
    WRITABLE;

    /** 选中项是否意味着「服务开着」。 */
    public boolean allowed() {
        return this != OFF;
    }

    /** 选中项是否意味着「允许对方写」。 */
    public boolean writable() {
        return this == WRITABLE;
    }

    /** 落盘状态对应的选中项。目前状态原样显示，不做任何猜测。 */
    public static WebDavShareOption preselect(boolean enabled, boolean writable) {
        if (!enabled) return OFF;
        return writable ? WRITABLE : READ_ONLY;
    }

    /** 下标转状态；越界一律当 {@link #OFF}（宁可保守，也不要越界读到别的项）。 */
    public static WebDavShareOption of(int index) {
        WebDavShareOption[] values = values();
        return index < 0 || index >= values.length ? OFF : values[index];
    }

    /** 状态转下标。 */
    public int index() {
        return ordinal();
    }
}
