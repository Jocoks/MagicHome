package io.github.jocoks.magichome.ui;

import android.app.Activity;
import android.os.SystemClock;
import android.view.MotionEvent;

import io.github.jocoks.magichome.feature.GestureRules;
import io.github.jocoks.magichome.feature.Logs;

/**
 * 交互层：上滑手势跟踪。
 *
 * 两条输入来源共用同一套判定：
 *  - 桌面 Activity 的 dispatchTouchEvent（普通触摸）；
 *  - Quickstep 手势管线（从屏幕底部边缘上滑会被 SystemUI 接管，只能在这里看全）。
 *
 * 「算什么手势」在功能层 {@link GestureRules}，「算完怎么跳」在 {@link LauncherBridge}。
 */
public final class SwipeTracker {

    private boolean mTracking = false;
    private Activity mAct;
    private float mDownX = 0f;
    private float mDownY = 0f;
    private long mDownAt = 0L;
    /** 本次上滑起手时是否停在桌面工作区（不是抽屉/最近任务） */
    private boolean mStartOnHome = false;
    private volatile long mLastJumpAt = 0L;

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /** 桌面 Activity.dispatchTouchEvent */
    public void onActivityTouch(Activity act, MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (LauncherBridge.hasPendingBounce()) {
                Logs.i("user touched, cancel pending bounce");
                LauncherBridge.cancelPendingBounce("user touch");
            }
            mTracking = false;
            if (act == null || !LauncherBridge.isHomeActivity(act)
                    || !isInBottomEdge(act, ev.getY())) {
                return;
            }
            start(act, ev.getX(), ev.getY());
        } else if (action == MotionEvent.ACTION_UP) {
            if (mTracking) {
                mTracking = false;
                evaluate(act, ev.getX(), ev.getY(), "activity");
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            mTracking = false;
        }
    }

    /** Quickstep 手势管线（TouchInterationManager.processGestureInputEvent） */
    public void onGesturePipeline(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mTracking = false;
            Activity act = LauncherBridge.currentActivity();
            if (act == null || act.isFinishing() || act.isDestroyed()
                    || !isInBottomEdge(act, ev.getY())) {
                return;
            }
            start(act, ev.getX(), ev.getY());
            Logs.d("edge gesture start y=" + (int) ev.getY() + " home=" + mStartOnHome);
        } else if (action == MotionEvent.ACTION_UP) {
            if (mTracking) {
                mTracking = false;
                evaluate(LauncherBridge.currentActivity(), ev.getX(), ev.getY(), "gesture");
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            mTracking = false;
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void start(Activity act, float x, float y) {
        mTracking = true;
        mAct = act;
        mDownX = x;
        mDownY = y;
        mDownAt = SystemClock.uptimeMillis();
        mStartOnHome = LauncherBridge.isSittingOnHomeWorkspace(act);
        // 上滑一起手就静默预热目标桌面，收尾跳转时资源已就绪
        LauncherBridge.warmTargetSilently("home-swipe");
    }

    private void evaluate(Activity act, float upX, float upY, String src) {
        // 以「按下那一刻」的桌面 Activity 为准
        Activity target = mAct != null ? mAct : act;
        if (target == null || target.isFinishing() || target.isDestroyed()) {
            return;
        }
        float dy = upY - mDownY;
        float dx = upX - mDownX;
        long dt = SystemClock.uptimeMillis() - mDownAt;
        GestureRules.SwipeResult result = GestureRules.classifySwipeUp(dx, dy, dt);
        if (result == GestureRules.SwipeResult.TOO_SLOW) {
            Logs.d("home swipe ignored: slow (" + dt + "ms, dy=" + (int) dy
                    + ", src=" + src + ")");
            return;
        }
        if (result == GestureRules.SwipeResult.DIAGONAL) {
            Logs.d("home swipe ignored: diagonal (dx=" + (int) dx + ", src=" + src + ")");
            return;
        }
        if (result != GestureRules.SwipeResult.PASS) {
            return; // 位移不够
        }
        if (!mStartOnHome) {
            return; // 起手时不在桌面工作区（从 App 上滑走的是手势令牌那条路）
        }
        // 上滑偏高 = 其实是在「进最近任务」，不是「回桌面」：这时别把用户拽去目标桌面
        if (LauncherBridge.isOverviewNow(target) || LauncherBridge.wasOverviewRecently()) {
            Logs.i("home swipe ignored: already in overview (dy=" + (int) dy
                    + ", dt=" + dt + "ms, src=" + src + ")");
            return;
        }
        if (!target.hasWindowFocus()) {
            return;
        }
        if (LauncherBridge.isAppsViewOpen(target)) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - mLastJumpAt < GestureRules.SWIPE_COOLDOWN_MS) {
            return;
        }
        mLastJumpAt = now;
        Logs.i("home swipe-up -> " + io.github.jocoks.magichome.feature.TargetLauncher.pkg()
                + " (src=" + src + ", dy=" + (int) dy + ", dx=" + (int) dx + ", dt=" + dt + "ms)");
        LauncherBridge.bounceToTarget(target);
    }

    private static boolean isInBottomEdge(Activity act, float y) {
        try {
            int h = act.getResources().getDisplayMetrics().heightPixels;
            return GestureRules.isBottomEdge(y, h);
        } catch (Throwable t) {
            return false;
        }
    }
}
