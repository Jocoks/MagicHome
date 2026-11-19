package io.github.jocoks.magichome.feature;

/** 全局常量：日志标签、被钩的桌面包名、默认目标桌面。 */
public final class Constants {

    public static final String TAG = "MagicHome";

    /** 荣耀桌面（MagicOS 10/11 两个包名都见过） */
    public static final String[] LAUNCHER_PKGS = {
            "com.hihonor.android.launcher",
            "com.hihonor.hnofficelauncher"
    };

    public static final String DEFAULT_TARGET_PKG = "com.teslacoilsw.launcher";
    public static final String DEFAULT_TARGET_ACT = "com.teslacoilsw.launcher.NovaLauncher";
    public static final String DEFAULT_WARM_URI = "content://com.teslacoilsw.launcher.api";

    private Constants() {
    }
}
