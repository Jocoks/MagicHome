package io.github.jocoks.magichome.feature;

/**
 * 功能层：跳转决策。
 *
 * 三个入口（手势回桌面 / 最近任务退出 / 按返回键退出）的「跳不跳、延迟多久、为什么」
 * 全在这里算，交互层只负责按结论调度。这样规则可单独检查，也不会散落在钩子里。
 */
public final class BouncePolicy {

    /** 顶层 resumed 后的缓冲 */
    public static final long TOP_RESUMED_DELAY_MS = 16L;
    /** 状态机兜底延迟（万一没收到 onTopResumedActivityChanged） */
    public static final long FALLBACK_DELAY_MS = 400L;

    /**
     * 最近任务里「没点卡片、点空白处或再上滑一次」也会回桌面：
     * 没有 HOME 手势令牌，也不会重发 onTopResumedActivityChanged，
     * 只能用 goToState 的「Overview -> Normal」来认。
     * 实测 412ms 里 400ms 是我们自己等的，改成一帧多一点就发跳转，没退出 overview 再补试。
     */
    public static final long RECENTS_EXIT_DELAY_MS = 50L;
    public static final int RECENTS_EXIT_RETRIES = 3;

    /**
     * 刚点过任务卡片时不要立刻接管：那多半是「打开这个应用」。
     *
     * <p>但不能一律放弃——在最近任务里点卡片顶栏的锁定/分屏按钮、或者点空白处，
     * 系统同样会记一次「点按」，然后桌面退回 NormalState。所以先等一小会儿再跳，
     * 跳之前会复查桌面是否还有窗口焦点：真的打开了应用，桌面早就没焦点了，自然放过。</p>
     */
    public static final long CARD_TAP_GUARD_MS = 400L;
    /** 刚点过卡片时，延后这么久再决定跳不跳（期间若打开了应用会被取消） */
    public static final long CARD_TAP_DEFER_MS = 500L;

    /** 桌面还没拿到窗口焦点时（比如刚从锁屏解锁），每隔这么久重试一次 */
    public static final long FOCUS_RETRY_MS = 150L;
    /** 等窗口焦点最多重试几次 */
    public static final int FOCUS_RETRIES = 6;

    /**
     * 解锁后这段时间内忽略「刚点过卡片」这个守卫。
     *
     * <p>实测：在最近任务界面锁屏、再解锁时，拉起锁屏的那次上滑会被系统漏给桌面，
     * 落在一张任务卡片上，像一次「点按」；于是 overview -> Normal 的退出被当成
     * 「用户点了卡片打开应用」而跳过，最后停在系统桌面。锁屏解锁不是操作卡片，
     * 这段时间内的点按记录一律不作数。</p>
     */
    public static final long UNLOCK_TAP_GRACE_MS = 1500L;

    /**
     * 按返回键退出应用：system_server 直接把 home 任务置前，既没手势令牌、
     * 桌面状态也没变化，只能按「桌面从后台回到前台」接管。
     */
    public static final long TOP_RESUMED_FALLBACK_DELAY_MS = 40L;
    /** 桌面至少离开前台这么久，才算「从后台回来」（排除转场抖动） */
    public static final long TOP_RESUMED_MIN_BG_MS = 200L;
    /** 刚跳过一次就不要再跳，避免来回抖 */
    public static final long BOUNCE_DEDUPE_MS = 1200L;

    /** 手势令牌有效期：Quickstep 手势结算到 HOME 之后这段时间才算是真·上滑回桌面 */
    public static final long HOME_GESTURE_TTL_MS = 1200L;

    /**
     * 刚切到最近任务没多久也算「已经在最近任务」。
     * goToState 钩子比 StateManager.getState() 早一拍，上滑偏高时状态还没同步过来，
     * 只查当前状态会漏判，于是把桌面刚跳进最近任务的那个瞬间也记住。
     */
    public static final long OVERVIEW_MEMORY_MS = 900L;

    /** 开机静默预热：避开开机/解锁高峰 */
    public static final long BOOT_WARM_DELAY_MS = 6000L;
    /** 只有开机后这个时间窗内才做一次开机预热 */
    public static final long BOOT_WARM_WINDOW_MS = 600_000L;
    /** 两次静默预热的最小间隔 */
    public static final long WARM_MIN_INTERVAL_MS = 1500L;

    /** 开关状态：只有真的要用时才去读（读设置是跨进程查询，别白跑） */
    public interface Switch {
        boolean isOn();
    }

    /** 一次决策结果 */
    public static final class Decision {

