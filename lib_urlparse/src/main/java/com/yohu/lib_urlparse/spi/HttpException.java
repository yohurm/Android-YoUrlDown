package com.yohu.lib_urlparse.spi;

import java.io.IOException;

/**
 * HTTP 层异常。
 *
 * <p>独立于 {@code ParseFailure}：HTTP 是传输层，解析是业务层。让 provider 能看到
 * "是超时、是 403、还是连接被重置"，从而决定要不要换通道/换 UA。
 */
public class HttpException extends IOException {

    private static final long serialVersionUID = 1L;

    /** 失败类别。 */
    public enum Kind {
        /** 连接/读超时。 */
        TIMEOUT,
        /** 无法建立连接（DNS、拒绝、网络不可达）。 */
        CONNECT,
        /** 协议层错误（TLS、响应格式）。 */
        PROTOCOL,
        /** 服务端返回 4xx/5xx（仍可通过 {@link HttpException#status()} 取值）。 */
        STATUS,
        /** 被调用方取消。 */
        CANCELLED
    }

    private final Kind kind;
    private final int status;

    public HttpException(Kind kind, int status, String message) {
        super(message);
        this.kind = kind;
        this.status = status;
    }

    public HttpException(Kind kind, int status, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.status = status;
    }

    public Kind kind() {
        return kind;
    }

    /** HTTP 状态码；非 {@link Kind#STATUS} 时为 {@code 0}。 */
    public int status() {
        return status;
    }

    @Override
    public String toString() {
        return "HttpException{" + kind + ", status=" + status + ", " + getMessage() + '}';
    }
}
