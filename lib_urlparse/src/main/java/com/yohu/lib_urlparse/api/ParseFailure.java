package com.yohu.lib_urlparse.api;

/**
 * 解析失败的统一异常。
 *
 * <p>三个必须携带的信息：
 * <ul>
 *   <li>{@link #reason()} —— 给程序分支用，稳定</li>
 *   <li>{@link #channel()} —— 哪个通道失败的，便于定位</li>
 *   <li>{@link #userHint()} —— 给"人"看的下一步建议，避免把"缺配置"误读成"引擎坏了"</li>
 * </ul>
 *
 * <p>约定：**不允许**用返回 {@code null} 或空列表来表示失败。失败只有这一种表达方式。
 */
public class ParseFailure extends Exception {

    private static final long serialVersionUID = 1L;

    private final FailureReason reason;
    private final String channel;

    public ParseFailure(FailureReason reason, String channel, String message) {
        super(message);
        this.reason = reason == null ? FailureReason.INTERNAL : reason;
        this.channel = channel == null ? "" : channel;
    }

    public ParseFailure(FailureReason reason, String channel, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason == null ? FailureReason.INTERNAL : reason;
        this.channel = channel == null ? "" : channel;
    }

    public FailureReason reason() {
        return reason;
    }

    /** 失败发生的通道；为空表示"没有任何通道接手"。 */
    public String channel() {
        return channel;
    }

    /** 换下一个通道是否有意义（取自 {@link FailureReason#retryable()}）。 */
    public boolean retryable() {
        return reason.retryable();
    }

    /** 给用户看的下一步建议。 */
    public String userHint() {
        switch (reason) {
            case CREDENTIALS_MISSING:
                return "该通道需要你自备的服务端点或凭据。请先配置后重试；本库刻意不内置任何第三方私有服务。";
            case CHANNEL_DISABLED:
                return "该通道需要宿主提供注入服务（例如 WebView 求值器）。请在装配引擎时注入。";
            case NETWORK:
                return "网络请求或脚本求值失败。请检查网络后重试；若页面本身加载不出，换一个输入试试。";
            case UNRECOGNISED_INPUT:
                return "没有通道认领这段输入。请确认它是一条分享链接或分享文本。";
            case NO_MEDIA_FOUND:
                return "通道认领了输入，但页面里没有可下载的媒体。可能是作品已删除，或页面结构变化。";
            case UNEXPECTED_RESPONSE:
                return "服务端返回了内容但结构不符：可能页面改版或返回了验证/错误页。";
            case CANCELLED:
                return "已取消。";
            default:
                return "内部错误，请附日志反馈。";
        }
    }

    @Override
    public String toString() {
        return "ParseFailure{" + reason + ", channel='" + channel + "', message=" + getMessage() + '}';
    }
}
