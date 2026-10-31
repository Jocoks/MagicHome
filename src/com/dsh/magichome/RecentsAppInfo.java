package com.dsh.magichome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.RelativeLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 最近任务卡片上的「应用信息」按钮。
 *
 * 每张卡片的顶栏是 com.hihonor.android.launcher.quickstep.views.TaskViewHeader
 * （view id: task_view_bar），里面已经有一个 button_group（分屏/锁定按钮）。
 * 卡片本身是 com.hihonor.android.launcher.quickstep.views.HnTaskView（extends
 * com.android.quickstep.views.TaskView），它的 mTask 字段是当前任务的 Task。
 *
 * 我们把按钮挂到 task_view_bar 上，位置取「button_group 右边缘到卡片右边缘」这段
 * 空白区域的正中（机主要求的位置）；点击时从按钮往上找到 TaskView，读它的
 * mTask，取顶层 ComponentName 的包名，直接打开该应用的权限设置页。
 */
public final class RecentsAppInfo {

    private static final String TAG = "MagicHome";
    private static final String BTN_TAG = "magichome_app_info_btn";
    private static final String DESC = "应用信息";

    private RecentsAppInfo() {
    }

    /** 在卡片顶栏上挂按钮（幂等）。 */
    public static void attach(final ViewGroup header) {
        if (header == null) {
            return;
        }
        try {
            if (header.findViewWithTag(BTN_TAG) != null) {
                return;
            }
            final Context ctx = header.getContext();
            final ImageButton btn = new ImageButton(ctx);
            btn.setTag(BTN_TAG);
            btn.setImageResource(android.R.drawable.ic_menu_info_details);
            btn.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
            btn.setContentDescription(DESC);
            btn.setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6));

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(0x1A000000);
            btn.setBackground(bg);

            int size = dp(ctx, 34);
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(size, size);
            lp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
            lp.addRule(RelativeLayout.CENTER_VERTICAL);
            header.addView(btn, lp);

