package io.github.jocoks.magichome;

import android.app.Activity;
import android.view.MotionEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.jocoks.magichome.feature.Constants;
import io.github.jocoks.magichome.feature.Logs;
import io.github.jocoks.magichome.ui.LauncherBridge;
import io.github.jocoks.magichome.ui.RecentsTouchHandler;
import io.github.jocoks.magichome.ui.SwipeTracker;

/**
 * MagicHome —— 让「导航栏上滑回桌面」落到 Nova Launcher。
 *
 * <p>分层：
 * <ul>
 *   <li>本类 = 交互层的「入口」：只负责装钩子，把系统回调转给
 *       {@link LauncherBridge}、{@link SwipeTracker}、{@link RecentsTouchHandler}；</li>
 *   <li>ui 包 = 交互层：和 Activity / View / 触摸 / 状态机打交道的部分；</li>
 *   <li>feature 包 = 功能层：跳转规则、手势规则、目标桌面、设置契约、运存文案。</li>
 * </ul>
 *
 * 荣耀 MagicOS 不允许第三方桌面做默认桌面，上滑走「桌面任务置前」，
 * 只能在荣耀桌面进程里重定向。
 */
public class HookEntry extends XposedModule {

    private static final String TAG = Constants.TAG;

    private static volatile boolean sLauncherHooked = false;

