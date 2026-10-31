package com.dsh.magichome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Window;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

/**
 * MagicHome —— 让「导航栏上滑回桌面」落到 Nova Launcher。
 *
 * 设计原则（按机主要求）：
 *  - 每次回桌面只尝试一次，不做补跳；
 *  - 手指一碰屏幕就取消待执行跳转（不抢控制权）；
 *  - 触发点用「桌面成为顶层 resumed」的精确时刻，而不是盲等固定延迟。
 *
 * 荣耀 MagicOS 不允许第三方桌面做默认桌面，上滑走「桌面任务置前」，
 * 只能在荣耀桌面进程里重定向。
 */
public class HookEntry extends XposedModule {

    private static final String TAG = "MagicHome";
    private static final String DEFAULT_PKG = "com.teslacoilsw.launcher";
    private static final String DEFAULT_ACT = "com.teslacoilsw.launcher.NovaLauncher";

    private static final String[] LAUNCHER_PKGS = {
            "com.hihonor.android.launcher",
            "com.hihonor.hnofficelauncher"
    };

    private static final boolean DEBUG = true;
    /** 顶层 resumed 后的缓冲 */
    private static final long TOP_RESUMED_DELAY_MS = 16L;
    /** 状态机兜底延迟（万一没收到 onTopResumedActivityChanged） */
    private static final long FALLBACK_DELAY_MS = 400L;
    /**
     * 手势令牌有效期：只有 Quickstep 手势结算到 HOME（GestureState.setEndTarget(HOME)）
     * 之后的这段时间内的「桌面 topResumed」才算真·上滑回桌面。
     */
    private static final long HOME_GESTURE_TTL_MS = 1200L;

    /**
     * 开机静默预热：模块加载后延迟多久去拉 Nova 进程（避开开机/解锁高峰）。
     * 只拉起进程与资源，不显示任何界面。
     */
    private static final long BOOT_WARM_DELAY_MS = 6000L;
    /** 只有开机后这个时间窗内才做一次开机预热（超过就当成不是"刚开机"） */
    private static final long BOOT_WARM_WINDOW_MS = 600_000L;
    /**
     * Nova 对外暴露的 ContentProvider（exported=true、无权限限制）。
     * 从别的进程 acquire 它，系统会静默创建 Nova 进程（跑 Application.onCreate、
     * 加载资源与 dex），但不会显示任何 Activity。
     */
    private static final String DEFAULT_WARM_URI = "content://com.teslacoilsw.launcher.api";
    /** 上滑手势一开始（从别的 App 起手）就静默预热 Nova，让收尾跳转时资源已就绪 */
    private static final boolean GESTURE_PREWARM = true;

    /** 目标桌面的设置出口（模块设置页写入，本进程只读）；5s 缓存，改完立刻生效 */
    private static final String SETTINGS_URI = SettingsProvider.URI_TARGET;
    private static final long TARGET_TTL_MS = 5000L;
    private static volatile String sTargetPkg = DEFAULT_PKG;
    private static volatile String sTargetAct = DEFAULT_ACT;
    private static volatile String sTargetWarmUri = DEFAULT_WARM_URI;
    private static volatile long sTargetLoadedAt = 0L;

    /**
     * 停在荣耀默认桌面时，快速上滑直接进 Nova。
     * 这是「已经回到系统桌面但没被模块接走」时的逃生通道（例如刚开机、或从最近任务回到桌面）。
     * 只认「快、直、够长」的上滑，避免和桌面翻页/抽屉滚动/下拉通知冲突。
     */
    private static final boolean ENABLE_HOME_SWIPE_TO_TARGET = true;
    /** 上滑位移下限（px）；本机 density=600，220px ≈ 84dp */
    private static final float HOME_SWIPE_MIN_PX = 220f;
    /** 上滑耗时上限：超过就不算「快速」 */
    private static final long HOME_SWIPE_MAX_MS = 400L;
    /** 横向漂移上限（相对纵向位移的比例），排除斜滑/翻页 */
    private static final float HOME_SWIPE_MAX_DX_RATIO = 0.6f;
    /** 两次桌面快速上滑之间的冷却，防止连跳 */
    private static final long HOME_SWIPE_COOLDOWN_MS = 1200L;
    /**
     * 底部手势区判定：起手点 y >= 屏高 - 该值 才算「从屏幕底部边缘上滑」。
     * 底部边缘滑动会被系统手势接管（Activity 只收到 DOWN 就 CANCEL），
     * 所以这条路径改从 Quickstep 手势管线里取事件。
     */
    private static final float HOME_EDGE_BOTTOM_PX = 240f;

    /** system_server 原生层 */
    private static final boolean ENABLE_SYSTEM_SERVER_LAYER = false;
    /** 探针模式：只记录系统方法调用，不改变行为（定位 home 任务置前入口用） */
    private static final boolean PROBE_MODE = false;

    private static volatile boolean sSysServerHooked = false;
    private static volatile boolean sLauncherHooked = false;
    private static volatile Activity sCurrentLauncherAct;
    /** Quickstep 手势结算到 HOME 的时刻（uptimeMillis），0 表示没有有效手势 */
    private static volatile long sHomeGestureAt = 0L;

    /** 本次开机后的静默预热是否已经安排过 */
    private volatile boolean mBootWarmArmed = false;
    /** 上一次静默预热的 uptime，短时间内不重复请求 */
    private volatile long mLastWarmAt = 0L;

    /** 桌面快速上滑追踪状态 */
    private boolean mSwipeTracking = false;
    /** 本次上滑起手时是否停在桌面 workspace（不是抽屉/最近任务） */
    private boolean mSwipeStartOnHome = false;
    private Activity mSwipeAct;

