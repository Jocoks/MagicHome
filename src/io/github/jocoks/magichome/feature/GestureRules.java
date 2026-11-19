package io.github.jocoks.magichome.feature;

/**
 * 功能层：手势判定规则。
 *
 * 只做「这个手势算不算」的纯计算，不碰 View / 事件分发。
 * 阈值都写在 {@link BouncePolicy} 之外，方便单独调参。
 */
public final class GestureRules {

    /** 上滑位移下限（px）；本机 density=600，220px ≈ 84dp */
    public static final float SWIPE_MIN_PX = 220f;
    /** 上滑耗时上限：超过就不算「快速」 */
    public static final long SWIPE_MAX_MS = 400L;
    /** 横向漂移上限（相对纵向位移的比例），排除斜滑/翻页 */
    public static final float SWIPE_MAX_DX_RATIO = 0.6f;
    /** 两次桌面快速上滑之间的冷却，防止连跳 */
    public static final long SWIPE_COOLDOWN_MS = 1200L;
    /** 底部手势区：起手点 y >= 屏高 - 该值 */
    public static final float EDGE_BOTTOM_PX = 240f;

    /** 最近任务卡片长按阈值（比系统 400ms 稍慢，降低误触） */
    public static final long LONG_PRESS_MS = 460L;
    /** 点按判定：按下到抬起不超过这个时长，且位移没超过 touchSlop */
    public static final long TAP_MAX_MS = 250L;

    /** 上滑判定结果 */
    public enum SwipeResult {
        /** 是个合格的快速上滑 */
        PASS,
        /** 位移不够（普通滑动 / 慢拖 / 左右翻页） */
        TOO_SHORT,
        /** 太慢，不算「快速」 */
        TOO_SLOW,
        /** 斜滑 */
        DIAGONAL
    }

    private GestureRules() {
    }

    /** 起手点是否落在屏幕底部手势区 */
    public static boolean isBottomEdge(float y, int screenHeight) {
        return screenHeight > 0 && y >= screenHeight - EDGE_BOTTOM_PX;
    }

    /**
     * 判定一次「从下往上」的手势。
     *
     * @param dx 横向位移（upX - downX）
     * @param dy 纵向位移（upY - downY，向上为负）
     * @param dt 耗时 ms
     */
    public static SwipeResult classifySwipeUp(float dx, float dy, long dt) {
        if (dy > -SWIPE_MIN_PX) {
            return SwipeResult.TOO_SHORT;
        }
        if (dt > SWIPE_MAX_MS) {
            return SwipeResult.TOO_SLOW;
        }
        if (Math.abs(dx) > Math.abs(dy) * SWIPE_MAX_DX_RATIO) {
            return SwipeResult.DIAGONAL;
        }
        return SwipeResult.PASS;
    }

    /** 一次按下-抬起是否算「点按」（滑动删除卡片不算） */
    public static boolean isTap(boolean moved, long downAt, long upAt) {
        return !moved && downAt > 0L && upAt >= downAt && upAt - downAt <= TAP_MAX_MS;
    }
}
