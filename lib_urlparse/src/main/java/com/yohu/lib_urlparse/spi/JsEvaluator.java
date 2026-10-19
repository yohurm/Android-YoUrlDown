package com.yohu.lib_urlparse.spi;

/**
 * JS 求值器：在某个页面上下文里执行一段脚本并取回结果。
 *
 * <p><b>为什么它是 SPI 而不是库内实现</b>：能跑 JS 的宿主只有两种——Android WebView 或
 * 嵌入的 JS 引擎。前者天然属于 Android 层，后者要引入重依赖（与"零运行时依赖"冲突）。
 * 把它外置后，{@code lib_urlparse} 本体不含任何 Android API，provider 也能在纯 JVM 单测里
 * 用假实现驱动。
 *
 * <p><b>线程约束（必须遵守，否则偶发崩溃）</b>：
 * 实现有线程亲和性——实例必须在**有 Looper 的线程**上创建，且后续所有调用必须在**同一线程**上。
 * 乱用会在 WebView 内部触发 "A WebView method was called on thread ..." 之类的错误。
 * 引擎不做线程调度，也不替调用方切换线程。
 */
public interface JsEvaluator {

    /**
     * 先加载页面，再在页面上下文里执行脚本。
     *
     * @param pageUrl   要加载的页面（实现可替换为测试桩页面）
     * @param script    要执行的脚本
     * @param timeoutMs 本次加载 + 求值的总超时
     * @return 脚本返回值（通常是 JSON 字符串）
     * @throws Exception 加载失败、脚本异常、超时
     */
    String loadAndEvaluate(String pageUrl, String script, long timeoutMs) throws Exception;

    /**
     * 在**已加载**的页面上下文里执行脚本（不重新导航）。
     *
     * @throws Exception 脚本异常、超时
     */
    String evaluate(String script, long timeoutMs) throws Exception;

    /** 释放底层资源（WebView 等）。调用后实例不可再用。 */
    void release();
}
