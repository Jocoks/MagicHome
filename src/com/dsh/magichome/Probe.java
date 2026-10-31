package com.dsh.magichome;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** 最近任务卡片结构探针：只在 DEBUG 期间用来摸清荣耀桌面 OverviewPanel 的视图/数据布局。 */
public final class Probe {

    private static final String TAG = "MagicHome";
    private static final int MAX_LOG = 200;

    private Probe() {
    }

    public static void dumpOverview(Activity act) {
        try {
            log("=== PROBE overview dump begin act=" + act.getClass().getName() + " ===");
            dumpTree(act.getWindow().getDecorView(), 0, 6);
            dumpLauncherClasses(act.getClassLoader());
            View lv = findById(act, "overview_panel_launcher3");
            log("ListView(overview_panel_launcher3) = " + str(lv)
                    + (lv == null ? "" : " class=" + lv.getClass().getName()));
            if (lv instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) lv;
                log("ListView childCount=" + g.getChildCount());
                dumpFields("listview", lv, 0);
                try {
                    Method gm = lv.getClass().getMethod("getAdapter");
                    Object ad = gm.invoke(lv);
                    log("adapter=" + (ad == null ? "null" : ad.getClass().getName()));
                    if (ad != null) {
                        dumpFields("adapter", ad, 0);
                        try {
                            Method ctm = ad.getClass().getMethod("getCount");
                            int n = ((Integer) ctm.invoke(ad)).intValue();
                            log("adapter.count=" + n);
                            if (n > 0) {
                                Method gim = ad.getClass().getMethod("getItem", int.class);
                                Object item = gim.invoke(ad, 0);
                                log("adapter.item[0]=" + str(item)
                                        + " class=" + (item == null ? "null" : item.getClass().getName()));
                                if (item != null) {
                                    dumpFields("item0", item, 0);
                                }
                            }
                        } catch (Throwable t) {
                            log("adapter getItem failed: " + t);
                        }
                    }
                } catch (Throwable t) {
                    log("getAdapter failed: " + t);
                }
                int c = g.getChildCount();
                for (int i = 0; i < c && i < 3; i++) {
                    View ch = g.getChildAt(i);
                    log("--- card[" + i + "] class=" + ch.getClass().getName()
                            + " desc=" + str(ch.getContentDescription()));
                    dumpFields("card" + i, ch, 0);
                    dumpTree(ch, 1, 4);
                }
            }
            log("=== PROBE overview dump end ===");
        } catch (Throwable t) {
            log("PROBE dumpOverview failed: " + t);
            t.printStackTrace();
        }
    }

    private static void dumpLauncherClasses(ClassLoader cl) {
        String[] names = {
                "com.hihonor.android.launcher.OverviewPanel",
                "com.hihonor.android.launcher.OverviewPanel$IconButton",
                "com.hihonor.android.launcher.OverviewContainer",
        };
        for (String n : names) {
            try {
                Class<?> c = Class.forName(n, false, cl);
                log("CLASS " + n + " super=" + c.getSuperclass()
                        + " isView=" + View.class.isAssignableFrom(c));
                for (Constructor<?> k : c.getDeclaredConstructors()) {
                    log("  CTOR " + sig(k.getParameterTypes()));
                }
                for (Method m : c.getDeclaredMethods()) {
                    if (isSynthetic(m.getModifiers()) || m.isBridge()) {
                        continue;
                    }
                    log("  M " + m.getName() + sig(m.getParameterTypes()) + " -> " + m.getReturnType().getSimpleName());
                }
                for (Field f : c.getDeclaredFields()) {
                    if (f.isSynthetic()) {
                        continue;
                    }
                    log("  F " + f.getName() + " : " + f.getType().getSimpleName());
                }
            } catch (Throwable t) {
                log("CLASS " + n + " missing: " + t);
            }
        }
    }

    private static boolean isSynthetic(int mods) {
        return (mods & 0x1000) != 0; // ACC_SYNTHETIC
    }

    private static String sig(Class<?>[] ps) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ps[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

    private static void dumpTree(View v, int depth, int maxDepth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        sb.append("V ").append(v.getClass().getSimpleName());
        sb.append('#').append(idName(v));
        int vis = v.getVisibility();
        if (vis != View.VISIBLE) {
            sb.append(vis == View.INVISIBLE ? " INVIS" : " GONE");
        }
        sb.append(" @").append(v.getLeft()).append(',').append(v.getTop())
                .append(' ').append(v.getWidth()).append('x').append(v.getHeight());
        Object cd = v.getContentDescription();
        if (cd != null) {
            sb.append(" desc=\"").append(cd).append('"');
        }
        log(sb.toString());
        if (depth >= maxDepth || !(v instanceof ViewGroup)) {
            return;
        }
        ViewGroup g = (ViewGroup) v;
        int c = g.getChildCount();
        for (int i = 0; i < c; i++) {
            dumpTree(g.getChildAt(i), depth + 1, maxDepth);
        }
    }

    private static String idName(View v) {
        int id = v.getId();
        if (id == View.NO_ID) {
            return "-";
        }
        try {
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return "0x" + Integer.toHexString(id);
        }
    }

    private static void dumpFields(String label, Object o, int depth) {
        Class<?> c = o.getClass();
        int lvl = 0;
        while (c != null && c != Object.class && lvl < 4) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isSynthetic() || Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object val = f.get(o);
                    log("  F[" + label + "] " + c.getSimpleName() + "." + f.getName()
                            + " : " + f.getType().getSimpleName() + " = " + str(val));
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
            lvl++;
        }
    }

    static View findById(Activity act, String entry) {
        try {
            int id = act.getResources().getIdentifier(entry, "id", act.getPackageName());
            if (id == 0) {
                return null;
            }
            return act.findViewById(id);
        } catch (Throwable t) {
            return null;
        }
    }

    public static int idOf(Activity act, String entry) {
        try {
            return act.getResources().getIdentifier(entry, "id", act.getPackageName());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String str(Object o) {
        if (o == null) {
            return "null";
        }
        String s;
        try {
            s = String.valueOf(o);
        } catch (Throwable t) {
            return "<toString failed>";
        }
        return s.length() > MAX_LOG ? s.substring(0, MAX_LOG) + "…" : s;
    }

    private static void log(String s) {
        Log.i(TAG, "PROBE " + s);
    }
}
