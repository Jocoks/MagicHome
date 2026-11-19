package io.github.jocoks.magichome.feature;

import android.net.Uri;

/**
 * 功能层：设置契约。
 *
 * 模块本体（设置页）与荣耀桌面进程（钩子侧）是两个 uid，靠一个 exported 的
 * ContentProvider 交换「目标桌面 / 开关」。这里只放键名和 URI，供两边共用。
 */
public final class SettingsContract {

    public static final String AUTHORITY = "io.github.jocoks.magichome.settings";
    public static final Uri TARGET_URI = Uri.parse("content://" + AUTHORITY + "/target");

    /** 模块本体的 SharedPreferences */
    public static final String PREFS = "magichome";
    /** 目标桌面包名（设置页写入） */
    public static final String KEY_TARGET = "target_pkg";
    /** 最近任务退出拦截开关（默认开） */
    public static final String KEY_RECENTS_EXIT = "recents_exit";

    public static final String COL_PKG = "target_pkg";
    public static final String COL_ACT = "target_act";
    public static final String COL_WARM = "warm_authority";
    public static final String COL_RECENTS_EXIT = "recents_exit";

    private SettingsContract() {
    }
}
