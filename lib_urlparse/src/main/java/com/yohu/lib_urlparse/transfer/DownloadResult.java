package com.yohu.lib_urlparse.transfer;

/** 下载结果。 */
public final class DownloadResult {

    private final String url;
    private final java.io.File file;
    private final long bytes;
    private final long elapsedMs;
    private final DownloadState state;
    private final String contentType;
    /** m3u8 合并时分片数量；普通下载为 1。 */
    private final int segmentCount;

    public DownloadResult(String url, java.io.File file, long bytes, long elapsedMs,
                          DownloadState state, String contentType, int segmentCount) {
        this.url = url;
        this.file = file;
        this.bytes = bytes;
        this.elapsedMs = elapsedMs;
        this.state = state;
        this.contentType = contentType;
        this.segmentCount = segmentCount;
    }

    public String url() {
        return url;
    }

    public java.io.File file() {
        return file;
    }

    public long bytes() {
        return bytes;
    }

    public long elapsedMs() {
        return elapsedMs;
    }

    public DownloadState state() {
        return state;
    }

    public String contentType() {
        return contentType;
    }

    public int segmentCount() {
        return segmentCount;
    }

    public boolean succeeded() {
        return state == DownloadState.COMPLETED && file != null && file.isFile();
    }

    @Override
    public String toString() {
        return (succeeded() ? "OK " : "FAIL ")
                + (file == null ? "(no file)" : file.getAbsolutePath())
                + "  " + DownloadProgress.human(bytes)
                + "  " + elapsedMs + "ms"
                + (segmentCount > 1 ? "  " + segmentCount + " 分片" : "");
    }
}
