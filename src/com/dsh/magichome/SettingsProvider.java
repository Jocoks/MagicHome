package com.dsh.magichome;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * 目标桌面配置的只读出口。
 *
 * 模块本体（com.dsh.magichome）里存 SharedPreferences，但真正发跳转的是
 * 荣耀桌面进程（另一个 uid），所以用 exported 的 provider 把「目标桌面包名 /
 * 启动组件 / 可用于静默预热的 provider 名」交给钩子侧读取。
 */
public class SettingsProvider extends ContentProvider {

    public static final String AUTHORITY = "com.dsh.magichome.settings";
    public static final String URI_TARGET = "content://" + AUTHORITY + "/target";

    public static final String COL_PKG = "target_pkg";
    public static final String COL_ACT = "target_act";
    public static final String COL_WARM = "warm_authority";

    public static final String PREFS = "magichome";
    public static final String KEY_TARGET = "target_pkg";

    public static final String DEFAULT_PKG = "com.teslacoilsw.launcher";
    private static final String DEFAULT_WARM_AUTHORITY = "com.teslacoilsw.launcher.api";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        Context ctx = getContext();
        String pkg = "";
        if (ctx != null) {
            String v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_TARGET, "");
            if (v != null) {
                pkg = v.trim();
            }
        }
        String act = "";
        String warm = "";
        if (!pkg.isEmpty() && ctx != null) {
            ComponentName cn = resolveHomeComponent(ctx, pkg);
            if (cn != null) {
                act = cn.flattenToString();
            }
            warm = warmAuthority(ctx, pkg);
        }
        MatrixCursor c = new MatrixCursor(new String[]{COL_PKG, COL_ACT, COL_WARM});
        c.addRow(new Object[]{pkg, act, warm});
        return c;
    }

    /**
     * 目标桌面的启动组件：优先 ACTION_MAIN+CATEGORY_HOME，退而求其次用 LAUNCHER intent。
     * 模块本体声明了 QUERY_ALL_PACKAGES，所以这里能看到任意已安装桌面。
     */
    static ComponentName resolveHomeComponent(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.setPackage(pkg);
            ResolveInfo ri = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) {
                return new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            }
        } catch (Throwable ignored) {
        }
        try {
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch != null && launch.getComponent() != null) {
                return launch.getComponent();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 静默预热用：找一个 exported、且不需要权限的 ContentProvider。
     * acquire 它不会显示界面，但会让系统创建目标桌面进程（跑 Application.onCreate、加载资源）。
     * Nova 的 com.teslacoilsw.launcher.api 是实测可用的；其他桌面用「第一个公开 provider」兜底。
     */
    static String warmAuthority(Context ctx, String pkg) {
        if (DEFAULT_PKG.equals(pkg)) {
            return DEFAULT_WARM_AUTHORITY;
        }
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(pkg, PackageManager.GET_PROVIDERS);
            if (pi.providers != null) {
                for (ProviderInfo p : pi.providers) {
                    if (!p.exported) {
                        continue;
                    }
                    if (p.readPermission != null || p.writePermission != null) {
                        continue;
                    }
                    if (p.authority != null && !p.authority.isEmpty()) {
                        return p.authority;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
