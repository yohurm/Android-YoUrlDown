package com.yohu.lib_urlparse.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 页面嗅探结果。 */
public final class SniffResult {

    /** 嗅探到的一条媒体候选。 */
    public static final class Candidate {
        private final String url;
        private final String mimeType;
        private final String contentTypeHeader;
        private final long contentLength;

        public Candidate(String url, String mimeType, String contentTypeHeader, long contentLength) {
            this.url = url;
            this.mimeType = mimeType;
            this.contentTypeHeader = contentTypeHeader;
            this.contentLength = contentLength;
        }

        public String url() {
            return url;
        }

        /** 推断出的 MIME（可能来自扩展名或响应头）。 */
        public String mimeType() {
            return mimeType;
        }

        /** 原始 {@code Content-Type} 响应头（可能为 {@code null}）。 */
        public String contentTypeHeader() {
            return contentTypeHeader;
        }

        /** 字节数；未知为 {@code -1}。 */
        public long contentLength() {
            return contentLength;
        }

        @Override
        public String toString() {
            return mimeType + " " + url;
        }
    }

    private final String finalUrl;
    private final String title;
    private final List<Candidate> candidates;
    private final int requestCount;
    private final boolean timedOut;
    private final Map<String, String> extras;

    private SniffResult(Builder b) {
        this.finalUrl = b.finalUrl;
        this.title = b.title;
        this.candidates = Collections.unmodifiableList(new ArrayList<>(b.candidates));
        this.requestCount = b.requestCount;
        this.timedOut = b.timedOut;
        this.extras = Collections.unmodifiableMap(new LinkedHashMap<>(b.extras));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 页面最终 URL（跟随重定向后）。 */
    public String finalUrl() {
        return finalUrl;
    }

    /** 页面标题；用于识别"拦截页/错误页"。 */
    public String title() {
        return title;
    }

    public List<Candidate> candidates() {
        return candidates;
    }

    /** 观察到的网络请求数。 */
    public int requestCount() {
        return requestCount;
    }

    /** 是否因超时提前结束（此时候选可能不完整）。 */
    public boolean timedOut() {
        return timedOut;
    }

    public Map<String, String> extras() {
        return extras;
    }

    @Override
    public String toString() {
        return "SniffResult{requests=" + requestCount + ", candidates=" + candidates.size()
                + ", title=" + title + '}';
    }

    /** 构造器。 */
    public static final class Builder {
        private String finalUrl;
        private String title;
        private final List<Candidate> candidates = new ArrayList<>();
        private int requestCount;
        private boolean timedOut;
        private final Map<String, String> extras = new LinkedHashMap<>();

        public Builder finalUrl(String v) {
            this.finalUrl = v;
            return this;
        }

        public Builder title(String v) {
            this.title = v;
            return this;
        }

        public Builder candidate(Candidate v) {
            if (v != null && v.url() != null && !v.url().isEmpty()) {
                this.candidates.add(v);
            }
            return this;
        }

        public Builder candidates(List<Candidate> v) {
            if (v != null) {
                for (Candidate c : v) {
                    candidate(c);
                }
            }
            return this;
        }

        public Builder requestCount(int v) {
            this.requestCount = v;
            return this;
        }

        public Builder timedOut(boolean v) {
            this.timedOut = v;
            return this;
        }

        public Builder extra(String key, String value) {
            if (key != null && value != null) {
                this.extras.put(key, value);
            }
            return this;
        }

        public SniffResult build() {
            return new SniffResult(this);
        }
    }
}
