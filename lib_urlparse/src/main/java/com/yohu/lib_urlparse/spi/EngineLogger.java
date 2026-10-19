package com.yohu.lib_urlparse.spi;

/** 日志出口。库本身不决定写到哪里（logcat / 文件 / 内存缓冲都由宿主决定）。 */
public interface EngineLogger {

    /** 日志级别。 */
    enum Level {
        ERROR, WARN, INFO, DEBUG
    }

    void log(Level level, String tag, String message);

    /** 空实现，便于单测与"不要日志"的场景。 */
    EngineLogger NONE = new EngineLogger() {
        @Override
        public void log(Level level, String tag, String message) {
            // intentionally empty
        }
    };

    /** 便捷方法：INFO 级。 */
    default void info(String tag, String message) {
        log(Level.INFO, tag, message);
    }

    /** 便捷方法：WARN 级。 */
    default void warn(String tag, String message) {
        log(Level.WARN, tag, message);
    }

    /** 便捷方法：DEBUG 级。 */
    default void debug(String tag, String message) {
        log(Level.DEBUG, tag, message);
    }
}
