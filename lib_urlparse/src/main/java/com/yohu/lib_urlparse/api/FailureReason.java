package com.yohu.lib_urlparse.api;

/**
 * 失败原因。**枚举是稳定的对外契约**，调用方可以据此分支处理，不要靠匹配 message 文本。
 *
 * <p>区分"可重试"与"配置缺失"很重要：前者换一个通道可能成功，后者换多少通道都没用。
 * 早期实现把两者混成一句话，导致"缺少端点"被当成网络故障反复重试。
 */
public enum FailureReason {

    /** 没有任何通道认领这段输入（不是链接、也不是已知平台的分享文本）。 */
    UNRECOGNISED_INPUT(false),

    /** 通道认领了，但没解析出任何媒体。 */
    NO_MEDIA_FOUND(true),

    /** 服务端有响应，但结构不符（页面改版、接口变更、返回了错误页）。 */
    UNEXPECTED_RESPONSE(true),

    /** 网络失败或 JS 求值失败。 */
    NETWORK(true),

    /** 该通道需要调用方提供端点/凭据，但未配置。 */
    CREDENTIALS_MISSING(false),

    /** 缺少必需的注入服务（例如没有提供 {@code JsEvaluator}）。 */
    CHANNEL_DISABLED(false),

    /** 调用方取消。 */
    CANCELLED(false),

    /** 未预期的内部错误。 */
    INTERNAL(false);

    private final boolean retryable;

    FailureReason(boolean retryable) {
        this.retryable = retryable;
    }

    /** 换下一个通道是否有意义。 */
    public boolean retryable() {
        return retryable;
    }
}
