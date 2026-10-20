package com.yohu.lib_urlparse.api;

/**
 * 解析请求。
 *
 * <p>只描述"要解析什么"和"允许多久"，不描述"怎么下载"——下载由宿主负责。
 * 这条边界让引擎可以完全不带 IO 落盘地跑单测。
 */
public final class UrlParseRequest {

    /** 默认单次解析超时（含短链展开与页面抓取）。 */
    public static final long DEFAULT_TIMEOUT_MS = 20_000L;

    private final String rawInput;
    private final Platform platformHint;
    private final long timeoutMs;

    private UrlParseRequest(Builder b) {
        this.rawInput = b.rawInput;
        this.platformHint = b.platformHint;
        this.timeoutMs = b.timeoutMs;
    }

    public static Builder builder(String rawInput) {
        return new Builder(rawInput);
    }

    /** 原始输入：分享文本或链接。 */
    public String rawInput() {
        return rawInput;
    }

    /** 平台提示；{@link Platform#UNKNOWN} 表示"根据 URL 自动识别"。 */
    public Platform platformHint() {
        return platformHint;
    }

    public long timeoutMs() {
        return timeoutMs;
    }

    @Override
    public String toString() {
        return "UrlParseRequest{hint=" + platformHint.id() + ", timeout=" + timeoutMs
                + ", input=" + (rawInput == null ? "null" : rawInput.length() + " chars") + '}';
    }

    /** 构造器。 */
    public static final class Builder {
        private final String rawInput;
        private Platform platformHint = Platform.UNKNOWN;
        private long timeoutMs = DEFAULT_TIMEOUT_MS;

        Builder(String rawInput) {
            this.rawInput = rawInput == null ? "" : rawInput;
        }

        public Builder platformHint(Platform v) {
            this.platformHint = v == null ? Platform.UNKNOWN : v;
            return this;
        }

        public Builder timeoutMs(long v) {
            this.timeoutMs = v > 0 ? v : DEFAULT_TIMEOUT_MS;
            return this;
        }

        public UrlParseRequest build() {
            if (rawInput.trim().isEmpty()) {
                throw new IllegalStateException("rawInput must not be blank");
            }
            return new UrlParseRequest(this);
        }
    }
}
