package io.github.jocoks.magichome.feature;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;

/**
 * 功能层：目标桌面。
 *
 * 负责「设置里选了哪个桌面 → 包名 / 启动组件 / 可预热的 provider」，以及跳转 Intent
 * 的构造。5 秒缓存，设置页改完立刻生效。
 */
public final class TargetLauncher {

    private static final long TTL_MS = 5000L;

    private static volatile String sPkg = Constants.DEFAULT_TARGET_PKG;
    private static volatile String sAct = Constants.DEFAULT_TARGET_ACT;
    private static volatile String sWarmUri = Constants.DEFAULT_WARM_URI;
    private static volatile long sLoadedAt = 0L;
    private static volatile boolean sRecentsExit = true;

    private TargetLauncher() {
    }

    public static String pkg() {
        return sPkg;
    }

    public static String activity() {
        return sAct;
    }

    /** 可用于静默预热的 provider（空串表示没有） */
    public static String warmUri() {
        return sWarmUri;
    }

    /** 最近任务退出拦截开关 */
    public static boolean recentsExitEnabled() {
        return sRecentsExit;
    }

    /** 需要时重新读设置（5s 缓存） */
    public static void refreshIfStale(Context ctx) {
        long now = android.os.SystemClock.uptimeMillis();
        if (sLoadedAt != 0L && now - sLoadedAt < TTL_MS) {
            return;
        }
        refresh(ctx);
    }

    /**
     * 从设置 provider 读一次目标桌面；解析失败时保持上一次的值（默认 Nova）。
     *
     * @return 是否真的读到了新值
     */
    public static boolean refresh(Context ctx) {
        if (ctx == null) {
            return false;
        }
        sLoadedAt = android.os.SystemClock.uptimeMillis();
        String pkg = null;
        String act = null;
        String warm = null;
        String recentsExit = null;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(SettingsContract.TARGET_URI, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ip = c.getColumnIndex(SettingsContract.COL_PKG);
                int ia = c.getColumnIndex(SettingsContract.COL_ACT);
                int iw = c.getColumnIndex(SettingsContract.COL_WARM);
                int ir = c.getColumnIndex(SettingsContract.COL_RECENTS_EXIT);
                if (ip >= 0) {
                    pkg = c.getString(ip);
                }
                if (ia >= 0) {
                    act = c.getString(ia);
                }
                if (iw >= 0) {
                    warm = c.getString(iw);
                }
                if (ir >= 0) {
                    recentsExit = c.getString(ir);
                }
            }
        } catch (Throwable t) {
            Logs.w("read target settings failed: " + t);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (recentsExit != null) {
            sRecentsExit = !"0".equals(recentsExit.trim());
        }
        if (pkg == null || pkg.trim().isEmpty() || act == null || act.trim().isEmpty()) {
            Logs.i("target launcher not configured, using " + Constants.DEFAULT_TARGET_PKG);
            return false;
        }
        int slash = act.indexOf('/');
        String apkg = slash > 0 ? act.substring(0, slash) : pkg;
        String acls = slash > 0 ? act.substring(slash + 1) : act;
        if (acls.startsWith(".")) {
            acls = apkg + acls;
        }
        sPkg = apkg.trim();
        sAct = acls.trim();
        sWarmUri = (warm == null || warm.trim().isEmpty()) ? "" : "content://" + warm.trim();
        Logs.i("target launcher = " + sPkg + "/" + sAct
                + (sWarmUri.isEmpty() ? "" : " warm=" + sWarmUri));
        return true;
    }

    /**
     * 跳转 Intent。
     *
     * 关键：不要带 CATEGORY_HOME，否则系统会把第三方桌面塞进 home 根任务，
     * 变成第二个桌面任务叠在荣耀桌面之上，最近任务会被吃掉触摸。
     */
    public static Intent launchIntent() {
        String cls = sAct;
        if (cls == null || cls.isEmpty()) {
            return null;
        }
        Intent target = new Intent(Intent.ACTION_MAIN);
        target.setComponent(new ComponentName(sPkg, cls));
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        return target;
    }

    /** 预热用的 URI（可能为空） */
    public static Uri warmParsedUri() {
        String uri = sWarmUri;
        return uri == null || uri.isEmpty() ? null : Uri.parse(uri);
    }
}
