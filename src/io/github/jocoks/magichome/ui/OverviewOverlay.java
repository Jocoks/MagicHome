package io.github.jocoks.magichome.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import io.github.jocoks.magichome.feature.Logs;
import io.github.jocoks.magichome.feature.MemoryStats;

/**
 * 交互层：最近任务底部那行运存文字。
 *
 * 只有文字、没有背景框；直接挂到桌面 Activity 的 DecorView 上（不需要悬浮窗权限）。
 * 数据与文案来自功能层 {@link MemoryStats}，这里只管视图与刷新节奏。
 */
public final class OverviewOverlay {

    private static final long REFRESH_MS = 1000L;

    private static WeakReference<Activity> sActivity = new WeakReference<>(null);
    private static WeakReference<TextView> sView = new WeakReference<>(null);
    private static boolean sVisible;

    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private static final Runnable REFRESH = new Runnable() {
        @Override
        public void run() {
            if (!sVisible) {
                return;
            }
            try {
                bind();
            } catch (Throwable t) {
                Logs.w("stats refresh failed: " + t);
            }
            sHandler.postDelayed(this, REFRESH_MS);
        }
    };

    private OverviewOverlay() {
    }

    public static void show(final Activity act) {
        if (act == null) {
            return;
        }
        sActivity = new WeakReference<>(act);
        sHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    final TextView tv = ensureView(act);
                    if (tv == null) {
                        return;
                    }
                    tv.setVisibility(View.VISIBLE);
                    sVisible = true;
                    bind();
                    sHandler.removeCallbacks(REFRESH);
                    sHandler.postDelayed(REFRESH, REFRESH_MS);
                } catch (Throwable t) {
                    Logs.w("stats show failed: " + t);
                }
            }
        });
    }

    public static void hide() {
        if (!sVisible) {
            return;
        }
        sVisible = false;
        sHandler.removeCallbacks(REFRESH);
        sHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    TextView tv = sView.get();
                    if (tv != null) {
                        tv.setVisibility(View.GONE);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static TextView ensureView(Activity act) {
        TextView cached = sView.get();
        if (cached != null && cached.getParent() != null) {
            return cached;
        }
        ViewGroup root = null;
        try {
            View decor = act.getWindow().getDecorView();
            if (decor instanceof ViewGroup) {
                root = (ViewGroup) decor;
            }
        } catch (Throwable t) {
            Logs.w("decor view unavailable: " + t);
        }
        if (root == null) {
            return null;
        }
        TextView tv = new TextView(act);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setSingleLine(true);
        tv.setClickable(false);
        tv.setFocusable(false);
        tv.setShadowLayer(dp(act, 4), 0f, dp(act, 1.5f), 0x99000000);
        tv.setText(MemoryStats.text(act));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = Math.max(0, dp(act, 56) - 84);   // 清理键下方、贴着手势条上方
        try {
            root.addView(tv, lp);
        } catch (Throwable t) {
            Logs.w("add stats view failed: " + t);
            try {
                root.addView(tv);
            } catch (Throwable t2) {
                return null;
            }
        }
        sView = new WeakReference<>(tv);
        Logs.i("overview stats view attached");
        return tv;
    }

    private static void bind() {
        TextView tv = sView.get();
        if (tv == null) {
            return;
        }
        tv.setText(MemoryStats.text(tv.getContext()));
    }

    private static int dp(Context ctx, float value) {
        if (ctx == null) {
            ctx = sView.get() != null ? sView.get().getContext() : null;
        }
        if (ctx == null) {
            return (int) (value * 3);
        }
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