    /** Quickstep 手势管线里的底部边缘手势追踪 */
    private boolean mEdgeTracking = false;
    private float mEdgeDownX = 0f;
    private float mEdgeDownY = 0f;
    private long mEdgeDownAt = 0L;
    private boolean mEdgeStartOnHome = false;
    private float mSwipeDownX = 0f;
    private float mSwipeDownY = 0f;
    private long mSwipeDownAt = 0L;
    private volatile long mLastHomeSwipeJumpAt = 0L;
    /** 反射缓存：荣耀桌面是否正在显示/播放「所有应用」抽屉 */
    private Method mAppsVisibleMethod;
    private Method mDisplayAppsMethod;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private volatile Runnable mPendingBounce;
    private volatile long mPendingDelay = Long.MAX_VALUE;
    private volatile String mLastStateName = "";
    private volatile Object mStateManager;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "module loaded, systemServer=" + param.isSystemServer()
                + " process=" + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        log(Log.INFO, TAG, "package loaded: " + pkg);
        try {
            if (isLauncherPkg(pkg)) {
                hookLauncherProcess(param.getDefaultClassLoader());
            } else if ("android".equals(pkg) && ENABLE_SYSTEM_SERVER_LAYER) {
                if (PROBE_MODE) {
                    installSystemProbes(param.getDefaultClassLoader());
                } else {
                    hookSystemServer(param.getDefaultClassLoader());
                }
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "onPackageLoaded(" + pkg + ") failed", t);
        }
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        if (!ENABLE_SYSTEM_SERVER_LAYER) {
            return;
        }
        try {
            if (PROBE_MODE) {
                installSystemProbes(param.getClassLoader());
            } else {
                hookSystemServer(param.getClassLoader());
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "onSystemServerStarting failed", t);
        }
    }

    private static boolean isLauncherPkg(String pkg) {
        for (String p : LAUNCHER_PKGS) {
            if (p.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 荣耀桌面进程
    // ------------------------------------------------------------------

    private void hookLauncherProcess(ClassLoader cl) {
        if (sLauncherHooked) {
            return;
        }
        sLauncherHooked = true;

        try {
            Method m = Activity.class.getDeclaredMethod(
                    "onTopResumedActivityChanged", boolean.class);
            hook(m)
                    .setId("magichome-top-resumed")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            Object out = chain.proceed();
                            try {
                                onTopResumedChanged(chain.getThisObject(), chain.getArg(0));
                            } catch (Throwable t) {
                                log(Log.ERROR, TAG, "topResumed handler failed", t);
                            }
                            return out;
                        }
                    });
            log(Log.INFO, TAG, "hooked Activity.onTopResumedActivityChanged");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "topResumed hook failed: " + t);
        }

        // 手势刚起（桌面 onResume）就把窗口藏掉，避免转场里出现荣耀桌面图标
        try {
            Method m = Activity.class.getDeclaredMethod("onResume");
            hook(m)
                    .setId("magichome-resume")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            Object out = chain.proceed();
                            try {
                                Object self = chain.getThisObject();
                                if (self instanceof Activity) {
                                    Activity act = (Activity) self;
                                    if (isHomeActivity(act)) {
                                        sCurrentLauncherAct = act;
                                        maybeBootWarm(act);
                                        if (DEBUG) {
                                            log(Log.INFO, TAG, "home resumed (state="
                                                    + currentStateName(act) + ")");
                                        }
                                    }
                                }
                            } catch (Throwable t) {
                                log(Log.WARN, TAG, "resume hide failed: " + t);
                            }
                            return out;
                        }
                    });
            log(Log.INFO, TAG, "hooked Activity.onResume (hide before transition)");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "resume hook failed: " + t);
        }

        // 窗口焦点：桌面一旦失焦（比如正在打开别的 App），立刻取消待跳转
        try {
            Method m = Activity.class.getDeclaredMethod("onWindowFocusChanged", boolean.class);
            hook(m)
                    .setId("magichome-focus")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            Object out = chain.proceed();
                            try {
                                Object self = chain.getThisObject();
                                boolean focused = Boolean.TRUE.equals(chain.getArg(0));
                                if (self instanceof Activity && isHomeActivity((Activity) self)
                                        && !focused && mPendingBounce != null) {
                                    log(Log.INFO, TAG, "home lost window focus, cancel bounce");
                                    cancelPendingBounce("lost window focus");
                                }
                            } catch (Throwable t) {
                                log(Log.WARN, TAG, "focus handler failed: " + t);
                            }
                            return out;
                        }
                    });
            log(Log.INFO, TAG, "hooked Activity.onWindowFocusChanged");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "focus hook failed: " + t);
        }

        try {
            Method m = Activity.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
            hook(m)
                    .setId("magichome-touch")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            try {
                                Object arg = chain.getArg(0);
                                if (arg instanceof MotionEvent) {
                                    Object self = chain.getThisObject();
                                    Activity act = self instanceof Activity ? (Activity) self : null;
                                    handleTouch(act, (MotionEvent) arg);
                                }
                            } catch (Throwable t) {
                                log(Log.WARN, TAG, "touch handler failed: " + t);
                            }
                            return chain.proceed();
                        }
                    });
            log(Log.INFO, TAG, "hooked Activity.dispatchTouchEvent");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "touch hook failed: " + t);
        }

        hookLauncherStateMachine(cl);
        hookQuickstep(cl);
        armRecentsAppInfoHook(cl);
    }

    /** 最近任务卡片顶栏注入「应用信息」按钮。 */
    private void armRecentsAppInfoHook(ClassLoader cl) {
        String headerCls = "com.hihonor.android.launcher.quickstep.views.TaskViewHeader";
        try {
            Class<?> c = Class.forName(headerCls, false, cl);
            Method m = c.getDeclaredMethod("onFinishInflate");
            hook(m)
                    .setId("magichome-appinfo-button")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            Object out = chain.proceed();
                            try {
                                Object self = chain.getThisObject();
                                if (self instanceof android.view.ViewGroup) {
                                    RecentsAppInfo.attach((android.view.ViewGroup) self);
                                }
                            } catch (Throwable t) {
                                log(Log.WARN, TAG, "appinfo attach failed: " + t);
                            }
                            return out;
                        }
                    });
            log(Log.INFO, TAG, "appinfo button hook installed on " + headerCls);
            armTaskViewTouchHook(cl);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "appinfo button hook failed: " + t);
        }
    }

    /** 把「应用信息」按钮的触摸从 TaskView 手里拦过来。 */
    private void armTaskViewTouchHook(ClassLoader cl) {
        String[] classes = {
                "com.android.quickstep.views.TaskView",
                "com.android.quickstep.views.BaseTaskView",
        };
        for (String cn : classes) {
            try {
                Class<?> c = Class.forName(cn, false, cl);
                Method m = null;
                try {
                    m = c.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
                } catch (NoSuchMethodException e) {
                    continue;
                }
                hook(m)
                        .setId("magichome-appinfo-touch-" + c.getSimpleName())
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(new Hooker() {
                            @Override
                            public Object intercept(Chain chain) throws Throwable {
                                try {
                                    Object self = chain.getThisObject();
                                    Object a = chain.getArg(0);
                                    if (self instanceof android.view.View && a instanceof MotionEvent) {
                                        android.view.View v = (android.view.View) self;
                                        Object task = fieldValueOf(self, "mTask");
                                        if (RecentsAppInfo.handleTouch(v, (MotionEvent) a, task)) {
                                            return Boolean.TRUE;
                                        }
                                    }
                                } catch (Throwable t) {
                                    log(Log.WARN, TAG, "appinfo touch hook failed: " + t);
                                }
                                return chain.proceed();
                            }
                        });
                log(Log.INFO, TAG, "appinfo touch hook installed on " + cn);
                return;
            } catch (Throwable t) {
                log(Log.WARN, TAG, "appinfo touch hook failed on " + cn + ": " + t);
            }
        }
    }

    private static Object fieldValueOf(Object o, String name) {
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

    private void hookLauncherStateMachine(ClassLoader cl) {
        try {
            Class<?> sm = Class.forName("com.android.launcher3.statemanager.StateManager", false, cl);
            int n = 0;
            for (Method m : sm.getDeclaredMethods()) {
                if (!"goToState".equals(m.getName()) || m.getParameterCount() == 0) {
                    continue;
                }
                try {
                    hook(m)
                            .setId("magichome-state-" + m.getParameterCount())
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .intercept(new Hooker() {
                                @Override
                                public Object intercept(Chain chain) throws Throwable {
                                    Object out = chain.proceed();
                                    try {
                                        onLauncherStateChanged(chain.getArgs());
                                    } catch (Throwable t) {
                                        log(Log.ERROR, TAG, "state handler failed", t);
                                    }
                                    return out;
                                }
                            });
                    n++;
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "hook goToState failed: " + t);
                }
            }
            log(Log.INFO, TAG, "state machine hooks installed: " + n);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "StateManager not hookable: " + t);
        }
    }

    private void onTopResumedChanged(Object self, Object topArg) {
        if (!(self instanceof Activity)) {
            return;
        }
        Activity act = (Activity) self;
        if (!isHomeActivity(act)) {
            return;
        }
        boolean top = Boolean.TRUE.equals(topArg);
        if (DEBUG) {
            log(Log.INFO, TAG, "topResumed=" + top + " state=" + currentStateName(act));
        }
        if (!top) {
            cancelPendingBounce("lost topResumed");
            return;
        }
        if (isOverviewNow(act)) {
            log(Log.INFO, TAG, "topResumed but overview, skip");
            return;
        }
        if (!isHomeGestureFresh()) {
            log(Log.INFO, TAG, "topResumed without home-gesture token, skip");
            return;
        }
        sCurrentLauncherAct = act;
        scheduleBounce(act, TOP_RESUMED_DELAY_MS, "topResumed+homeGesture");
    }

    private void onLauncherStateChanged(List<Object> args) {
        if (args == null || args.isEmpty() || args.get(0) == null) {
            return;
        }
        String name = args.get(0).getClass().getSimpleName();
        mLastStateName = name;
        if (DEBUG) {
            log(Log.INFO, TAG, "state -> " + name);
        }
        if (isOverviewStateName(name)) {
            armOverviewProbe();
        }
        Activity act = sCurrentLauncherAct;
        if (isOverviewStateName(name) || name.contains("Background")) {
            cancelPendingBounce("non-home state: " + name);
            return;
        }
        if (isHomeStateName(name) && act != null && !act.isFinishing()) {
            // 兜底：顶层 resumed 回调没来时再跳一次；但必须是真·上滑回桌面手势之后
            if (mPendingBounce == null && isHomeGestureFresh()) {
                scheduleBounce(act, FALLBACK_DELAY_MS, "state=" + name + "+homeGesture");
            }
        }
    }

    private static final boolean PROBE_OVERVIEW = false;
    private static long sLastOverviewProbeAt;

    private void armOverviewProbe() {
        if (!PROBE_OVERVIEW) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - sLastOverviewProbeAt < 3000L) {
            return;
        }
        sLastOverviewProbeAt = now;
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Activity act = sCurrentLauncherAct;
                if (act == null || act.isFinishing()) {
                    return;
                }
                Probe.dumpOverview(act);
            }
        }, 350L);
    }

    private void scheduleBounce(Activity act, long delayMs, String why) {
        sCurrentLauncherAct = act;
        if (mPendingBounce != null) {
            if (delayMs >= mPendingDelay) {
                if (DEBUG) {
                    log(Log.INFO, TAG, "keep earlier pending bounce");
                }
                return;
            }
            mHandler.removeCallbacks(mPendingBounce);
            mPendingBounce = null;
            mPendingDelay = Long.MAX_VALUE;
        }
        final Activity target = act;
        final String reason = why;
        Runnable r = new Runnable() {
            @Override
            public void run() {
                mPendingBounce = null;
                mPendingDelay = Long.MAX_VALUE;
                try {
                    if (target.isFinishing() || target.isDestroyed()) {
                        log(Log.INFO, TAG, "skip bounce: activity gone");
                        return;
                    }
                    if (isOverviewNow(target)) {
                        log(Log.INFO, TAG, "skip bounce: overview showing");
                        return;
                    }
                    if (!target.hasWindowFocus()) {
                        log(Log.INFO, TAG, "skip bounce: home not focused (probably opening an app)");
                        return;
                    }
                    bounceToTarget(target);
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "delayed bounce failed", t);
                }
            }
        };
        mPendingBounce = r;
        mPendingDelay = delayMs;
        mHandler.postDelayed(r, delayMs);
        if (DEBUG) {
            log(Log.INFO, TAG, "scheduled bounce in " + delayMs + "ms (" + reason + ")");
        }
    }

    private void cancelPendingBounce(String why) {
        Runnable r = mPendingBounce;
        if (r != null) {
            mHandler.removeCallbacks(r);
            mPendingBounce = null;
            mPendingDelay = Long.MAX_VALUE;
            if (DEBUG) {
                log(Log.INFO, TAG, "cancelled pending bounce: " + why);
            }
        }
    }

    /** 最近一次 Quickstep 手势是否结算到 HOME（真·上滑回桌面） */
    private static boolean isHomeGestureFresh() {
        long t = sHomeGestureAt;
        return t > 0L && SystemClock.uptimeMillis() - t <= HOME_GESTURE_TTL_MS;
    }

    /** 用掉令牌，避免同一次手势跳两次 */
    private static void consumeHomeGesture() {
        sHomeGestureAt = 0L;
    }

    // ------------------------------------------------------------------
    // 桌面快速上滑 → Nova（停在本机默认桌面时的逃生通道）
    // ------------------------------------------------------------------

    // ---- 桌面快速上滑（从屏幕底部边缘起手）→ Nova ----

    /** 起手点是否落在屏幕底部手势区 */
    private boolean isInBottomEdge(Activity act, float y) {
        try {
            int h = act.getResources().getDisplayMetrics().heightPixels;
            return h > 0 && y >= h - HOME_EDGE_BOTTOM_PX;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 起手时是否真的停在桌面 workspace（不是抽屉 / 最近任务） */
    private boolean isSittingOnHomeWorkspace(Activity act) {
        if (act == null || act.isFinishing() || act.isDestroyed()) {
            return false;
        }
        String st = currentStateName(act);
        boolean appsOpen = isAppsViewOpen(act);
        boolean ok = isHomeStateName(st) && !isOverviewStateName(st) && !appsOpen;
        if (DEBUG) {
            log(Log.INFO, TAG, "home workspace check: state=" + st
                    + " appsViewOpen=" + appsOpen + " ok=" + ok);
        }
        return ok;
    }

    private void handleTouch(Activity act, MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (mPendingBounce != null) {
                log(Log.INFO, TAG, "user touched, cancel pending bounce");
                cancelPendingBounce("user touch");
            }
            mSwipeTracking = false;
            if (!ENABLE_HOME_SWIPE_TO_TARGET || act == null || !isHomeActivity(act)
                    || !isInBottomEdge(act, ev.getY())) {
                return;
            }
            mSwipeTracking = true;
            mSwipeDownX = ev.getX();
            mSwipeDownY = ev.getY();
            mSwipeDownAt = SystemClock.uptimeMillis();
            mSwipeAct = act;
            mSwipeStartOnHome = isSittingOnHomeWorkspace(act);
            if (GESTURE_PREWARM) {
                warmTargetSilently("home-swipe");
            }
        } else if (action == MotionEvent.ACTION_UP) {
            if (mSwipeTracking) {
                mSwipeTracking = false;
                Activity target = mSwipeAct != null ? mSwipeAct : act;
                handleHomeSwipeUp(target, mSwipeDownX, mSwipeDownY, mSwipeDownAt,
                        ev.getX(), ev.getY(), mSwipeStartOnHome, "activity");
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            mSwipeTracking = false;
        }
    }

    /**
     * Quickstep 手势管线里看到的事件。
     * 关键：从屏幕底部边缘上滑会被 SystemUI 的手势 nav 接管，
     * 桌面 Activity 的 dispatchTouchEvent 只会先收到一个 DOWN、随后 CANCEL，
     * 完整的手势（DOWN…UP）只在这里看得到。
     */
    private void handleGesturePipelineEvent(MotionEvent ev) {
        if (!ENABLE_HOME_SWIPE_TO_TARGET) {
            return;
        }
        int action = ev.getActionMasked();
        Activity act = sCurrentLauncherAct;
        if (action == MotionEvent.ACTION_DOWN) {
            mEdgeTracking = false;
            if (act == null || act.isFinishing() || act.isDestroyed()
                    || !isInBottomEdge(act, ev.getY())) {
                return;
            }
            mEdgeTracking = true;
            mEdgeDownX = ev.getX();
            mEdgeDownY = ev.getY();
            mEdgeDownAt = SystemClock.uptimeMillis();
            mEdgeStartOnHome = isSittingOnHomeWorkspace(act);
            if (DEBUG) {
                log(Log.INFO, TAG, "edge gesture start y=" + (int) ev.getY()
                        + " home=" + mEdgeStartOnHome);
            }
            if (GESTURE_PREWARM) {
                warmTargetSilently("home-swipe");
            }
        } else if (action == MotionEvent.ACTION_UP) {
            if (mEdgeTracking) {
                mEdgeTracking = false;
                handleHomeSwipeUp(act, mEdgeDownX, mEdgeDownY, mEdgeDownAt,
                        ev.getX(), ev.getY(), mEdgeStartOnHome, "gesture");
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            mEdgeTracking = false;
        }
    }

    private void handleHomeSwipeUp(Activity act, float downX, float downY, long downAt,
                                   float upX, float upY, boolean startedOnHome, String src) {
        if (act == null || act.isFinishing() || act.isDestroyed()) {
            return;
        }
        float dy = upY - downY;
        float dx = upX - downX;
        long dt = SystemClock.uptimeMillis() - downAt;
        if (dy > -HOME_SWIPE_MIN_PX) {
            return; // 位移不够（普通滑动 / 慢拖 / 左右翻页）
        }
        if (dt > HOME_SWIPE_MAX_MS) {
            if (DEBUG) {
                log(Log.INFO, TAG, "home swipe ignored: slow (" + dt + "ms, dy=" + (int) dy
                        + ", src=" + src + ")");
            }
            return;
        }
        if (Math.abs(dx) > Math.abs(dy) * HOME_SWIPE_MAX_DX_RATIO) {
            if (DEBUG) {
                log(Log.INFO, TAG, "home swipe ignored: diagonal (dx=" + (int) dx
                        + ", src=" + src + ")");
            }
            return;
        }
        if (!startedOnHome) {
            return; // 起手时不在桌面 workspace（从 App 上滑走的是手势令牌那条路）
        }
        if (!act.hasWindowFocus()) {
            return;
        }
        if (isAppsViewOpen(act)) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - mLastHomeSwipeJumpAt < HOME_SWIPE_COOLDOWN_MS) {
            return;
        }
        mLastHomeSwipeJumpAt = now;
        log(Log.INFO, TAG, "home swipe-up -> " + sTargetPkg + " (src=" + src + ", dy=" + (int) dy
                + ", dx=" + (int) dx + ", dt=" + dt + "ms)");
        bounceToTarget(act);
    }

    /** 荣耀桌面是否正显示/正在播「所有应用」抽屉（此时上滑是滚列表，不是回桌面） */
    private boolean isAppsViewOpen(Activity act) {
        Class<?> c = act.getClass();
        try {
            if (mAppsVisibleMethod == null || mAppsVisibleMethod.getDeclaringClass() != c) {
                mAppsVisibleMethod = findNoArgBoolean(c, "isAppsViewVisible");
                mDisplayAppsMethod = findNoArgBoolean(c, "isDisplayAppsView");
            }
            if (mAppsVisibleMethod != null && Boolean.TRUE.equals(mAppsVisibleMethod.invoke(act))) {
                return true;
            }
            if (mDisplayAppsMethod != null && Boolean.TRUE.equals(mDisplayAppsMethod.invoke(act))) {
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

    // ------------------------------------------------------------------
    // 静默预热：只拉起 Nova 进程 / 加载资源，不显示任何界面
    // ------------------------------------------------------------------

    /** 开机后第一次回到桌面时，安排一次延迟预热 */
    private void maybeBootWarm(Activity act) {
        if (mBootWarmArmed) {
            return;
        }
        long up = SystemClock.elapsedRealtime();
        if (up > BOOT_WARM_WINDOW_MS) {
            // 不是"刚开机"的场景（比如模块是中途加载的），不做预热
            mBootWarmArmed = true;
            return;
        }
        mBootWarmArmed = true;
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                warmTargetSilently("boot");
            }
        }, BOOT_WARM_DELAY_MS);
        log(Log.INFO, TAG, "boot warm scheduled in " + BOOT_WARM_DELAY_MS
                + "ms (uptime=" + up + "ms)");
    }

    /**
     * 静默预热目标桌面：acquire 它在设置页里解析出来的、exported 且免权限的
     * ContentProvider。系统会为 provider 创建目标桌面进程（Application.onCreate +
     * 资源加载），但不弹任何界面。Nova 用它的 api provider，其他桌面用第一个公开 provider。
     */
    private void warmTargetSilently(String why) {
        Activity act = sCurrentLauncherAct;
        if (act == null) {
            log(Log.WARN, TAG, "warm(" + why + ") skipped: no launcher activity");
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - mLastWarmAt < 1500L) {
            return;
        }
        mLastWarmAt = now;
        refreshTargetIfStale(act);
        String uri = sTargetWarmUri;
        if (uri == null || uri.isEmpty()) {
            if (DEBUG) {
                log(Log.INFO, TAG, "warm(" + why + ") skipped: no exported provider for "
                        + sTargetPkg);
            }
            return;
        }
        ContentProviderClient cpc = null;
        try {
            ContentResolver cr = act.getContentResolver();
            cpc = cr.acquireContentProviderClient(Uri.parse(uri));
            if (cpc != null) {
                log(Log.INFO, TAG, "warm(" + why + ") -> " + sTargetPkg + " process requested");
            } else {
                log(Log.WARN, TAG, "warm(" + why + ") -> provider not acquired: " + uri);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "warm(" + why + ") failed: " + t);
        } finally {
            if (cpc != null) {
                try {
                    cpc.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 读取设置页写入的目标桌面；未配置时保持默认 Nova */
    private void refreshTargetIfStale(Context ctx) {
        if (ctx == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (sTargetLoadedAt != 0L && now - sTargetLoadedAt < TARGET_TTL_MS) {
            return;
        }
        sTargetLoadedAt = now;
        String pkg = null;
        String act = null;
        String warm = null;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(Uri.parse(SETTINGS_URI), null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ip = c.getColumnIndex(SettingsProvider.COL_PKG);
                int ia = c.getColumnIndex(SettingsProvider.COL_ACT);
                int iw = c.getColumnIndex(SettingsProvider.COL_WARM);
                if (ip >= 0) {
                    pkg = c.getString(ip);
                }
                if (ia >= 0) {
                    act = c.getString(ia);
                }
                if (iw >= 0) {
                    warm = c.getString(iw);
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "read target settings failed: " + t);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (pkg == null || pkg.trim().isEmpty() || act == null || act.trim().isEmpty()) {
            if (DEBUG) {
                log(Log.INFO, TAG, "target launcher not configured, using " + DEFAULT_PKG);
            }
            return;
        }
        int slash = act.indexOf('/');
        String apkg = slash > 0 ? act.substring(0, slash) : pkg;
        String acls = slash > 0 ? act.substring(slash + 1) : act;
        if (acls.startsWith(".")) {
            acls = apkg + acls;
        }
        sTargetPkg = apkg.trim();
        sTargetAct = acls.trim();
        sTargetWarmUri = (warm == null || warm.trim().isEmpty())
                ? "" : "content://" + warm.trim();
        log(Log.INFO, TAG, "target launcher = " + sTargetPkg + "/" + sTargetAct
                + (sTargetWarmUri.isEmpty() ? "" : " warm=" + sTargetWarmUri));
    }

    private boolean isHomeActivity(Activity act) {
        ComponentName cn = act.getComponentName();
        if (cn == null || !isLauncherPkg(cn.getPackageName())) {
            return false;
        }
        // 关键：荣耀的最近任务 RecentsActivity 也在 launcher 包里，不能只按类名判断。
        // 只认带 CATEGORY_HOME 的 MAIN intent（即真正的桌面 Activity）。
        Intent it = act.getIntent();
        return it != null
                && Intent.ACTION_MAIN.equals(it.getAction())
                && it.hasCategory(Intent.CATEGORY_HOME);
    }

    private boolean isOverviewNow(Activity act) {
        return isOverviewStateName(currentStateName(act));
    }

    private String currentStateName(Activity act) {
        try {
            Object sm = mStateManager;
            if (sm == null) {
                sm = findStateManager(act);
                mStateManager = sm;
            }
            if (sm == null) {
                return mLastStateName;
            }
            Method getState = sm.getClass().getMethod("getState");
            Object state = getState.invoke(sm);
            return state == null ? mLastStateName : state.getClass().getSimpleName();
        } catch (Throwable t) {
            return mLastStateName;
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

    private static boolean isOverviewStateName(String n) {
        return n != null && (n.contains("Overview") || n.contains("Recents"));
    }

    private static boolean isHomeStateName(String n) {
        return n != null && (n.contains("Normal") || n.contains("Home")
                || "NORMAL".equalsIgnoreCase(n));
    }

    private void setWindowAlpha(Activity act, float alpha) {
        try {
            Window w = act.getWindow();
            if (w == null) {
                return;
            }
            WindowManager.LayoutParams lp = w.getAttributes();
            if (lp.alpha != alpha) {
                lp.alpha = alpha;
                w.setAttributes(lp);
                if (DEBUG) {
                    log(Log.INFO, TAG, "window alpha -> " + alpha);
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "setWindowAlpha failed: " + t);
        }
    }

    private void bounceToTarget(Activity act) {
        refreshTargetIfStale(act);
        final String pkg = sTargetPkg;
        final String cls = sTargetAct;
        if (cls == null || cls.isEmpty()) {
            log(Log.WARN, TAG, "target launcher activity unknown: " + pkg);
            return;
        }
        try {
            // 关键：不要带 CATEGORY_HOME，否则系统会把第三方桌面塞进 home 根任务，
            // 变成第二个桌面任务叠在荣耀桌面之上，最近任务会被吃掉触摸。
            Intent target = new Intent(Intent.ACTION_MAIN);
            target.setComponent(new ComponentName(pkg, cls));
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            act.startActivity(target);
            consumeHomeGesture();
            log(Log.INFO, TAG, "bounced -> " + pkg + ", from "
                    + act.getComponentName().flattenToShortString()
                    + " state=" + mLastStateName);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "bounce failed", t);
        }
    }

    // ------------------------------------------------------------------
    // Quickstep 手势管线：HOME 手势令牌 + 探针
    // ------------------------------------------------------------------

    /** 探针开关：只记录 Quickstep 管线的方法调用，不改行为。排查时打开。 */
    private static final boolean QUICKSTEP_PROBE = false;
    private int mProbeLines = 0;

    private void hookQuickstep(ClassLoader cl) {
        Class<?> endTarget;
        try {
            endTarget = Class.forName(
                    "com.android.quickstep.GestureState$GestureEndTarget", false, cl);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "GestureEndTarget missing, no gesture gating: " + t);
            return;
        }
        // 精确信号：手势结算终点。只有 HOME 才是「上滑回桌面」。
        armGestureHook(cl, endTarget, new Class<?>[]{endTarget});
        armGestureHook(cl, endTarget, new Class<?>[]{endTarget, boolean.class});

        if (GESTURE_PREWARM) {
            armGesturePrewarmHook(cl);
        }

        // 底部边缘上滑（桌面在场时直接去 Nova）：被系统手势接管，
        // 只能从 Quickstep 手势管线里取事件。
        armEdgeSwipeHook(cl);

        if (!QUICKSTEP_PROBE) {
            return;
        }
        try {
            Class<?>[] et = new Class<?>[]{endTarget};

            probeHook(cl, "com.android.quickstep.GestureState",
                    "setEndTarget", et, "endTarget", false);
            probeHook(cl, "com.android.quickstep.GestureState",
                    "setEndTarget", new Class<?>[]{endTarget, boolean.class}, "endTarget2", false);
            probeHook(cl, "com.android.quickstep.inputconsumers.OtherActivityInputConsumer",
                    "finishSwipeUp", new Class<?>[]{MotionEvent.class, endTarget}, "finishSwipeUp", false);
            probeHook(cl, "com.android.quickstep.inputconsumers.OtherActivityInputConsumer",
                    "onMotionEvent", new Class<?>[]{MotionEvent.class}, "oac.motion", true);
            probeHook(cl, "com.android.quickstep.AbsSwipeUpHandler",
                    "onSettledOnEndTarget", new Class<?>[0], "ash.settledOnEndTarget", false);
            probeHook(cl, "com.android.quickstep.AbsSwipeUpHandler",
                    "onGestureEnded", new Class<?>[]{float.class, android.graphics.PointF.class},
                    "ash.onGestureEnded", false);
            probeHook(cl, "com.android.quickstep.AbsSwipeUpHandler",
                    "calculateEndTarget",
                    new Class<?>[]{android.graphics.PointF.class, float.class, boolean.class, boolean.class},
                    "ash.calcEndTarget", false);
            probeHook(cl, "com.android.quickstep.AbsSwipeUpHandler",
                    "maybeFinishSwipeToHome", new Class<?>[0], "ash.maybeFinishSwipeToHome", false);
            probeHook(cl, "com.android.quickstep.AbsSwipeUpHandler",
                    "processForEndHome", new Class<?>[0], "ash.processForEndHome", false);
            probeHook(cl, "com.hihonor.android.launcher.extension.quickstep.HnLauncherSwipeHandlerV2",
                    "onGestureEnded", new Class<?>[]{float.class}, "v2.onGestureEnded", false);
            probeHook(cl, "com.hihonor.android.launcher.extension.quickstep.HnLauncherSwipeHandlerV2",
                    "processForEndHome", new Class<?>[0], "v2.processForEndHome", false);
            probeHook(cl, "com.hihonor.android.launcher.quickstep.TouchInterationManager",
                    "processGestureInputEvent", new Class<?>[]{MotionEvent.class}, "tim.processGesture", true);
            log(Log.INFO, TAG, "quickstep probes installed");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "quickstep probe setup failed: " + t);
        }
    }

    /** 钩 GestureState.setEndTarget(...)：终点是 HOME 时发一张短期令牌 */
    private void armGestureHook(ClassLoader cl, Class<?> endTarget, Class<?>[] params) {
        try {
            Method m = Class.forName("com.android.quickstep.GestureState", false, cl)
                    .getDeclaredMethod("setEndTarget", params);
            hook(m)
                    .setId("magichome-home-gesture-" + params.length)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            Object et = chain.getArg(0);
                            String name = et == null ? "null" : String.valueOf(et);
                            boolean home = "HOME".equals(name);
                            if (home) {
                                sHomeGestureAt = SystemClock.uptimeMillis();
                            } else {
                                // 换了终点（RECENTS/LAST_TASK/NEW_TASK…）说明不是回桌面，撤销令牌
                                sHomeGestureAt = 0L;
                            }
                            if (DEBUG) {
                                log(Log.INFO, TAG, "gesture end target=" + name
                                        + (home ? " -> armed" : " -> disarmed"));
                            }
                            return chain.proceed();
                        }
                    });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "arm hook failed (" + params.length + "): " + t);
        }
    }

    /**
     * 上滑手势起手（从别的 App 上滑）时静默预热 Nova。
     * createOtherActivityInputConsumer 只在「从非桌面界面开始上滑」时创建，
     * 正是可能结算到 HOME / RECENTS 的那类手势。
     */
    private void armEdgeSwipeHook(ClassLoader cl) {
        try {
            Class<?> tim = Class.forName(
                    "com.hihonor.android.launcher.quickstep.TouchInterationManager", false, cl);
            Method m = tim.getDeclaredMethod("processGestureInputEvent", MotionEvent.class);
            hook(m)
                    .setId("magichome-edge-swipe")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            try {
                                Object a = chain.getArg(0);
                                if (a instanceof MotionEvent) {
                                    handleGesturePipelineEvent((MotionEvent) a);
                                }
                            } catch (Throwable t) {
                                log(Log.WARN, TAG, "edge swipe handler failed: " + t);
                            }
                            return chain.proceed();
                        }
                    });
            log(Log.INFO, TAG, "edge swipe hook installed");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "edge swipe hook failed: " + t);
        }
    }

    private void armGesturePrewarmHook(ClassLoader cl) {
        try {
            Class<?> gs = Class.forName("com.android.quickstep.GestureState", false, cl);
            Method m = Class.forName(
                            "com.hihonor.android.launcher.quickstep.TouchInterationManager", false, cl)
                    .getDeclaredMethod("createOtherActivityInputConsumer",
                            gs, MotionEvent.class);
            hook(m)
                    .setId("magichome-gesture-prewarm")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            try {
                                mHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        warmTargetSilently("gesture");
                                    }
                                });
                            } catch (Throwable t) {
                                log(Log.WARN, TAG, "gesture prewarm post failed: " + t);
                            }
                            return chain.proceed();
                        }
                    });
            log(Log.INFO, TAG, "gesture prewarm hook installed");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "gesture prewarm hook failed: " + t);
        }
    }

    private void probeHook(ClassLoader cl, String cn, String mn, Class<?>[] params,
                           String label, final boolean motionEdgesOnly) {
        try {
            Class<?> c = Class.forName(cn, false, cl);
            Method m = c.getDeclaredMethod(mn, params);
            hook(m)
                    .setId("magichome-probe-" + label)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            boolean want = !motionEdgesOnly || isMotionEdge(chain);
                            if (want) {
                                probeLine(label + "(" + describeArgs(chain) + ")");
                            }
                            Object out = chain.proceed();
                            if (want && out instanceof Boolean) {
                                probeLine(label + " -> " + out);
                            }
                            return out;
                        }
                    });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "probe hook failed " + label + ": " + t);
        }
    }

    private static boolean isMotionEdge(Chain chain) {
        try {
            Object a = chain.getArg(0);
            if (a instanceof MotionEvent) {
                int act = ((MotionEvent) a).getActionMasked();
                return act == MotionEvent.ACTION_DOWN
                        || act == MotionEvent.ACTION_UP
                        || act == MotionEvent.ACTION_CANCEL;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    private static String describeArgs(Chain chain) {
        StringBuilder sb = new StringBuilder();
        try {
            List<Object> as = chain.getArgs();
            for (int i = 0; i < as.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                Object a = as.get(i);
                if (a instanceof MotionEvent) {
                    MotionEvent e = (MotionEvent) a;
                    sb.append(MotionEvent.actionToString(e.getActionMasked()))
                            .append('@').append((int) e.getX()).append(',').append((int) e.getY());
                } else if (a instanceof android.graphics.PointF) {
                    android.graphics.PointF p = (android.graphics.PointF) a;
                    sb.append('(').append((int) p.x).append(',').append((int) p.y).append(')');
                } else {
                    sb.append(String.valueOf(a));
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private void probeLine(String s) {
        if (mProbeLines > 4000) {
            return;
        }
        mProbeLines++;
        log(Log.INFO, TAG, "probe " + s);
    }

    // ------------------------------------------------------------------
    // system_server 层（本机默认关闭：实测 home 不走 intent）
    // ------------------------------------------------------------------


    // ------------------------------------------------------------------
    // 探针：定位「home 任务置前」的系统入口（只记录，不改变行为）
    // ------------------------------------------------------------------

    private static final String[][] PROBE_TARGETS = {
            {"com.android.server.wm.ActivityTaskSupervisor", "startHomeActivity"},
            {"com.android.server.wm.ActivityTaskSupervisor", "startHomeOnDisplay"},
            {"com.android.server.wm.ActivityTaskSupervisor", "startHomeOnTaskDisplayArea"},
            {"com.android.server.wm.ActivityTaskSupervisor", "startHomeActivityLocked"},
            {"com.android.server.wm.ActivityTaskManagerService", "moveTaskToFront"},
            {"com.android.server.wm.ActivityTaskManagerService", "setFocusedTask"},
            {"com.android.server.wm.ActivityTaskManagerService", "startActivityFromRecents"},
            {"com.android.server.wm.RootWindowContainer", "moveRootTaskToFront"},
            {"com.android.server.wm.RootWindowContainer", "moveTaskToFront"},
            {"com.android.server.wm.Task", "moveToFront"},
            {"com.android.server.wm.Task", "moveToFrontAndResumeState"},
            {"com.android.server.wm.Task", "startActivityLocked"},
            {"com.android.server.wm.DisplayContent", "onHomeRequested"},
    };

    private static volatile boolean sProbesInstalled = false;

    private void installSystemProbes(ClassLoader cl) {
        if (sProbesInstalled || cl == null) {
            return;
        }
        sProbesInstalled = true;
        int total = 0;
        for (String[] t : PROBE_TARGETS) {
            String clsName = t[0];
            String method = t[1];
            Class<?> c;
            try {
                c = Class.forName(clsName, false, cl);
            } catch (Throwable e) {
                if (DEBUG) {
                    log(Log.INFO, TAG, "probe class not found: " + clsName);
                }
                continue;
            }
            int n = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(method)) {
                    continue;
                }
                final String tag = c.getSimpleName() + "#" + method;
                try {
                    hook(m)
                            .setId("probe-" + tag + "-" + m.getParameterCount())
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .intercept(new Hooker() {
                                @Override
                                public Object intercept(Chain chain) throws Throwable {
                                    try {
                                        log(Log.INFO, TAG, "PROBE " + tag
                                                + "(" + summarizeArgs(chain) + ")");
                                    } catch (Throwable ignored) {
                                    }
                                    return chain.proceed();
                                }
                            });
                    n++;
                } catch (Throwable e) {
                    log(Log.WARN, TAG, "probe hook failed " + tag + ": " + e);
                }
            }
            if (n > 0) {
                total += n;
                log(Log.INFO, TAG, "probe installed " + c.getSimpleName() + "#" + method + " x" + n);
            }
        }
        log(Log.INFO, TAG, "system probes installed: " + total);
    }

    private static String summarizeArgs(Chain chain) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Object a : chain.getArgs()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                if (a == null) {
                    sb.append("null");
                } else if (a instanceof Intent) {
                    Intent it = (Intent) a;
                    sb.append("I{").append(it.getAction()).append('/')
                            .append(it.getComponent()).append('}');
                } else if (a instanceof Integer || a instanceof Boolean || a instanceof Long) {
                    sb.append(a);
                } else if (a instanceof String) {
                    sb.append('"').append(a).append('"');
                } else if (a.getClass().getSimpleName().equals("Task")
                        || a.getClass().getSimpleName().equals("TaskFragment")) {
                    sb.append(a.getClass().getSimpleName()).append('{');
                    try {
                        Field f = a.getClass().getDeclaredField("mTaskId");
                        f.setAccessible(true);
                        sb.append("task=").append(f.getInt(a));
                    } catch (Throwable ignored) {
                        sb.append("task=?");
                    }
                    sb.append('}');
                } else {
                    sb.append(a.getClass().getSimpleName());
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private void hookSystemServer(ClassLoader cl) {
        if (sSysServerHooked || cl == null) {
            return;
        }
        sSysServerHooked = true;

        // 1) 系统解析 home Activity 时直接返回 Nova —— home 任务从创建起就是 Nova，
        //    转场目标天然是 Nova，荣耀桌面不会入画（零闪现）。
        try {
            Class<?> rwc = Class.forName("com.android.server.wm.RootWindowContainer", false, cl);
            int n = 0;
            for (Method m : rwc.getDeclaredMethods()) {
                if (!"resolveHomeActivity".equals(m.getName())) {
                    continue;
                }
                hook(m)
                        .setId("magichome-resolve-home")
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(new Hooker() {
                            @Override
                            public Object intercept(Chain chain) throws Throwable {
                                Object original = null;
                                try {
                                    original = chain.proceed();
                                } catch (Throwable t) {
                                    log(Log.WARN, TAG, "resolveHome original failed: " + t);
                                }
                                try {
                                    int userId = 0;
                                    for (Object a : chain.getArgs()) {
                                        if (a instanceof Integer && (Integer) a >= 0) {
                                            userId = (Integer) a;
                                            break;
                                        }
                                    }
                                    ActivityInfo nova = getNovaActivityInfo(userId);
                                    if (nova != null) {
                                        log(Log.INFO, TAG, "resolveHomeActivity -> Nova"
                                                + " (original=" + describe(original) + ")");
                                        return nova;
                                    }
                                    log(Log.WARN, TAG, "Nova ActivityInfo unavailable, keep original");
                                } catch (Throwable t) {
                                    log(Log.WARN, TAG, "resolveHome override failed: " + t);
                                }
                                return original;
                            }
                        });
                n++;
            }
            log(Log.INFO, TAG, "resolveHomeActivity hooks: " + n);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "RootWindowContainer not found: " + t);
        }

        // 2) 兜底：HOME intent 改写
        hookHomeIntents(cl);
    }

    private static String describe(Object o) {
        if (o == null) {
            return "null";
        }
        try {
            Field f = o.getClass().getField("packageName");
            Object pkg = f.get(o);
            Field f2 = o.getClass().getField("name");
            return pkg + "/" + f2.get(o);
        } catch (Throwable ignored) {
            return o.getClass().getSimpleName();
        }
    }

    private static volatile ActivityInfo sNovaActivityInfo;

    private ActivityInfo getNovaActivityInfo(int userId) {
        ActivityInfo cached = sNovaActivityInfo;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> appGlobals = Class.forName("android.app.AppGlobals");
            Object pm = appGlobals.getMethod("getPackageManager").invoke(null);
            if (pm == null) {
                return null;
            }
            ComponentName cn = new ComponentName(DEFAULT_PKG, DEFAULT_ACT);
            for (Method m : pm.getClass().getMethods()) {
                if (!"getActivityInfo".equals(m.getName())) {
                    continue;
                }
                Class<?>[] pt = m.getParameterTypes();
                Object info;
                try {
                    if (pt.length == 3 && pt[0] == ComponentName.class && pt[1] == long.class) {
                        info = m.invoke(pm, cn, 0L, userId);
                    } else if (pt.length == 3 && pt[0] == ComponentName.class && pt[1] == int.class) {
                        info = m.invoke(pm, cn, 0, userId);
                    } else {
                        continue;
                    }
                } catch (Throwable t) {
                    continue;
                }
                if (info instanceof ActivityInfo) {
                    ActivityInfo ai = (ActivityInfo) info;
                    // 校验：包名/类名/applicationInfo 都齐全才接管，避免开机没桌面
                    if (ai.packageName == null || ai.name == null
                            || ai.applicationInfo == null
                            || !DEFAULT_PKG.equals(ai.packageName)
                            || !DEFAULT_ACT.equals(ai.name)) {
                        log(Log.WARN, TAG, "Nova ActivityInfo incomplete, reject");
                        continue;
                    }
                    sNovaActivityInfo = ai;
                    log(Log.INFO, TAG, "Nova ActivityInfo: " + ((ActivityInfo) info).packageName
                            + "/" + ((ActivityInfo) info).name);
                    return sNovaActivityInfo;
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "getNovaActivityInfo failed: " + t);
        }
        return null;
    }

    private void hookHomeIntents(ClassLoader cl) {
        Class<?> atms;
        try {
            atms = Class.forName("com.android.server.wm.ActivityTaskManagerService", false, cl);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "ATMS not found: " + t);
            return;
        }
        String[] names = {"startActivityAsUser", "startActivityAsUserWithFeature", "startActivity"};
        int installed = 0;
        for (String name : names) {
            for (Method m : atms.getDeclaredMethods()) {
                if (!m.getName().equals(name)) {
                    continue;
                }
                try {
                    hook(m)
                            .setId("magichome-sys-" + name)
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .intercept(new Hooker() {
                                @Override
                                public Object intercept(Chain chain) throws Throwable {
                                    return rewriteHomeIntent(chain);
                                }
                            });
                    installed++;
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "hook " + name + " failed: " + t);
                }
            }
        }
        log(Log.INFO, TAG, "HOME intent hooks installed: " + installed);
    }

    private Object rewriteHomeIntent(Chain chain) throws Throwable {
        List<Object> args = chain.getArgs();
        boolean changed = false;
        for (Object arg : args) {
            if (!(arg instanceof Intent)) {
                continue;
            }
            Intent it = (Intent) arg;
            if (!isHomeIntent(it)) {
                continue;
            }
            ComponentName cur = it.getComponent();
            if (cur != null && DEFAULT_PKG.equals(cur.getPackageName())
                    && DEFAULT_ACT.equals(cur.getClassName())) {
                continue;
            }
            it.setComponent(new ComponentName(DEFAULT_PKG, DEFAULT_ACT));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            changed = true;
            log(Log.INFO, TAG, "rewrite HOME intent in "
                    + chain.getExecutable().getName() + " -> Nova");
        }
        return changed ? chain.proceed(args.toArray()) : chain.proceed();
    }

    private static boolean isHomeIntent(Intent it) {
        return it != null
                && Intent.ACTION_MAIN.equals(it.getAction())
                && it.hasCategory(Intent.CATEGORY_HOME);
    }
}
