package com.yohu.lib_download.internal.platform.connection;

import androidx.annotation.Nullable;

import com.yohu.lib_download.internal.util.HeaderUtil;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description HTTP 下载响应元数据。
 */
public final class DownloadResponse {

    private final int responseCode;
    private final long contentLength;
    private final String etag;
    private final String lastModified;
    private final String acceptRanges;
    private final String contentType;
    private final String contentDisposition;

    public DownloadResponse(int responseCode,
                            long contentLength,
                            @Nullable String etag,
                            @Nullable String lastModified,
                            @Nullable String acceptRanges,
                            @Nullable String contentType,
                            @Nullable String contentDisposition) {
        this.responseCode = responseCode;
        this.contentLength = contentLength;
        this.etag = etag;
        this.lastModified = lastModified;
        this.acceptRanges = acceptRanges;
        this.contentType = contentType;
        this.contentDisposition = contentDisposition;
    }

    public int responseCode() {
        return responseCode;
    }

    public long contentLength() {
        return contentLength;
    }

    @Nullable
    public String etag() {
        return etag;
    }

    @Nullable
    public String lastModified() {
        return lastModified;
    }

    public boolean acceptRanges() {
        return HeaderUtil.acceptRanges(acceptRanges);
    }

    @Nullable
    public String contentType() {
        return contentType;
    }

    @Nullable
    public String contentDisposition() {
        return contentDisposition;
    }
}
