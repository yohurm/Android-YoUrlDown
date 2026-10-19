package com.yohu.lib_urlparse.spi;

/**
 * 引擎运行选项：一次解析过程中所有通道共享的运行时参数。
 *
 * <p>与 {@code UrlParseRequest} 的分工：request 是"这一次解析要什么"，
 * options 是"这次运行允许用什么"（超时、UA 覆盖、凭据、取消）。
 */
public final class EngineOptions {

    private final long timeoutMs;
    private final String userAgentOverride;
    private final CredentialProvider credentials;
    private final CancelToken cancelToken;

    private EngineOptions(Builder b) {
        this.timeoutMs = b.timeoutMs;
        this.userAgentOverride = b.userAgentOverride;
        this.credentials = b.credentials == null ? CredentialProvider.NONE : b.credentials;
        this.cancelToken = b.cancelToken == null ? CancelToken.fresh() : b.cancelToken;
    }

    public static Builder builder() {
        return new Builder();
    }

    public long timeoutMs() {
        return timeoutMs;
    }

    /** 覆盖默认 UA；{@code null} 表示由各通道/判据按域名自行选择。 */
    public String userAgentOverride() {
        return userAgentOverride;
    }

    public CredentialProvider credentials() {
        return credentials;
    }

    public CancelToken cancelToken() {
        return cancelToken;
    }

    /** 构造器。 */
    public static final class Builder {
        private long timeoutMs = 20_000L;
        private String userAgentOverride;
        private CredentialProvider credentials;
        private CancelToken cancelToken;

        public Builder timeoutMs(long v) {
            this.timeoutMs = v > 0 ? v : 20_000L;
            return this;
        }

        public Builder userAgentOverride(String v) {
            this.userAgentOverride = v;
            return this;
        }

        public Builder credentials(CredentialProvider v) {
            this.credentials = v;
            return this;
        }

        public Builder cancelToken(CancelToken v) {
            this.cancelToken = v;
            return this;
        }

        public EngineOptions build() {
            return new EngineOptions(this);
        }
    }
}
