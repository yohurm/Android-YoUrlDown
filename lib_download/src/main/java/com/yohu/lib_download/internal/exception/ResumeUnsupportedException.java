package com.yohu.lib_download.internal.exception;

import androidx.annotation.NonNull;

import com.yohu.lib_download.YoDownload;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description 已存在断点但服务端不支持 Range 或资源校验信息变化时抛出。
 */
public final class ResumeUnsupportedException extends DownloadException {

    public ResumeUnsupportedException(@NonNull String message) {
        super(YoDownload.Error.RESUME_UNSUPPORTED, message);
    }
}
