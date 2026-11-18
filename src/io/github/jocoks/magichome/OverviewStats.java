package io.github.jocoks.magichome;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
                    // 先隐藏（参与布局但不绘制），采样背景时不会采到自己
                    tv.setVisibility(View.INVISIBLE);
                    sVisible = true;
                    bind();
                    tv.post(new Runnable() {
                        @Override
                        public void run() {
                            applyTextColorByBackground(act, tv);
                            tv.setVisibility(View.VISIBLE);
                        }
                    });
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
        tv.setShadowLayer(dp(act, 3), 0f, dp(act, 1), 0x66000000);
        tv.setText(statsText(act));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(act, 56);   // 清理键下方、手势条上方
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

    /** 把一个 1/4 缩略的桌面窗口画出来，取文字区域的平均亮度，决定用黑字还是白字。 */
    private static void applyTextColorByBackground(Activity act, TextView tv) {
        int lum = -1;
        Bitmap bmp = null;
        try {
            View decor = act.getWindow().getDecorView();
            int w = decor.getWidth();
            int h = decor.getHeight();
            if (w > 0 && h > 0) {
                int sw = Math.max(1, w / 4);
                int sh = Math.max(1, h / 4);
                bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);
                canvas.scale(sw / (float) w, sh / (float) h);
                int[] loc = new int[2];
                tv.getLocationInWindow(loc);
                // 采样区域就是我们文字所在的那一条
                int left = loc[0] / 4;
                int top = loc[1] / 4;
                int right = Math.min(sw - 1, (loc[0] + tv.getWidth()) / 4);
                int bottom = Math.min(sh - 1, (loc[1] + tv.getHeight()) / 4);
                decor.draw(canvas);
                long sum = 0L;
                int n = 0;
                for (int y = Math.max(0, top); y <= bottom; y++) {
                    for (int x = Math.max(0, left); x <= right; x++) {
                        int c = bmp.getPixel(x, y);
                        int a = Color.alpha(c);
                        if (a < 40) {
                            continue;
                        }
                        sum += (long) (0.299 * Color.red(c) + 0.587 * Color.green(c)
                                + 0.114 * Color.blue(c));
                        n++;
                    }
                }
                if (n > 0) {
                    lum = (int) (sum / n);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "sample background failed: " + t);
        } finally {
            if (bmp != null) {
                bmp.recycle();
            }
        }
        boolean darkBackground = lum >= 0 && lum < 140;
        int textColor = darkBackground ? Color.WHITE : Color.parseColor("#FF1B1B1F");
        int shadow = darkBackground ? 0x66000000 : 0x66FFFFFF;
        try {
            tv.setTextColor(textColor);
            tv.setShadowLayer(dp(tv.getContext(), 3), 0f, dp(tv.getContext(), 1), shadow);
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "overview stats color: bgLum=" + lum + " text=" + (darkBackground ? "white" : "black"));
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

    private static int dp(int value) {
        return dp(null, value);
    }

    private static int dp(Context ctx, int value) {
        if (ctx == null) {
            ctx = sView.get() != null ? sView.get().getContext() : null;
        }
        if (ctx == null) {
            return value * 3;
        }
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
