package com.yohu.lib_download.internal.platform.store;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.yohu.lib_download.YoDownload;
import com.yohu.lib_download.model.BlockInfo;
import com.yohu.lib_download.model.BreakpointInfo;

import java.util.List;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description 断点存储接口。
 */
public interface BreakpointStore {

    void onInit();

    /** 释放底层存储资源；引擎关停时调用。 */
    void close();

    void upsert(@NonNull BreakpointInfo info);

    void updateState(@NonNull String taskId, @NonNull YoDownload.State state);

    void updateMeta(@NonNull BreakpointInfo info);

    void updateBlockProgress(@NonNull String taskId, int blockIndex, long currentOffset);

    @Nullable
    BreakpointInfo query(@NonNull String taskId);

    @NonNull
    List<BreakpointInfo> listByState(@NonNull YoDownload.State state);

    @NonNull
    List<BreakpointInfo> listAll();

    void remove(@NonNull String taskId);
}
