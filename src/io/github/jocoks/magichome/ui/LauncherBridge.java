package io.github.jocoks.magichome.ui;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.jocoks.magichome.feature.BouncePolicy;
import io.github.jocoks.magichome.feature.Constants;
import io.github.jocoks.magichome.feature.LauncherState;
import io.github.jocoks.magichome.feature.Logs;
import io.github.jocoks.magichome.feature.TargetLauncher;

/**
 * 交互层：桌面运行时。
 *
 * 负责「现在是不是该跳、什么时候跳、跳过去」以及所有和桌面 Activity 打交道的动作
 * （读状态机、取消待跳转、预热、启动目标桌面）。判定规则都在功能层
 * {@link BouncePolicy}，这里只是调度和执行。
 */
public final class LauncherBridge {

    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private static volatile Activity sCurrentLauncherAct;
    /** Quickstep 手势结算到 HOME 的时刻（uptimeMillis），0 表示没有有效手势 */
    private static volatile long sHomeGestureAt = 0L;
    /** 桌面最后一次失去顶层的时间（0 表示还没失去过） */
    private static volatile long sTopLostAt = 0L;
    /** 最后一次跳到目标桌面的时间，用于防抖 */
    private static volatile long sLastBouncedAt = 0L;

    private static volatile Runnable sPendingBounce;
    private static volatile long sPendingDelay = Long.MAX_VALUE;

    private static volatile String sLastStateName = "";
    /** 最近一次进入最近任务的时间（uptimeMillis），用于「上滑偏高」的判定 */
    private static volatile long sLastOverviewAt = 0L;
    /** 上一次的桌面状态名，用来识别「最近任务 -> 桌面」这种退出 */
    private static volatile String sPrevStateName = "";
    /** 最近一次解锁的时刻（ACTION_USER_PRESENT），用于忽略解锁时漏进来的假点按 */
    private static volatile long sUserPresentAt = 0L;
    private static volatile boolean sScreenWatcherInstalled = false;

    private static volatile Object sStateManager;
    private static Method sAppsVisibleMethod;
    private static Method sDisplayAppsMethod;

    private static volatile boolean sBootWarmArmed = false;
    private static volatile long sLastWarmAt = 0L;

    private LauncherBridge() {
    }

    // ------------------------------------------------------------------
    // 桌面回调
    // ------------------------------------------------------------------

    /** Activity.onResume：记下当前桌面，顺带安排一次开机预热 */
    public static void onLauncherResumed(Activity act) {
        sCurrentLauncherAct = act;
        installScreenWatcher(act);
        maybeBootWarm(act);
        Logs.d("home resumed (state=" + currentStateName(act) + ")");
    }