            final int groupId = header.getResources()
                    .getIdentifier("button_group", "id", ctx.getPackageName());
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    handleClick(v);
                }
            });
            header.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b,
                                           int ol, int ot, int or, int ob) {
                    if (r - l != or - ol) {
                        position(header, btn, groupId);
                    }
                }
            });
            log("app info button attached to " + header.getClass().getSimpleName());
        } catch (Throwable t) {
            log("attach failed: " + t);
        }
    }

    /** 让按钮居中于 button_group 右侧的空白区域（即机主图上绿圈的位置）。 */
    private static void position(ViewGroup header, View btn, int groupId) {
        try {
            int w = header.getWidth();
            if (w <= 0) {
                return;
            }
            int bw = btn.getWidth() > 0 ? btn.getWidth() : dp(header.getContext(), 34);
            int groupRight = -1;
            if (groupId != 0) {
                View group = header.findViewById(groupId);
                if (group != null && group.getVisibility() != View.GONE && group.getWidth() > 0) {
                    groupRight = group.getRight() + (int) group.getTranslationX();
                }
            }
            if (groupRight <= 0 || groupRight > w) {
                groupRight = (int) (w * 0.62f);
            }
            int margin = (w - groupRight - bw) / 2;
            int min = dp(header.getContext(), 10);
            if (margin < min) {
                margin = min;
            }
            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) btn.getLayoutParams();
            if (lp != null && lp.rightMargin != margin) {
                lp.rightMargin = margin;
                btn.setLayoutParams(lp);
            }
        } catch (Throwable t) {
            log("position failed: " + t);
        }
    }

    /**
     * TaskView 自己会拦截/转接头部的触摸（点击卡头 = 打开应用），所以按钮的触摸在
     * TaskView.dispatchTouchEvent 这一层拦下来：落在按钮矩形内就由我们吃掉。
     *
     * @return true 表示事件已被消费
     */
    public static boolean handleTouch(View taskView, MotionEvent e, Object task) {
        try {
            View btn = taskView.findViewWithTag(BTN_TAG);
            if (btn == null || btn.getVisibility() != View.VISIBLE) {
                return false;
            }
            int[] bl = new int[2];
            int[] tl = new int[2];
            btn.getLocationInWindow(bl);
            taskView.getLocationInWindow(tl);
            float left = bl[0] - tl[0];
            float top = bl[1] - tl[1];
            float x = e.getX();
            float y = e.getY();
            boolean inside = x >= left && x <= left + btn.getWidth()
                    && y >= top && y <= top + btn.getHeight();
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                sPressed = inside;
                return inside;
            }
            if (action == MotionEvent.ACTION_UP) {
                boolean was = sPressed;
                sPressed = false;
                if (was && inside) {
                    String pkg = packageOf(task);
                    log("app info tap: task=" + task + " pkg=" + pkg);
                    if (pkg != null && !pkg.isEmpty()) {
                        openPermissions(taskView.getContext(), pkg);
                    }
                    return true;
                }
                return was;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                boolean was = sPressed;
                sPressed = false;
                return was;
            }
            return sPressed;
        } catch (Throwable t) {
            sPressed = false;
            log("touch failed: " + t);
            return false;
        }
    }

    private static boolean sPressed;

    private static void handleClick(View btn) {
        try {
            Object taskView = findTaskView(btn);
            Object task = fieldValue(taskView, "mTask");
            String pkg = packageOf(task);
            log("app info click: task=" + task + " pkg=" + pkg);
            if (pkg == null || pkg.isEmpty()) {
                return;
            }
            openPermissions(btn.getContext(), pkg);
        } catch (Throwable t) {
            log("app info click failed: " + t);
        }
    }

    private static Object findTaskView(View v) {
        View p = v;
        for (int i = 0; i < 8 && p != null; i++) {
            if (p.getClass().getName().indexOf("TaskView") >= 0) {
                return p;
            }
            Object parent = p.getParent();
            p = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static Object fieldValue(Object o, String name) {
        Class<?> c = o == null ? null : o.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** 从 Task 里取顶层 ComponentName 的包名，兼容几种命名。 */
    private static String packageOf(Object task) {
        if (task == null) {
            return null;
        }
        String[] methods = {"getTopComponent", "getComponent", "getBaseComponent"};
        for (String name : methods) {
            ComponentName cn = invokeComponent(task, name);
            if (cn != null) {
                return cn.getPackageName();
            }
        }
        String[] fields = {"component", "mComponent", "baseComponent", "topComponent"};
        for (String name : fields) {
            Object v = fieldValue(task, name);
            if (v instanceof ComponentName) {
                return ((ComponentName) v).getPackageName();
            }
        }
        String[] intents = {"getBaseIntent", "getIntent"};
        for (String name : intents) {
            try {
                Method m = task.getClass().getMethod(name);
                Object v = m.invoke(task);
                if (v instanceof Intent) {
                    ComponentName cn = ((Intent) v).getComponent();
                    if (cn != null) {
                        return cn.getPackageName();
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        for (String name : new String[]{"baseIntent", "intent", "mIntent"}) {
            Object v = fieldValue(task, name);
            if (v instanceof Intent) {
                ComponentName cn = ((Intent) v).getComponent();
                if (cn != null) {
                    return cn.getPackageName();
                }
            }
        }
        return null;
    }

    private static ComponentName invokeComponent(Object task, String name) {
        try {
            Method m = task.getClass().getMethod(name);
            Object v = m.invoke(task);
            return v instanceof ComponentName ? (ComponentName) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 优先直达「权限」页；不行再退到「应用信息」页。 */
    private static void openPermissions(Context ctx, String pkg) {
        try {
            Intent it = new Intent("android.intent.action.MANAGE_APP_PERMISSIONS");
            it.putExtra("android.intent.extra.PACKAGE_NAME", pkg);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            log("open permissions page for " + pkg);
            return;
        } catch (Throwable t) {
            log("MANAGE_APP_PERMISSIONS failed: " + t);
        }
        try {
            Intent it = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", pkg, null));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            log("open app details page for " + pkg);
        } catch (Throwable t) {
            log("app details failed: " + t);
        }
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void log(String s) {
        Log.i(TAG, "[appinfo] " + s);
    }
}
