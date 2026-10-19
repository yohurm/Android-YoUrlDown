package com.yohu.lib_download.internal.engine;

import com.yohu.lib_download.YoDownload;

import androidx.annotation.NonNull;

import com.yohu.lib_download.internal.impl.DownloadConfigImpl;
import com.yohu.lib_download.model.BlockInfo;
import com.yohu.lib_download.internal.platform.connection.DownloadResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description 下载策略：决定是否多分块，以及如何切分 block。
 */
public final class DownloadStrategy {

    public boolean supportMultiBlock(@NonNull YoDownload.Request request,
                                     @NonNull DownloadConfigImpl config,
                                     @NonNull DownloadResponse response) {
        return request.blockCount() > 1
                && config.maxBlocksPerTask() > 1
                && response.acceptRanges()
                && response.contentLength() > 0L;
    }

    @NonNull
    public List<BlockInfo> createBlocks(@NonNull YoDownload.Request request,
                                        @NonNull DownloadConfigImpl config,
                                        long totalLength) {
        int count = Math.min(request.blockCount(), config.maxBlocksPerTask());
        count = Math.max(1, count);
        List<BlockInfo> result = new ArrayList<>(count);
        long blockSize = totalLength / count;
        long start = 0L;
        for (int i = 0; i < count; i++) {
            long end = i == count - 1 ? totalLength - 1 : start + blockSize - 1;
            result.add(new BlockInfo(i, start, end, start));
            start = end + 1;
        }
        return result;
    }
}
