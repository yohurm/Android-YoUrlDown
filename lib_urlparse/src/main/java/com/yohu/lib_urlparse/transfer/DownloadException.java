package com.yohu.lib_urlparse.transfer;

/** 下载失败原因。 */
public class DownloadException extends Exception {

    public enum Reason {
        /** 网络错误或超时。 */
        NETWORK,
        /** HTTP 状态码非 2xx（例如 403/404/451）。 */
        HTTP_STATUS,
        /** 服务端返回的长度与声明不符，或连接提前断开（已下载部分保留，可续传）。 */
        INCOMPLETE,
        /** 落盘失败（空间不足、路径不可写等）。 */
        IO,
        /** 调用方取消。 */
        CANCELLED,
        /** 输入不合法（URL 为空、目标目录不存在等）。 */
        INVALID_INPUT,
        /** m3u8 解析/分片下载失败。 */
        M3U8,
        /** DASH 音视频轨合并失败（音视频本体通常已下好，可回退为保留两个分轨文件）。 */
        MUX_FAILED,
        /** 其它。 */
        OTHER
    }

    private final Reason reason;
    private final int httpStatus;
    private final long bytesReceived;

    public DownloadException(Reason reason, String message) {
        this(reason, message, null, -1, -1L);
    }

    public DownloadException(Reason reason, String message, Throwable cause) {
        this(reason, message, cause, -1, -1L);
    }

    public DownloadException(Reason reason, String message, Throwable cause,
                             int httpStatus, long bytesReceived) {
        super(message, cause);
        this.reason = reason;
        this.httpStatus = httpStatus;
        this.bytesReceived = bytesReceived;
    }

    public Reason reason() {
        return reason;
    }

    /** 相关 HTTP 状态码；不适用时为 -1。 */
    public int httpStatus() {
        return httpStatus;
    }

    /** 失败前已写入的字节数；不适用时为 -1。 */
    public long bytesReceived() {
        return bytesReceived;
    }

    /** 该失败是否留下可续传的 {@code .part} 文件。 */
    public boolean resumable() {
        return (reason == Reason.INCOMPLETE || reason == Reason.NETWORK
                || reason == Reason.CANCELLED) && bytesReceived > 0;
    }
}
