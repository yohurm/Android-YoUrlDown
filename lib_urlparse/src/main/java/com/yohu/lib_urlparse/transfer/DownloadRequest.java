package com.yohu.lib_urlparse.transfer;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单次下载请求。
 *
 * <p>刻意不依赖 Android 类型：{@code File} 由调用方给出，因此这一层在纯 JVM 上也能跑测试。
 * Android 侧"输出到应用私有外部目录 / MediaStore"的选择放在宿主（见 {@code app} 模块）。
 */
public final class DownloadRequest {

    private final String url;
    private final File targetFile;
    private final Map<String, String> headers;
    private final String userAgent;
    private final long connectTimeoutMs;
    private final long readTimeoutMs;
    private final boolean resume;
    /** 期望总长度；<=0 表示未知，不做校验。 */
    private final long expectedSize;
    /** 该 URL 是否按 m3u8 播放列表处理（true 时由 M3u8Downloader 走分片合并）。 */
    private final boolean m3u8;

    private DownloadRequest(Builder b) {
        this.url = b.url;
        this.targetFile = b.targetFile;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
        this.userAgent = b.userAgent;
        this.connectTimeoutMs = b.connectTimeoutMs;
        this.readTimeoutMs = b.readTimeoutMs;
        this.resume = b.resume;
        this.expectedSize = b.expectedSize;
        this.m3u8 = b.m3u8;
    }

    public String url() {
        return url;
    }

    public File targetFile() {
        return targetFile;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public String userAgent() {
        return userAgent;
    }

    public long connectTimeoutMs() {
        return connectTimeoutMs;
    }

    public long readTimeoutMs() {
        return readTimeoutMs;
    }

    public boolean resume() {
        return resume;
    }

    public long expectedSize() {
        return expectedSize;
    }

    public boolean m3u8() {
        return m3u8;
    }

    /** 临时文件：下载中写这里，完成后原子改名为 {@link #targetFile()}。 */
    public File partFile() {
        return new File(targetFile.getAbsolutePath() + ".part");
    }

    public static Builder builder(String url, File targetFile) {
        return new Builder(url, targetFile);
    }

    public static final class Builder {
        private final String url;
        private final File targetFile;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String userAgent = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";
        private long connectTimeoutMs = 10_000L;
        private long readTimeoutMs = 30_000L;
        private boolean resume = true;
        private long expectedSize = -1L;
        private boolean m3u8 = false;

        Builder(String url, File targetFile) {
            this.url = url;
            this.targetFile = targetFile;
        }

        public Builder header(String name, String value) {
            this.headers.put(name, value);
            return this;
        }

        public Builder userAgent(String v) {
            this.userAgent = v;
            return this;
        }

        public Builder timeouts(long connectMs, long readMs) {
            this.connectTimeoutMs = connectMs;
            this.readTimeoutMs = readMs;
            return this;
        }

        public Builder resume(boolean v) {
            this.resume = v;
            return this;
        }

        public Builder expectedSize(long v) {
            this.expectedSize = v;
            return this;
        }

        public Builder m3u8(boolean v) {
            this.m3u8 = v;
            return this;
        }

        public DownloadRequest build() {
            if (url == null || url.trim().isEmpty()) {
                throw new IllegalArgumentException("url must not be empty");
            }
            if (targetFile == null) {
                throw new IllegalArgumentException("targetFile == null");
            }
            return new DownloadRequest(this);
        }
    }
}
