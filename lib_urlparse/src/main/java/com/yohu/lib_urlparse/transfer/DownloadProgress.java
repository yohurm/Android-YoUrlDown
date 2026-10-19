package com.yohu.lib_urlparse.transfer;

/** 下载进度快照（不可变）。 */
public final class DownloadProgress {

    private final String url;
    private final long bytesReceived;
    /** 服务端声明的总长度；未知时为 -1（chunked 或未给 Content-Length）。 */
    private final long totalBytes;
    private final long elapsedMs;
    private final long bytesPerSecond;

    public DownloadProgress(String url, long bytesReceived, long totalBytes,
                            long elapsedMs, long bytesPerSecond) {
        this.url = url;
        this.bytesReceived = bytesReceived;
        this.totalBytes = totalBytes;
        this.elapsedMs = elapsedMs;
        this.bytesPerSecond = bytesPerSecond;
    }

    public String url() {
        return url;
    }

    public long bytesReceived() {
        return bytesReceived;
    }

    public long totalBytes() {
        return totalBytes;
    }

    public long elapsedMs() {
        return elapsedMs;
    }

    public long bytesPerSecond() {
        return bytesPerSecond;
    }

    /** 总长度未知时返回 -1。 */
    public int percent() {
        if (totalBytes <= 0) {
            return -1;
        }
        long p = bytesReceived * 100L / totalBytes;
        if (p < 0) {
            return 0;
        }
        return p > 100 ? 100 : (int) p;
    }

    public boolean totalKnown() {
        return totalBytes > 0;
    }

    /** 形如 {@code 1.2 MB / 8.4 MB (14%) · 512 KB/s}，便于直接写日志。 */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(human(bytesReceived));
        if (totalKnown()) {
            sb.append(" / ").append(human(totalBytes))
              .append(" (").append(percent()).append("%)");
        } else {
            sb.append(" (总长未知)");
        }
        if (bytesPerSecond > 0) {
            sb.append(" · ").append(human(bytesPerSecond)).append("/s");
        }
        return sb.toString();
    }

    public static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", mb);
        }
        return String.format(java.util.Locale.ROOT, "%.2f GB", mb / 1024.0);
    }
}
