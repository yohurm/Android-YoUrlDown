package com.yohu.lib_download.log;

/**
 * 下载子系统的日志垫片。
 *
 * <p>原 {@code lib_common} 的 {@code YoLog} 背后是一整套日志子系统（文件 sink、崩溃捕获、
 * 生命周期……），而下载子系统只用到 {@code d/i/w/e} 四个方法。为了让"合入下载"这件事
 * 不把整套日志一起拖进来，这里提供一个同签名的最小实现：默认静默，可挂一个 sink。
 */
public final class YoLog {

    /** 日志出口（可为 null，表示静默）。 */
    public interface Sink {
        void log(char level, String tag, String message);
    }

    private static volatile Sink sink;

    private YoLog() {
    }

    public static void setSink(Sink s) {
        sink = s;
    }

    public static void d(String tag, String message) {
        emit('d', tag, message);
    }

    public static void i(String tag, String message) {
        emit('i', tag, message);
    }

    public static void w(String tag, String message) {
        emit('w', tag, message);
    }

    public static void e(String tag, String message) {
        emit('e', tag, message);
    }

    public static void e(String tag, String message, Throwable t) {
        emit('e', tag, withCause(message, t));
    }

    public static void d(String tag, String message, Throwable t) {
        emit('d', tag, withCause(message, t));
    }

    public static void i(String tag, String message, Throwable t) {
        emit('i', tag, withCause(message, t));
    }

    public static void w(String tag, String message, Throwable t) {
        emit('w', tag, withCause(message, t));
    }

    private static String withCause(String message, Throwable t) {
        return t == null ? message : message + " | " + t;
    }
    private static void emit(char level, String tag, String message) {
        Sink s = sink;
        if (s != null) {
            s.log(level, tag, message);
        }
    }
}
