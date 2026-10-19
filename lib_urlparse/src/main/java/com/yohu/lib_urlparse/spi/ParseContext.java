package com.yohu.lib_urlparse.spi;

import com.yohu.lib_urlparse.api.Platform;

/**
 * 通道在解析过程中能拿到的运行时资源。
 *
 * <p>刻意保持窄：只给"解析必需"的东西。特别是**不给 Context、不给 Activity、不给文件路径**——
 * 通道一旦能碰这些，就再也回不到纯 JVM 可测的状态。
 */
public interface ParseContext {

    /** HTTP 客户端（线程安全，可复用）。 */
    HttpClient http();

    /** 日志出口。 */
    EngineLogger logger();

    /**
     * 展开后的最终 URL（短链已被解析）；未展开时为 {@code null}。
     *
     * <p>通道应优先用它；为空时自行从 {@code request.rawInput()} 中提取并展开。
     */
    String resolvedUrl();

    /** 运行参数（超时、UA 覆盖、凭据、取消令牌）。 */
    EngineOptions options();

    /** 请求里携带的平台提示；{@link Platform#UNKNOWN} 表示未指定。 */
    Platform platformHint();

    /**
     * 取注入的服务实现（{@code JsEvaluator} / {@code PageSniffer} / {@code MediaMuxer}）。
     *
     * @return 实现；未注入时返回 {@code null}（调用方应抛 {@code CHANNEL_DISABLED}）
     */
    <T> T service(Class<T> type);
}
