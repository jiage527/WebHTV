package com.fongmi.android.tv.player.intro;

import com.github.catvod.utils.Prefers;

/**
 * 智能片头片尾的开关。
 *
 * <p><b>默认关闭。</b>自动跳过会移动播放位置，属于用户能直接察觉的行为改变。
 * 现有版本是纯手动记录，直接改成默认自动会让老用户觉得「播放器乱跳」。
 * 所以默认保持与旧版本一致的手动行为，由用户主动开启。
 */
public final class IntroSetting {

    private static final String KEY_ENABLED = "intro_auto_skip";
    private static final String KEY_CHAPTER = "intro_use_chapter";
    private static final String KEY_LEARN = "intro_use_learned";

    private IntroSetting() {
    }

    /** 总开关：关掉后只保留手动记录，行为与旧版本一致。 */
    public static boolean isEnabled() {
        return Prefers.getBoolean(KEY_ENABLED, false);
    }

    public static void putEnabled(boolean enabled) {
        Prefers.put(KEY_ENABLED, enabled);
    }

    /** 是否读取容器章节标记。与总开关分开，便于「章节误判多」时单独关掉它。 */
    public static boolean useChapter() {
        return Prefers.getBoolean(KEY_CHAPTER, true);
    }

    public static void putUseChapter(boolean enabled) {
        Prefers.put(KEY_CHAPTER, enabled);
    }

    /** 是否套用同剧其它集学到的偏移。 */
    public static boolean useLearned() {
        return Prefers.getBoolean(KEY_LEARN, true);
    }

    public static void putUseLearned(boolean enabled) {
        Prefers.put(KEY_LEARN, enabled);
    }

    /** 摘要文本，供设置页显示。 */
    public static String summarize() {
        if (!isEnabled()) return "off";
        return "on" + (useChapter() ? "+chapter" : "") + (useLearned() ? "+learn" : "");
    }
}
