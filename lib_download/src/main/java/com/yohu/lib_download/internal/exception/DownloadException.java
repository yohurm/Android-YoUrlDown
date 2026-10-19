package com.yohu.lib_download.internal.exception;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.yohu.lib_download.YoDownload;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description 下载模块统一异常，携带可映射到结果层的错误分类。
 */
public class DownloadException extends Exception {

    private final YoDownload.Error error;

    public DownloadException(@NonNull YoDownload.Error error, @NonNull String message) {
        super(message);
        this.error = error;
    }

    public DownloadException(@NonNull YoDownload.Error error,
                             @NonNull String message,
                             @Nullable Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    @NonNull
    public YoDownload.Error error() {
        return error;
    }
}
