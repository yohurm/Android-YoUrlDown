package com.yohu.lib_urlparse.spi;

/**
 * 协作式取消令牌。
 *
 * <p>为什么不用线程中断：下载与抓取是"多个串行步骤 + 可能并发的分片"，线程中断只能打断一个点，
 * 而且会污染调用方的线程状态。协作式令牌在每一步检查一次，语义清晰、可测。
 */
public final class CancelToken {

    private volatile boolean cancelled;

    /** 未取消的令牌。 */
    /** 永远不会被取消的令牌（别名，便于移植代码沿用旧名）。 */
    public static CancelToken none() {
        return fresh();
    }

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

    /**
     * 若已取消则抛出。
     *
     * @throws HttpException 以 {@link HttpException.Kind#CANCELLED} 表示取消
     */
    public void throwIfCancelled() throws HttpException {
        if (cancelled) {
            throw new HttpException(HttpException.Kind.CANCELLED, 0, "cancelled by caller");
        }
    }
}
