package com.yohu.lib_download.internal.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.yohu.lib_download.YoDownload;

import java.util.List;

/**
 * 下载状态查询能力。
 */
public interface StatusStore {

    @Nullable
    YoDownload.Task query(@NonNull String taskId);

    @NonNull
    List<YoDownload.Task> queryByState(@NonNull YoDownload.State state);
}
