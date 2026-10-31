package com.dsh.magichome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 最近任务卡片「长按 → 应用权限设置」。
 *
 * 荣耀最近任务的卡片是 com.hihonor.android.launcher.quickstep.views.HnTaskView
 * （extends com.android.quickstep.views.TaskView），它的 mTask 字段是当前任务的 Task；
 * 卡片的触摸也由 TaskView 处理（点一下 = 打开该应用）。
 *
 * 这里不加任何视图：在 TaskView.dispatchTouchEvent 这一层跟踪手势，原地按住超过
 * LONG_PRESS_MS 且位移没超过 touchSlop，就把该任务的包名送进「应用权限」设置页；
 * 触发后把后续 MOVE/UP 吃掉，避免又当成点击去打开应用。卡片顶栏的分屏/锁定按钮
 * 区域不参与，保持桌面原有行为。
 */
public final class RecentsAppInfo {

    private static final String TAG = "MagicHome";
    /** 长按判定时间；比 ViewConfiguration.getLongPressTimeout(400ms) 稍慢，降低误触 */
    private static final long LONG_PRESS_MS = 460L;

    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static Runnable sPending;
    private static View sDownView;
    private static Object sDownTask;
    private static float sDownX;
    private static float sDownY;
    private static boolean sFired;
    private static int sSlop = -1;

    private RecentsAppInfo() {
    }

    /**
     * 在 TaskView.dispatchTouchEvent 里调用。
     *
     * @return true 表示这个事件已被消费（长按已触发后的 MOVE/UP）
     */
    public static boolean handleTouch(final View taskView, MotionEvent e, final Object task) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                cancelPending();
                if (isOnHeaderButton(taskView, e.getX(), e.getY())) {
                    return false; // 分屏/锁定按钮照旧
                }
                if (sSlop < 0) {
                    sSlop = ViewConfiguration.get(taskView.getContext()).getScaledTouchSlop();
                    if (sSlop <= 0) {
                        sSlop = 24;
                    }
                }
                sDownView = taskView;
                sDownTask = task;
                sDownX = e.getX();
                sDownY = e.getY();
                sFired = false;
                final long downAt = SystemClock.uptimeMillis();
                sPending = new Runnable() {
                    @Override
                    public void run() {
                        if (sFired || sDownView != taskView) {
                            return;
                        }
                        sFired = true;
                        try {
                            taskView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS,
                                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                        } catch (Throwable ignored) {
                        }
                        String pkg = packageOf(sDownTask);
                        log("long press (" + (SystemClock.uptimeMillis() - downAt) + "ms) task="
                                + sDownTask + " pkg=" + pkg);
                        if (pkg != null && !pkg.isEmpty()) {
                            openPermissions(taskView.getContext(), pkg);
                        }
                    }
                };
                sHandler.postDelayed(sPending, LONG_PRESS_MS);
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (sPending != null && !sFired) {
                    float dx = e.getX() - sDownX;
                    float dy = e.getY() - sDownY;
                    if (dx * dx + dy * dy > (float) sSlop * sSlop) {
                        cancelPending();
                    }
                }
                return sFired; // 长按已触发：吃掉滑动，别让它继续拖卡片
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean fired = sFired;
                cancelPending();
                sDownView = null;
                sDownTask = null;
                sFired = false;
                return fired; // 长按已触发时吞掉这次 UP，避免又「打开应用」
            }
            default:
                return false;
        }
    }

    private static void cancelPending() {
        if (sPending != null) {
            sHandler.removeCallbacks(sPending);
            sPending = null;
        }
    }

    /** 长按落在卡片顶栏的分屏/锁定按钮上时不接管。 */
    private static boolean isOnHeaderButton(View taskView, float x, float y) {
        try {
            int id = taskView.getResources().getIdentifier(
                    "button_group", "id", taskView.getContext().getPackageName());
            if (id == 0) {
                return false;
            }
            View group = taskView.findViewById(id);
            if (group == null || group.getVisibility() != View.VISIBLE) {
                return false;
            }
            int[] gl = new int[2];
            int[] vl = new int[2];
            group.getLocationInWindow(gl);
            taskView.getLocationInWindow(vl);
            float left = gl[0] - vl[0];
            float top = gl[1] - vl[1];
            return x >= left && x <= left + group.getWidth()
                    && y >= top && y <= top + group.getHeight();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 从 Task 里取顶层 ComponentName 的包名，兼容几种命名。 */
    static String packageOf(Object task) {
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

    private static Object fieldValue(Object o, String name) {
        Class<?> c = o == null ? null : o.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** 优先直达「权限」页；不行再退到「应用信息」页。 */
    static void openPermissions(Context ctx, String pkg) {
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

    private static void log(String s) {
        Log.i(TAG, "[appinfo] " + s);
    }
}
