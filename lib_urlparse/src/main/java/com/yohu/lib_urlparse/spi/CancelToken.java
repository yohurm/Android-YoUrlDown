package com.yohu.lib_urlparse.spi;

/**
 * 协作式取消令牌。
 *
 * <p>为什么不用线程中断：解析是"多个串行通道 + 可能并发的请求"，线程中断只能打断一个点，
 * 而且会污染调用方的线程状态。协作式令牌在每一步检查一次，语义清晰、可测。
 */
public final class CancelToken {

    private volatile boolean cancelled;

    /** 新建一个未取消的令牌。 */
    public static CancelToken fresh() {
        return new CancelToken();
    }

    /** 请求取消。可重复调用。 */
    public void cancel() {
        this.cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
