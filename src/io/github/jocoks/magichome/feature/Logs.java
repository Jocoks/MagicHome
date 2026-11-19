package io.github.jocoks.magichome.feature;

/**
 * 日志出口。
 *
 * 钩子跑在荣耀桌面进程里，日志必须走 LSPosed 模块日志（用户就是在管理器里看的），
 * 所以入口类安装一个 {@link Sink}，两层代码都只通过这里打日志。
 */
public final class Logs {

    /** 是否输出调试日志（和原来的 DEBUG 开关等价） */
    public static final boolean DEBUG = true;

    public interface Sink {
        void log(int priority, String tag, String msg, Throwable tr);
    }

    private static volatile Sink sSink;

    private Logs() {
    }

    public static void install(Sink sink) {
        sSink = sink;
    }

    public static void i(String msg) {
        emit(android.util.Log.INFO, msg, null);
    }

    /** 只在 DEBUG 下输出 */
    public static void d(String msg) {
        if (DEBUG) {
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
