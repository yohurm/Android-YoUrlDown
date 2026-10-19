package com.yohu.lib_urlparse.spi;

/** 一次 HTTP 请求的描述。 */
public final class HttpRequest {

    /** HTTP 方法。目前只需要 GET 与 POST（表单/JSON 都是 POST）。 */
    public enum Method {
        GET, POST
    }

    private final Method method;
    private final String url;
    private final String body;
    private final String contentType;
    private final java.util.Map<String, String> headers;

    private HttpRequest(Builder b) {
        this.method = b.method;
        this.url = b.url;
        this.body = b.body;
        this.contentType = b.contentType;
        this.headers = b.headers == null
                ? java.util.Collections.<String, String>emptyMap()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(b.headers));
    }

    public static Builder get(String url) {
        return new Builder(Method.GET, url);
    }

    public static Builder post(String url) {
        return new Builder(Method.POST, url);
    }

    public Method method() {
        return method;
    }

    public String url() {
        return url;
    }

    /** 请求体；GET 时为 {@code null}。 */
    public String body() {
        return body;
    }

    /** 请求体类型；未设置时为 {@code null}（由实现决定默认值）。 */
    public String contentType() {
        return contentType;
    }

    public java.util.Map<String, String> headers() {
        return headers;
    }

    @Override
    public String toString() {
        return method + " " + url;
    }

    /** 构造器。 */
    public static final class Builder {
        private final Method method;
        private final String url;
        private String body;
        private String contentType;
        private java.util.Map<String, String> headers;

        Builder(Method method, String url) {
            this.method = method;
            this.url = url;
        }

        public Builder body(String v) {
            this.body = v;
            return this;
        }

        public Builder contentType(String v) {
            this.contentType = v;
            return this;
        }

        public Builder header(String name, String value) {
            if (name == null || value == null) {
                return this;
            }
            if (this.headers == null) {
                this.headers = new java.util.LinkedHashMap<>();
            }
            this.headers.put(name, value);
            return this;
        }

        public Builder headers(java.util.Map<String, String> v) {
            if (v != null && !v.isEmpty()) {
                if (this.headers == null) {
                    this.headers = new java.util.LinkedHashMap<>();
                }
                this.headers.putAll(v);
            }
            return this;
        }

        public HttpRequest build() {
            if (url == null || url.isEmpty()) {
                throw new IllegalStateException("url must not be empty");
            }
            return new HttpRequest(this);
        }
    }
}