    /** 上滑跟踪（实例状态：一次手势的起点） */
    private final SwipeTracker mSwipeTracker = new SwipeTracker();

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        // 两层代码都通过 feature.Logs 打日志；这里把出口接到 LSPosed 模块日志
        Logs.install(new Logs.Sink() {
            @Override
            public void log(int priority, String tag, String msg, Throwable tr) {
                if (tr != null) {
                    HookEntry.this.log(priority, tag, msg, tr);
                } else {
                    HookEntry.this.log(priority, tag, msg);
                }
            }
        });
        log(android.util.Log.INFO, TAG, "module loaded, systemServer=" + param.isSystemServer()
                + " process=" + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        log(android.util.Log.INFO, TAG, "package loaded: " + pkg);
        try {
            if (LauncherBridge.isLauncherPkg(pkg)) {
                hookLauncherProcess(param.getDefaultClassLoader());
            }
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "onPackageLoaded(" + pkg + ") failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 荣耀桌面进程
    // ------------------------------------------------------------------

    private void hookLauncherProcess(ClassLoader cl) {
        if (sLauncherHooked) {
            return;
        }
        sLauncherHooked = true;

        hookTopResumed();
        hookResume();
        hookWindowFocus();
        hookTouchDispatch();
        hookLauncherStateMachine(cl);
        hookQuickstep(cl);
        hookRecentsLongPress(cl);
    }

    /** 桌面是否成为顶层 resumed —— 回桌面的主要触发点 */
    private void hookTopResumed() {
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
                                Object self = chain.getThisObject();
                                if (self instanceof Activity) {
                                    LauncherBridge.onTopResumed((Activity) self,
                                            Boolean.TRUE.equals(chain.getArg(0)));
                                }
                            } catch (Throwable t) {
                                log(android.util.Log.ERROR, TAG, "topResumed handler failed", t);
                            }
                            return out;
                        }
                    });
            log(android.util.Log.INFO, TAG, "hooked Activity.onTopResumedActivityChanged");
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "topResumed hook failed: " + t);
        }
    }

    /** 桌面 onResume：记下当前桌面实例，并安排开机静默预热 */
    private void hookResume() {
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
                                    if (LauncherBridge.isHomeActivity(act)) {
                                        LauncherBridge.onLauncherResumed(act);
                                    }
                                }
                            } catch (Throwable t) {
                                log(android.util.Log.WARN, TAG, "resume handler failed: " + t);
                            }
                            return out;
                        }
                    });
            log(android.util.Log.INFO, TAG, "hooked Activity.onResume");
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "resume hook failed: " + t);
        }
    }

    /** 桌面失焦（比如正在打开别的 App）立刻取消待跳转 */
    private void hookWindowFocus() {
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
                                if (self instanceof Activity) {
                                    LauncherBridge.onWindowFocusChanged((Activity) self,
                                            Boolean.TRUE.equals(chain.getArg(0)));
                                }
                            } catch (Throwable t) {
                                log(android.util.Log.WARN, TAG, "focus handler failed: " + t);
                            }
                            return out;
                        }
                    });
            log(android.util.Log.INFO, TAG, "hooked Activity.onWindowFocusChanged");
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "focus hook failed: " + t);
        }
    }

    /** 桌面触摸：普通上滑（非系统手势区起手）走这里 */
    private void hookTouchDispatch() {
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
                                Object self = chain.getThisObject();
                                if (arg instanceof MotionEvent && self instanceof Activity) {
                                    mSwipeTracker.onActivityTouch((Activity) self, (MotionEvent) arg);
                                }
                            } catch (Throwable t) {
                                log(android.util.Log.WARN, TAG, "touch handler failed: " + t);
                            }
                            return chain.proceed();
                        }
                    });
            log(android.util.Log.INFO, TAG, "hooked Activity.dispatchTouchEvent");
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "touch hook failed: " + t);
        }
    }

    /** 状态机：goToState 之后读新状态（最近任务退出、手势兜底都靠它） */
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
                                        LauncherBridge.onStateChanged(chain.getArgs());
                                    } catch (Throwable t) {
                                        log(android.util.Log.ERROR, TAG, "state handler failed", t);
                                    }
                                    return out;
                                }
                            });
                    n++;
                } catch (Throwable t) {
                    log(android.util.Log.WARN, TAG, "hook goToState failed: " + t);
                }
            }
            log(android.util.Log.INFO, TAG, "state machine hooks installed: " + n);
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "StateManager not hookable: " + t);
        }
    }

    /** Quickstep：HOME 手势令牌 + 手势管线里的底部上滑事件 + 起手预热 */
    private void hookQuickstep(ClassLoader cl) {
        Class<?> endTarget;
        try {
            endTarget = Class.forName(
                    "com.android.quickstep.GestureState$GestureEndTarget", false, cl);
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "GestureEndTarget missing, no gesture gating: " + t);
            return;
        }
        armGestureHook(cl, endTarget, new Class<?>[]{endTarget});
        armGestureHook(cl, endTarget, new Class<?>[]{endTarget, boolean.class});
        armEdgeSwipeHook(cl);
        armGesturePrewarmHook(cl, endTarget);
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
                                LauncherBridge.armHomeGesture();
                            } else {
                                LauncherBridge.disarmHomeGesture();
                            }
                            Logs.d("gesture end target=" + name
                                    + (home ? " -> armed" : " -> disarmed"));
                            return chain.proceed();
                        }
                    });
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "arm hook failed (" + params.length + "): " + t);
        }
    }

    /**
     * 从屏幕底部边缘上滑会被系统手势接管，桌面只收到 DOWN 就 CANCEL，
     * 完整手势只能在这里看。
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
                                    mSwipeTracker.onGesturePipeline((MotionEvent) a);
                                }
                            } catch (Throwable t) {
                                log(android.util.Log.WARN, TAG, "edge swipe handler failed: " + t);
                            }
                            return chain.proceed();
                        }
                    });
            log(android.util.Log.INFO, TAG, "edge swipe hook installed");
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "edge swipe hook failed: " + t);
        }
    }

    /**
     * 从别的 App 上滑（createOtherActivityInputConsumer）时静默预热目标桌面，
     * 正是可能结算到 HOME / RECENTS 的那类手势。
     */
    private void armGesturePrewarmHook(ClassLoader cl, Class<?> gestureState) {
        try {
            Method m = Class.forName(
                            "com.hihonor.android.launcher.quickstep.TouchInterationManager", false, cl)
                    .getDeclaredMethod("createOtherActivityInputConsumer",
                            gestureState, MotionEvent.class);
            hook(m)
                    .setId("magichome-gesture-prewarm")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            try {
                                LauncherBridge.postWarm("gesture");
                            } catch (Throwable t) {
                                log(android.util.Log.WARN, TAG, "gesture prewarm failed: " + t);
                            }
                            return chain.proceed();
                        }
                    });
            log(android.util.Log.INFO, TAG, "gesture prewarm hook installed");
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "gesture prewarm hook failed: " + t);
        }
    }

    /** 最近任务卡片长按 → 应用信息页 */
    private void hookRecentsLongPress(ClassLoader cl) {
        String[] classes = {
                "com.android.quickstep.views.TaskView",
                "com.android.quickstep.views.BaseTaskView",
        };
        for (String cn : classes) {
            try {
                Class<?> c = Class.forName(cn, false, cl);
                Method m;
                try {
                    m = c.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
                } catch (NoSuchMethodException e) {
                    continue;
                }
                hook(m)
                        .setId("magichome-longpress-" + c.getSimpleName())
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(new Hooker() {
                            @Override
                            public Object intercept(Chain chain) throws Throwable {
                                try {
                                    Object self = chain.getThisObject();
                                    Object a = chain.getArg(0);
                                    if (self instanceof android.view.View
                                            && a instanceof MotionEvent) {
                                        Object task = fieldValueOf(self, "mTask");
                                        if (RecentsTouchHandler.handleTouch(
                                                (android.view.View) self, (MotionEvent) a, task)) {
                                            return Boolean.TRUE;
                                        }
                                    }
                                } catch (Throwable t) {
                                    log(android.util.Log.WARN, TAG, "longpress handler failed: " + t);
                                }
                                return chain.proceed();
                            }
                        });
                log(android.util.Log.INFO, TAG, "recents long-press hook installed on " + cn);
                return;
            } catch (Throwable t) {
                log(android.util.Log.WARN, TAG,
                        "recents long-press hook failed on " + cn + ": " + t);
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
}
