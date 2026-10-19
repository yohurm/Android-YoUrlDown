package com.yohu.lib_urlparse.spi;

import java.io.File;

/**
 * 页面媒体嗅探。Android 侧用 WebView 实现；单测用假实现。
 *
 * <p>为什么要外置：旧实现把 WebView 嗅探器放在引擎模块里，导致"只要想测引擎就必须起 Android"。
 * 外置之后，嗅探器是宿主提供的能力，引擎只依赖这个接口。
 */
public interface PageSniffer {

    /**
     * 加载页面并观察其网络请求，收集媒体候选。
     *
     * <p>线程：与 {@link JsEvaluator} 一样有线程亲和性（WebView 实现要求），
     * 同一线程创建并调用。
     */
    SniffResult sniff(String pageUrl, long timeoutMs, SniffOptions options) throws Exception;

    /** 释放底层资源。 */
    void release();

    /** 需要落盘临时文件的实现可忽略此方法；默认无操作。 */
    default void setWorkDir(File dir) {
        // no-op
    }
}
