package com.yohu.lib_download.internal.exception;

import androidx.annotation.NonNull;

import com.yohu.lib_download.YoDownload;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description 目标文件正在被其它任务写入时抛出，避免并发写坏文件。
 */
public final class FileBusyException extends DownloadException {

    public FileBusyException(@NonNull String message) {
        super(YoDownload.Error.FILE_BUSY, message);
    }
}
