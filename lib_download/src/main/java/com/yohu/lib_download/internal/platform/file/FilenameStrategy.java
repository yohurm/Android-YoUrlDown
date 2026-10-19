package com.yohu.lib_download.internal.platform.file;

import com.yohu.lib_download.YoDownload;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.yohu.lib_download.internal.util.DownloadUtil;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description 文件名推导策略。
 */
public class FilenameStrategy {

    @NonNull
    public String filename(@NonNull YoDownload.Request request) {
        if (request.fileName() != null && !request.fileName().trim().isEmpty()) {
            return DownloadUtil.safeFilename(request.fileName(), "download");
        }
        return DownloadUtil.guessFilename(
                request.url(), null, request.mimeType());
    }
}
