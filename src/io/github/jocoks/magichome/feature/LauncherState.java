package io.github.jocoks.magichome.feature;

/**
 * 功能层：桌面状态名判定。
 *
 * 荣耀桌面 / Launcher3 的状态类名因版本而异（NormalState、HnOverviewState、
 * HnBackgroundAppState…），所以只按关键字判定，不写死类名。
 */
public final class LauncherState {

    private LauncherState() {
    }

    /** 是否处在最近任务（overview） */
    public static boolean isOverview(String name) {
        return name != null && (name.contains("Overview") || name.contains("Recents"));
    }

    /** 是否停在桌面工作区（Normal/Home） */
    public static boolean isHome(String name) {
        return name != null && (name.contains("Normal") || name.contains("Home")
                || "NORMAL".equalsIgnoreCase(name));
    }

    /** 是否有别的应用盖在桌面上（Background*State） */
    public static boolean isBackground(String name) {
        return name != null && name.contains("Background");
    }
}