    /** 监听解锁事件：锁屏时漏给桌面的那次上滑不能当成「点了卡片」 */
    private static void installScreenWatcher(final Activity act) {
        if (sScreenWatcherInstalled) {
            return;
        }
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_USER_PRESENT);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            act.getApplicationContext().registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    String action = intent == null ? null : intent.getAction();
                    if (Intent.ACTION_USER_PRESENT.equals(action)) {
                        sUserPresentAt = SystemClock.uptimeMillis();
                        Logs.i("user present (unlock), state=" + sLastStateName);
                    } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        Logs.d("screen off, state=" + sLastStateName);
                    }
                }
            }, f);
            sScreenWatcherInstalled = true;
            Logs.i("screen watcher installed");
        } catch (Throwable t) {
            Logs.w("screen watcher failed: " + t);
        }
    }

    /**
     * 交给规则层的「距上次点卡片多久」。解锁后一小段时间内当作没点过，
     * 因为解锁上滑会被系统漏给下面的桌面，随手就是一次假点按。
     */
    private static long cardTapAgeForRules(long now) {
        long age = RecentsTouchHandler.cardTapAge(now);
        if (age < 0L) {
            return age;
        }
        long sinceUnlock = sUserPresentAt > 0L ? now - sUserPresentAt : -1L;
        if (sinceUnlock >= 0L && sinceUnlock < BouncePolicy.UNLOCK_TAP_GRACE_MS) {
            Logs.i("ignore card-tap guard: unlocked " + sinceUnlock + "ms ago");
            return -1L;
        }
        return age;
    }

    /** Activity.onTopResumedActivityChanged */
    public static void onTopResumed(Activity act, boolean top) {
        if (act == null || !isHomeActivity(act)) {
            return;
        }
        Logs.d("topResumed=" + top + " state=" + currentStateName(act));
        if (!top) {
            sTopLostAt = SystemClock.uptimeMillis();
            cancelPendingBounce("lost topResumed");
            return;
        }
        long lostAt = sTopLostAt;
        sTopLostAt = 0L;
        if (isOverviewNow(act)) {
            Logs.i("topResumed but overview, skip");
            return;
        }
        long now = SystemClock.uptimeMillis();
        BouncePolicy.Decision decision = BouncePolicy.onTopResumed(
                isHomeGestureFresh(),
                lostAt > 0L ? now - lostAt : -1L,
                sLastBouncedAt > 0L ? now - sLastBouncedAt : -1L,
                cardTapAgeForRules(now),
                isInMultiWindow(act),
                recentsExitSwitch(act));
        apply(act, decision);
    }

    /** Activity.onWindowFocusChanged */
    public static void onWindowFocusChanged(Activity act, boolean focused) {
        if (act == null || !isHomeActivity(act) || focused) {
            return;
        }
        if (sPendingBounce != null) {
            Logs.i("home lost window focus, cancel bounce");
            cancelPendingBounce("lost window focus");
        }
    }

    /** StateManager.goToState 之后 */
    public static void onStateChanged(List<Object> args) {
        if (args == null || args.isEmpty() || args.get(0) == null) {
            return;
        }
        String name = args.get(0).getClass().getSimpleName();
        String prev = sPrevStateName;
        sPrevStateName = name;
        sLastStateName = name;
        Logs.d("state -> " + name + " (prev=" + prev + ")");

        boolean overview = LauncherState.isOverview(name);
        if (overview) {
            sLastOverviewAt = SystemClock.uptimeMillis();
            // 进最近任务就静默拉起目标桌面进程，退出时跳转不用等冷启动
            warmTargetSilently("overview");
            OverviewOverlay.show(sCurrentLauncherAct);
        } else {
            OverviewOverlay.hide();
        }

        Activity act = sCurrentLauncherAct;
        if (overview || LauncherState.isBackground(name)) {
            cancelPendingBounce("non-home state: " + name);
            return;
        }
        if (!LauncherState.isHome(name) || act == null || act.isFinishing()) {
            return;
        }
        boolean fromOverview = LauncherState.isOverview(prev);
        BouncePolicy.Decision decision = BouncePolicy.onHomeState(
                fromOverview,
                sPendingBounce != null,
                isHomeGestureFresh(),
                fromOverview ? recentsExitSwitch(act) : NEVER_ON,
                cardTapAgeForRules(SystemClock.uptimeMillis()),
                name);
        if (decision != null && decision.bounce) {
            Logs.i("recents exit (" + prev + " -> " + name + "), bounce to target");
        }
        apply(act, decision);
    }

    private static void apply(Activity act, BouncePolicy.Decision decision) {
        if (decision == null) {
            return;
        }
        if (!decision.bounce) {
            if (!decision.verboseOnly) {
                Logs.i(decision.reason);
            } else {
                Logs.d(decision.reason);
            }
            return;
        }
        sCurrentLauncherAct = act;
        scheduleBounce(act, decision.delayMs, decision.reason, decision.retries,
                decision.focusRetries);
    }

    // ------------------------------------------------------------------
    // 跳转调度
    // ------------------------------------------------------------------

    public static void scheduleBounce(Activity act, long delayMs, String why) {
        scheduleBounce(act, delayMs, why, 0, BouncePolicy.FOCUS_RETRIES);
    }

    public static void scheduleBounce(Activity act, long delayMs, String why, final int retries) {
        scheduleBounce(act, delayMs, why, retries, BouncePolicy.FOCUS_RETRIES);
    }

    public static void scheduleBounce(Activity act, long delayMs, String why, final int retries,
                                      final int focusRetries) {
        sCurrentLauncherAct = act;
        if (sPendingBounce != null) {
            if (delayMs >= sPendingDelay) {
                Logs.d("keep earlier pending bounce");
                return;
            }
            sHandler.removeCallbacks(sPendingBounce);
            sPendingBounce = null;
            sPendingDelay = Long.MAX_VALUE;
        }
        final Activity target = act;
        final String reason = why;
        Runnable r = new Runnable() {
            @Override
            public void run() {
                sPendingBounce = null;
                sPendingDelay = Long.MAX_VALUE;
                try {
                    if (target.isFinishing() || target.isDestroyed()) {
                        Logs.i("skip bounce: activity gone");
                        return;
                    }
                    if (isOverviewNow(target)) {
                        if (retries > 0) {
                            Logs.i("overview still showing, retry bounce (" + retries + " left)");
                            scheduleBounce(target, BouncePolicy.RECENTS_EXIT_DELAY_MS, reason,
                                    retries - 1, focusRetries);
                        } else {
                            Logs.i("skip bounce: overview showing");
                        }
                        return;
                    }
                    if (!target.hasWindowFocus()) {
                        // 桌面还没拿到焦点：可能是真的在开应用，也可能是刚从锁屏解锁
                        // （钥匙串界面还没退干净）。等一会儿再看，开应用时这个待跳会被取消。
                        if (focusRetries > 0) {
                            Logs.i("home not focused yet, retry bounce (" + focusRetries
                                    + " left)");
                            scheduleBounce(target, BouncePolicy.FOCUS_RETRY_MS, reason, retries,
                                    focusRetries - 1);
                        } else {
                            Logs.i("skip bounce: home not focused (probably opening an app)");
                        }
                        return;
                    }
                    bounceToTarget(target);
                } catch (Throwable t) {
                    Logs.e("delayed bounce failed", t);
                }
            }
        };
        sPendingBounce = r;
        sPendingDelay = delayMs;
        sHandler.postDelayed(r, delayMs);
        Logs.d("scheduled bounce in " + delayMs + "ms (" + reason + ")");
    }

    public static void cancelPendingBounce(String why) {
        Runnable r = sPendingBounce;
        if (r != null) {
            sHandler.removeCallbacks(r);
            sPendingBounce = null;
            sPendingDelay = Long.MAX_VALUE;
            Logs.d("cancelled pending bounce: " + why);
        }
    }

    public static boolean hasPendingBounce() {
        return sPendingBounce != null;
    }

    /** 真正发出跳转 */
    public static void bounceToTarget(Activity act) {
        TargetLauncher.refreshIfStale(act);
        Intent target = TargetLauncher.launchIntent();
        if (target == null) {
            Logs.w("target launcher activity unknown: " + TargetLauncher.pkg());
            return;
        }
        try {
            act.startActivity(target);
            sLastBouncedAt = SystemClock.uptimeMillis();
            consumeHomeGesture();
            Logs.i("bounced -> " + TargetLauncher.pkg() + ", from "
                    + act.getComponentName().flattenToShortString()
                    + " state=" + sLastStateName);
        } catch (Throwable t) {
            Logs.e("bounce failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 手势令牌
    // ------------------------------------------------------------------

    /** Quickstep 手势结算到 HOME 时调用 */
    public static void armHomeGesture() {
        sHomeGestureAt = SystemClock.uptimeMillis();
    }

    /** 换了终点（RECENTS/LAST_TASK…）说明不是回桌面，撤销令牌 */
    public static void disarmHomeGesture() {
        sHomeGestureAt = 0L;
    }

    public static boolean isHomeGestureFresh() {
        long t = sHomeGestureAt;
        if (t <= 0L) {
            return false;
        }
        return BouncePolicy.isHomeGestureFresh(SystemClock.uptimeMillis() - t);
    }

    private static void consumeHomeGesture() {
        sHomeGestureAt = 0L;
    }

    // ------------------------------------------------------------------
    // 预热
    // ------------------------------------------------------------------

    /** 预热总是回到主线程做（钩子可能在别的线程上） */
    public static void postWarm(final String why) {
        sHandler.post(new Runnable() {
            @Override
            public void run() {
                warmTargetSilently(why);
            }
        });
    }

    /** 开机后第一次回到桌面时，安排一次延迟预热 */
    private static void maybeBootWarm(Activity act) {
        if (sBootWarmArmed) {
            return;
        }
        long up = SystemClock.elapsedRealtime();
        sBootWarmArmed = true;
        if (up > BouncePolicy.BOOT_WARM_WINDOW_MS) {
            return; // 不是「刚开机」（模块中途加载），不预热
        }
        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                warmTargetSilently("boot");
            }
        }, BouncePolicy.BOOT_WARM_DELAY_MS);
        Logs.i("boot warm scheduled in " + BouncePolicy.BOOT_WARM_DELAY_MS
                + "ms (uptime=" + up + "ms)");
    }

    /**
     * 静默预热目标桌面：acquire 它 exported 且免权限的 ContentProvider。
     * 系统会为目标桌面创建进程（Application.onCreate + 资源加载），但不弹任何界面。
     */
    public static void warmTargetSilently(String why) {
        Activity act = sCurrentLauncherAct;
        if (act == null) {
            Logs.w("warm(" + why + ") skipped: no launcher activity");
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - sLastWarmAt < BouncePolicy.WARM_MIN_INTERVAL_MS) {
            return;
        }
        sLastWarmAt = now;
        TargetLauncher.refreshIfStale(act);
        android.net.Uri uri = TargetLauncher.warmParsedUri();
        if (uri == null) {
            Logs.d("warm(" + why + ") skipped: no exported provider for "
                    + TargetLauncher.pkg());
            return;
        }
        ContentProviderClient cpc = null;
        try {
            ContentResolver cr = act.getContentResolver();
            cpc = cr.acquireContentProviderClient(uri);
            if (cpc != null) {
                Logs.i("warm(" + why + ") -> " + TargetLauncher.pkg() + " process requested");
            } else {
                Logs.w("warm(" + why + ") -> provider not acquired: " + uri);
            }
        } catch (Throwable t) {
            Logs.w("warm(" + why + ") failed: " + t);
        } finally {
            if (cpc != null) {
                try {
                    cpc.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 桌面状态读取
    // ------------------------------------------------------------------

    /** 最近任务退出拦截的开关（懒读设置，5s 缓存） */
    private static BouncePolicy.Switch recentsExitSwitch(final Activity act) {
        return new BouncePolicy.Switch() {
            @Override
            public boolean isOn() {
                TargetLauncher.refreshIfStale(act);
                return TargetLauncher.recentsExitEnabled();
            }
        };
    }

    private static final BouncePolicy.Switch NEVER_ON = new BouncePolicy.Switch() {
        @Override
        public boolean isOn() {
            return false;
        }
    };

    private static boolean isInMultiWindow(Activity act) {
        try {
            return act.isInMultiWindowMode();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isHomeActivity(Activity act) {
        ComponentName cn = act.getComponentName();
        if (cn == null || !isLauncherPkg(cn.getPackageName())) {
            return false;
        }
        // 最近任务的 RecentsActivity 也在 launcher 包里，只认带 CATEGORY_HOME 的 MAIN intent
        Intent it = act.getIntent();
        return it != null
                && Intent.ACTION_MAIN.equals(it.getAction())
                && it.hasCategory(Intent.CATEGORY_HOME);
    }

    public static boolean isLauncherPkg(String pkg) {
        for (String p : Constants.LAUNCHER_PKGS) {
            if (p.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isOverviewNow(Activity act) {
        return LauncherState.isOverview(currentStateName(act));
    }

    /** 是否刚刚进过最近任务（状态机回调比 getState() 早一拍，用它兜住） */
    public static boolean wasOverviewRecently() {
        long t = sLastOverviewAt;
        return t > 0L && SystemClock.uptimeMillis() - t <= BouncePolicy.OVERVIEW_MEMORY_MS;
    }

    /** 桌面当前状态名（读不到就退回最近一次 goToState 记录的名字） */
    public static String currentStateName(Activity act) {
        try {
            Object sm = sStateManager;
            if (sm == null) {
                sm = findStateManager(act);
                sStateManager = sm;
            }
            if (sm == null) {
                return sLastStateName;
            }
            Method getState = sm.getClass().getMethod("getState");
            Object state = getState.invoke(sm);
            return state == null ? sLastStateName : state.getClass().getSimpleName();
        } catch (Throwable t) {
            return sLastStateName;
        }
    }

    private static Object findStateManager(Activity act) {
        Class<?> c = act.getClass();
        while (c != null && c != Object.class) {
            try {
                Method m = c.getDeclaredMethod("getStateManager");
                m.setAccessible(true);
                return m.invoke(act);
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        c = act.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().getSimpleName().equals("StateManager")) {
                    try {
                        f.setAccessible(true);
                        return f.get(act);
                    } catch (Throwable ignored) {
                    }
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** 桌面上是否开着「所有应用」抽屉（此时上滑是滚列表，不是回桌面） */
    public static boolean isAppsViewOpen(Activity act) {
        Class<?> c = act.getClass();
        try {
            if (sAppsVisibleMethod == null || sAppsVisibleMethod.getDeclaringClass() != c) {
                sAppsVisibleMethod = findNoArgBoolean(c, "isAppsViewVisible");
                sDisplayAppsMethod = findNoArgBoolean(c, "isDisplayAppsView");
            }
            if (sAppsVisibleMethod != null && Boolean.TRUE.equals(sAppsVisibleMethod.invoke(act))) {
                return true;
            }
            if (sDisplayAppsMethod != null && Boolean.TRUE.equals(sDisplayAppsMethod.invoke(act))) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static Method findNoArgBoolean(Class<?> c, String name) {
        try {
            Method m = c.getMethod(name);
            return m.getReturnType() == boolean.class ? m : null;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** 起手时是否真的停在桌面工作区（不是抽屉 / 最近任务） */
    public static boolean isSittingOnHomeWorkspace(Activity act) {
        if (act == null || act.isFinishing() || act.isDestroyed()) {
            return false;
        }
        String st = currentStateName(act);
        boolean appsOpen = isAppsViewOpen(act);
        boolean ok = LauncherState.isHome(st) && !LauncherState.isOverview(st) && !appsOpen;
        Logs.d("home workspace check: state=" + st + " appsViewOpen=" + appsOpen + " ok=" + ok);
        return ok;
    }

    public static Activity currentActivity() {
        return sCurrentLauncherAct;
    }
}
