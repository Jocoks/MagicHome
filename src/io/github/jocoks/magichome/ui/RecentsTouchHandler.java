package io.github.jocoks.magichome.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.jocoks.magichome.feature.GestureRules;
import io.github.jocoks.magichome.feature.Logs;

/**
 * 交互层：最近任务卡片的触摸处理。
 *
 * <p>两件事：
 *  <ul>
 *    <li>长按卡片 → 打开该应用的「应用信息」页（荣耀最近任务卡片没有这个入口）；</li>
 *    <li>记录「刚刚点了卡片」的时刻，供功能层判断这次回桌面是不是在打开应用。</li>
 *  </ul>
 *
 * 卡片的触摸发生在 com.android.quickstep.views.TaskView.dispatchTouchEvent，
 * 不加任何视图，原地按住超过阈值且没滑动才触发；触发后把后续 MOVE/UP 吃掉，
 * 避免又当成点击去打开应用。卡片顶栏的分屏/锁定按钮区域不参与。
 */
public final class RecentsTouchHandler {

    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static Runnable sPending;
    private static View sDownView;
    private static Object sDownTask;
    private static float sDownX;
    private static float sDownY;
    private static boolean sFired;
    private static int sSlop = -1;

    private static long sDownAt = 0L;
    private static boolean sMoved = false;
    /** 最近一次「真·点按任务卡片」的时刻（uptimeMillis） */
    private static volatile long sLastCardTapAt = 0L;

    private RecentsTouchHandler() {
    }

    /**
     * 在 TaskView.dispatchTouchEvent 里调用。
     *
     * @return true 表示这个事件已被消费（长按已触发后的 MOVE/UP）
     */
    public static boolean handleTouch(final View taskView, MotionEvent e, final Object task) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                return onDown(taskView, e, task);
            case MotionEvent.ACTION_MOVE:
                return onMove(e);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return onUp(e, action);
            default:
                return false;
        }
    }

    /** 距上次「点任务卡片」过了多久（ms）；-1 表示没有记录 */
    public static long cardTapAge(long now) {
        long t = sLastCardTapAt;
        return t > 0L ? now - t : -1L;
    }

    private static boolean onDown(final View taskView, MotionEvent e, final Object task) {
        cancelPending();
        sDownAt = SystemClock.uptimeMillis();
        sMoved = false;
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
                Logs.i("[appinfo] long press (" + (SystemClock.uptimeMillis() - downAt)
                        + "ms) task=" + sDownTask + " pkg=" + pkg);
                if (pkg != null && !pkg.isEmpty()) {
                    openAppInfo(taskView.getContext(), pkg);
                }
            }
        };
        sHandler.postDelayed(sPending, GestureRules.LONG_PRESS_MS);
        return false;
    }

    private static boolean onMove(MotionEvent e) {
        if (sPending != null && !sFired) {
            float dx = e.getX() - sDownX;
            float dy = e.getY() - sDownY;
            if (dx * dx + dy * dy > (float) sSlop * sSlop) {
                sMoved = true; // 有位移 = 在滑卡片，不是点按
                cancelPending();
            }
        }
        return sFired; // 长按已触发：吃掉滑动，别让它继续拖卡片
    }

    private static boolean onUp(MotionEvent e, int action) {
        boolean fired = sFired;
        long upAt = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_UP
                && GestureRules.isTap(sMoved, sDownAt, upAt)) {
            // 真·点按（比如点卡片开应用）才记，滑掉任务卡不记
            sLastCardTapAt = upAt;
        }
        sDownAt = 0L;
        sMoved = false;
        cancelPending();
        sDownView = null;
        sDownTask = null;
        sFired = false;
        return fired; // 长按已触发时吞掉这次 UP，避免又「打开应用」
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
        for (String name : new String[]{"getBaseIntent", "getIntent"}) {
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

    /** 打开系统的「应用信息」页；不行再退到「权限」页。 */
    private static void openAppInfo(Context ctx, String pkg) {
        try {
            Intent it = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            it.setData(Uri.parse("package:" + pkg));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            Logs.i("[appinfo] open app info page for " + pkg);
            return;
        } catch (Throwable t) {
            Logs.w("[appinfo] APPLICATION_DETAILS_SETTINGS failed: " + t);
        }
        try {
            Intent it = new Intent("android.intent.action.MANAGE_APP_PERMISSIONS");
            it.putExtra("android.intent.extra.PACKAGE_NAME", pkg);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            Logs.i("[appinfo] open permissions page for " + pkg);
        } catch (Throwable t) {
            Logs.w("[appinfo] MANAGE_APP_PERMISSIONS failed: " + t);
        }
    }
}
