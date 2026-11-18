package io.github.jocoks.magichome;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * 在荣耀桌面的「最近任务」界面底部显示一行运存信息：
 * 总运存 / 空闲运存 / 已用百分比。
 *
 * <p>实现方式：在桌面 Activity 的 DecorView（FrameLayout）里塞一个 TextView，
 * 只在 Overview 状态下显示；离开最近任务就移除。纯代码构建视图，不依赖任何资源，
 * 也不申请悬浮窗权限（视图属于桌面自己的窗口层级）。
 */
final class OverviewStats {

    private static final String TAG = "MagicHome";
    private static final long REFRESH_MS = 2000L;

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
                Log.w(TAG, "stats refresh failed: " + t);
            }
            sHandler.postDelayed(this, REFRESH_MS);
        }
    };

    private OverviewStats() {
    }

    static void show(final Activity act) {
        if (act == null) {
            return;
        }
        sActivity = new WeakReference<>(act);
        sHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    TextView tv = ensureView(act);
                    if (tv == null) {
                        return;
                    }
                    tv.setVisibility(View.VISIBLE);
                    sVisible = true;
                    bind();
                    sHandler.removeCallbacks(REFRESH);
                    sHandler.postDelayed(REFRESH, REFRESH_MS);
                } catch (Throwable t) {
                    Log.w(TAG, "stats show failed: " + t);
                }
            }
        });
    }

    static void hide() {
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
            Log.w(TAG, "decor view unavailable: " + t);
        }
        if (root == null) {
            return null;
        }
        Context ctx = act;
        TextView tv = new TextView(ctx);
        tv.setTextColor(Color.parseColor("#FF2B2B33"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setSingleLine(true);
        int padH = dp(ctx, 12);
        int padV = dp(ctx, 6);
        tv.setPadding(padH, padV, padH, padV);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#E6FFFFFF"));
        bg.setCornerRadius(dp(ctx, 999));
        tv.setBackground(bg);
        tv.setElevation(dp(ctx, 2));
        tv.setText(statsText(ctx));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(ctx, 84);
        try {
            root.addView(tv, lp);
        } catch (Throwable t) {
            Log.w(TAG, "add stats view failed: " + t);
            try {
                root.addView(tv);
            } catch (Throwable t2) {
                return null;
            }
        }
        sView = new WeakReference<>(tv);
        Log.i(TAG, "overview stats view attached");
        return tv;
    }

    private static void bind() {
        TextView tv = sView.get();
        if (tv == null) {
            return;
        }
        tv.setText(statsText(tv.getContext()));
    }

    private static String statsText(Context ctx) {
        long total = 0L;
        long avail = 0L;
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                total = mi.totalMem;
                avail = mi.availMem;
            }
        } catch (Throwable t) {
            Log.w(TAG, "read memory info failed: " + t);
        }
        if (total <= 0L) {
            return "运存信息不可用";
        }
        if (avail < 0L) {
            avail = 0L;
        }
        if (avail > total) {
            avail = total;
        }
        long used = total - avail;
        int pct = (int) Math.round(used * 100.0 / total);
        return String.format(Locale.US, "运存 %s / %s · 空闲 %s · 已用 %d%%",
                gb(ctx, total), gb(ctx, total), gb(ctx, avail), pct);
    }

    private static String gb(Context ctx, long bytes) {
        double gb = bytes / 1024.0 / 1024.0 / 1024.0;
        if (gb >= 1.0) {
            return String.format(Locale.US, "%.1f GB", gb);
        }
        double mb = bytes / 1024.0 / 1024.0;
        return String.format(Locale.US, "%.0f MB", mb);
    }

    private static int dp(Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
