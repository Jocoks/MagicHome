package io.github.jocoks.magichome.feature;

/**
 * 日志出口。
 *
 * 钩子跑在荣耀桌面进程里，日志必须走 LSPosed 模块日志（用户就是在管理器里看的），
 * 所以入口类安装一个 {@link Sink}，两层代码都只通过这里打日志。
 */
public final class Logs {

    /** 调试日志开关，由设置页控制（默认关） */
    private static volatile boolean sDebug = false;

    public interface Sink {
        void log(int priority, String tag, String msg, Throwable tr);
    }

    private static volatile Sink sSink;

    private Logs() {
    }

    public static void install(Sink sink) {
        sSink = sink;
    }

    /** 设置页打开/关闭调试日志（钩子侧每次读设置时同步） */
    public static void setDebug(boolean on) {
        sDebug = on;
    }

    public static boolean isDebug() {
        return sDebug;
    }

    /** 过程日志：只有打开调试日志才输出 */
    public static void i(String msg) {
        if (sDebug) {
            emit(android.util.Log.INFO, msg, null);
        }
    }

    /** 更细的过程日志：同样受调试开关控制 */
    public static void d(String msg) {
        if (sDebug) {
            emit(android.util.Log.INFO, msg, null);
        }
    }

    public static void w(String msg) {
        emit(android.util.Log.WARN, msg, null);
    }

    public static void e(String msg, Throwable t) {
        emit(android.util.Log.ERROR, msg, t);
    }

    private static void emit(int priority, String msg, Throwable t) {
        Sink sink = sSink;
        if (sink != null) {
            try {
                sink.log(priority, Constants.TAG, msg, t);
                return;
            } catch (Throwable ignored) {
            }
        }
        // 兜底（模块入口还没装 sink 时）
        if (t != null) {
            android.util.Log.println(priority, Constants.TAG, msg + " :: " + t);
        } else {
            android.util.Log.println(priority, Constants.TAG, msg);
        }
    }
}
