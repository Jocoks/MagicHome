package io.github.jocoks.magichome.feature;

import android.app.ActivityManager;
import android.content.Context;

import java.util.Locale;

/** 功能层：运存数据与文案。只做读取和格式化，不碰视图。 */
public final class MemoryStats {

    /** 一次内存快照 */
    public static final class Snapshot {
        public final long totalBytes;
        public final long availableBytes;

        Snapshot(long totalBytes, long availableBytes) {
            this.totalBytes = totalBytes;
            this.availableBytes = availableBytes;
        }

        public boolean isValid() {
            return totalBytes > 0L;
        }

        public long usedBytes() {
            return Math.max(0L, totalBytes - availableBytes);
        }

        public int usedPercent() {
            if (totalBytes <= 0L) {
                return 0;
            }
            return (int) Math.round(usedBytes() * 100.0 / totalBytes);
        }
    }

    private MemoryStats() {
    }

    /** 每次调用都重新读，拿到的是实时值 */
    public static Snapshot read(Context ctx) {
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
            Logs.w("read memory info failed: " + t);
        }
        if (avail < 0L) {
            avail = 0L;
        }
        if (avail > total) {
            avail = total;
        }
        return new Snapshot(total, avail);
    }

    /** 「运存 14.8 GB · 空闲 7.1 GB · 已用 52%」 */
    public static String text(Context ctx) {
        Snapshot s = read(ctx);
        if (!s.isValid()) {
            return "运存信息不可用";
        }
        return String.format(Locale.US, "运存 %s · 空闲 %s · 已用 %d%%",
                size(s.totalBytes), size(s.availableBytes), s.usedPercent());
    }

    private static String size(long bytes) {
        double gb = bytes / 1024.0 / 1024.0 / 1024.0;
        if (gb >= 1.0) {
            return String.format(Locale.US, "%.1f GB", gb);
        }
        return String.format(Locale.US, "%.0f MB", bytes / 1024.0 / 1024.0);
    }
}
