package com.yohu.lib_urlparse.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条可下载的媒体条目。
 *
 * <p>{@link #headers()} 不是可选项：很多站点要求下载时带 {@code Referer} 或特定 {@code User-Agent}，
 * 少了它会返回 403。把"抓取时用的头"随条目一起带出来，下载层就不需要猜。
 */
public final class MediaAsset {

    /** 媒体类型。 */
    public enum Kind {
        VIDEO, IMAGE, AUDIO, COVER
    }

    private final Kind kind;
    private final String url;
    private final String mimeType;
    private final long sizeBytes;
    private final long durationMs;
    private final int width;
    private final int height;
    private final Map<String, String> headers;

    private MediaAsset(Builder b) {
        this.kind = b.kind;
        this.url = b.url;
        this.mimeType = b.mimeType;
        this.sizeBytes = b.sizeBytes;
        this.durationMs = b.durationMs;
        this.width = b.width;
        this.height = b.height;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
    }

    public static Builder video(String url) {
        return new Builder(Kind.VIDEO, url);
    }

    public static Builder image(String url) {
        return new Builder(Kind.IMAGE, url);
    }

    public static Builder audio(String url) {
        return new Builder(Kind.AUDIO, url);
    }

    public static Builder cover(String url) {
        return new Builder(Kind.COVER, url);
    }

    public Kind kind() {
        return kind;
    }

    public String url() {
        return url;
    }

    /** MIME；未知为 {@code null}。 */
    public String mimeType() {
        return mimeType;
    }

    /** 字节数；未知为 {@code -1}。 */
    public long sizeBytes() {
        return sizeBytes;
    }

    /** 时长（毫秒）；未知为 {@code -1}。主要对视频有意义。 */
    public long durationMs() {
        return durationMs;
    }

    /** 视频宽（像素）；未知为 {@code 0}。 */
    public int width() {
        return width;
    }

    /** 视频高（像素）；未知为 {@code 0}。 */
    public int height() {
        return height;
    }

    /** 下载该条目应携带的请求头（只读）。 */
    public Map<String, String> headers() {
        return headers;
    }

    @Override
    public String toString() {
        return kind + " " + url;
    }

    /** 构造器。 */
    public static final class Builder {
        private final Kind kind;
        private final String url;
        private String mimeType;
        private long sizeBytes = -1L;
        private long durationMs = -1L;
        private int width;
        private int height;
        private final Map<String, String> headers = new LinkedHashMap<>();

        Builder(Kind kind, String url) {
            this.kind = kind;
            this.url = url;
        }

        public Builder mimeType(String v) {
            this.mimeType = v;
            return this;
        }

        public Builder sizeBytes(long v) {
            this.sizeBytes = v;
            return this;
        }

        public Builder durationMs(long v) {
            this.durationMs = v;
            return this;
        }

        public Builder dimension(int w, int h) {
            this.width = w > 0 ? w : 0;
            this.height = h > 0 ? h : 0;
            return this;
        }

        public Builder header(String name, String value) {
            if (name != null && value != null) {
                this.headers.put(name, value);
            }
            return this;
        }

        public Builder headers(Map<String, String> v) {
            if (v != null) {
                this.headers.putAll(v);
            }
            return this;
        }

        public MediaAsset build() {
            if (url == null || url.isEmpty()) {
                throw new IllegalStateException("MediaAsset.url must not be empty");
            }
            return new MediaAsset(this);
        }
    }
}
