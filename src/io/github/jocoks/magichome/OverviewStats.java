package io.github.jocoks.magichome;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Color;
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
 * 最近任务界面里的一行运存文字：总运存 / 空闲 / 已用百分比。
 *
 * <p>只有文字、没有背景框；位置在清理键下方。文字颜色在显示时按该区域的实际背景亮度
 * 自动选黑或白（把桌面窗口按 1/4 缩略画一遍再取平均亮度），所以浅色壁纸用黑字、
 * 深色壁纸用白字。数据每 1 秒从 {@link ActivityManager.MemoryInfo} 实时读取。
 */
final class OverviewStats {

    private static final String TAG = "MagicHome";
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
        TextView tv = new TextView(act);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setSingleLine(true);
        tv.setClickable(false);
        tv.setFocusable(false);
        tv.setShadowLayer(dp(act, 4), 0f, dp(act, 1.5f), 0x99000000);
        tv.setText(statsText(act));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = Math.max(0, dp(act, 56) - 20);   // 清理键下方、手势条上方，再下移 20px
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
                am.getMemoryInfo(mi);      // 每次刷新都重新读，实时值
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
        return String.format(Locale.US, "运存 %s · 空闲 %s · 已用 %d%%",
                gb(total), gb(avail), pct);
    }

    private static String gb(long bytes) {
        double gb = bytes / 1024.0 / 1024.0 / 1024.0;
        if (gb >= 1.0) {
            return String.format(Locale.US, "%.1f GB", gb);
        }
        return String.format(Locale.US, "%.0f MB", bytes / 1024.0 / 1024.0);
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