        public final boolean bounce;
        public final long delayMs;
        public final String reason;
        public final int retries;
        /** 等窗口焦点（比如刚从锁屏解锁）时的重试次数 */
        public final int focusRetries;
        /** 跳过时不打日志（只在 DEBUG 下打） */
        public final boolean verboseOnly;

        private Decision(boolean bounce, long delayMs, String reason, int retries,
                         int focusRetries, boolean verboseOnly) {
            this.bounce = bounce;
            this.delayMs = delayMs;
            this.reason = reason;
            this.retries = retries;
            this.focusRetries = focusRetries;
            this.verboseOnly = verboseOnly;
        }

        public static Decision bounce(long delayMs, String reason, int retries) {
            return new Decision(true, delayMs, reason, retries, FOCUS_RETRIES, false);
        }

        public static Decision skip(String reason) {
            return new Decision(false, 0L, reason, 0, 0, false);
        }

        public static Decision skipVerbose(String reason) {
            return new Decision(false, 0L, reason, 0, 0, true);
        }
    }

    private BouncePolicy() {
    }

    /** 手势令牌是否还新鲜 */
    public static boolean isHomeGestureFresh(long sinceGestureMs) {
        return sinceGestureMs >= 0L && sinceGestureMs <= HOME_GESTURE_TTL_MS;
    }

    /**
     * 桌面成为顶层 resumed 时的决策。
     *
     * @param gestureFresh   最近是否有 Quickstep HOME 手势令牌
     * @param sinceLostMs    桌面离开顶层到现在多久（&lt;0 表示没记录）
     * @param sinceBouncedMs 上一次跳转到现在多久（&lt;0 表示没有）
     * @param sinceCardTapMs 上一次点任务卡片到现在多久（&lt;0 表示没有）
     * @param multiWindow    是否处于多窗口
     * @param interceptOn    最近任务功能开关
     */
    public static Decision onTopResumed(boolean gestureFresh, long sinceLostMs,
                                        long sinceBouncedMs, long sinceCardTapMs,
                                        boolean multiWindow, Switch intercept) {
        if (gestureFresh) {
            return Decision.bounce(TOP_RESUMED_DELAY_MS, "topResumed+homeGesture", 0);
        }
        boolean fromBackground = sinceLostMs >= TOP_RESUMED_MIN_BG_MS;
        if (!fromBackground) {
            return Decision.skipVerbose(
                    "topResumed without home-gesture token, skip (not from background)");
        }
        if (sinceBouncedMs >= 0L && sinceBouncedMs < BOUNCE_DEDUPE_MS) {
            return Decision.skip("topResumed from background, skip: bounced just now");
        }
        if (sinceCardTapMs >= 0L && sinceCardTapMs < CARD_TAP_GUARD_MS) {
            return Decision.bounce(CARD_TAP_DEFER_MS,
                    "topResumed-from-bg, card tapped just now: defer and check", 0);
        }
        if (multiWindow) {
            return Decision.skip("topResumed from background, skip: multi-window");
        }
        if (!intercept.isOn()) {
            return Decision.skip("topResumed from background, skip: switch off");
        }
        return Decision.bounce(TOP_RESUMED_FALLBACK_DELAY_MS, "topResumed-from-bg", 0);
    }

    /**
     * 桌面状态机切到 home 状态时的决策。
     *
     * @param fromOverview   上一次状态是不是最近任务
     * @param hasPending     是否已有待执行的跳转
     * @param gestureFresh   是否有 HOME 手势令牌
     * @param interceptOn    最近任务功能开关
     * @param sinceCardTapMs 上一次点任务卡片到现在多久（&lt;0 表示没有）
     */
    public static Decision onHomeState(boolean fromOverview, boolean hasPending,
                                       boolean gestureFresh, Switch intercept,
                                       long sinceCardTapMs, String stateName) {
        if (fromOverview && intercept.isOn()) {
            if (sinceCardTapMs >= 0L && sinceCardTapMs < CARD_TAP_GUARD_MS) {
                return Decision.bounce(CARD_TAP_DEFER_MS,
                        "recents->home, card tapped just now: defer and check", 0);
            }
            return Decision.bounce(RECENTS_EXIT_DELAY_MS, "recents->home",
                    RECENTS_EXIT_RETRIES);
        }
        if (!hasPending && gestureFresh) {
            return Decision.bounce(FALLBACK_DELAY_MS, "state=" + stateName + "+homeGesture", 0);
        }
        return null;
    }
}
