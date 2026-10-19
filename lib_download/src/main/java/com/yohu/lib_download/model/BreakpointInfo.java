package com.yohu.lib_download.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.yohu.lib_download.YoDownload;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description 任务级断点信息。
 */
public final class BreakpointInfo {

    private final String taskId;
    private final String url;
    private final File targetFile;
    private final File tempFile;
    private long totalLength;
    private String etag;
    private String lastModified;
    private YoDownload.State state;
    private final long createdAt;
    private long updatedAt;
    private final List<BlockInfo> blocks = new ArrayList<>();
    private final Map<String, String> headers;
    private final int blockCount;
    private final YoDownload.NetworkType networkType;
    private final YoDownload.Priority priority;
    private final String title;
    private final String mimeType;

    public BreakpointInfo(@NonNull String taskId,
                          @NonNull String url,
                          @NonNull File targetFile,
                          @NonNull File tempFile,
                          long totalLength,
                          @Nullable String etag,
                          @Nullable String lastModified,
                          @NonNull YoDownload.State state,
                          long createdAt,
                          long updatedAt) {
        this(taskId, url, targetFile, tempFile, totalLength, etag, lastModified,
                state, createdAt, updatedAt,
                Collections.emptyMap(), 1, YoDownload.NetworkType.ALL,
                YoDownload.Priority.NORMAL, null, null);
    }

    public BreakpointInfo(@NonNull String taskId,
                          @NonNull String url,
                          @NonNull File targetFile,
                          @NonNull File tempFile,
                          long totalLength,
                          @Nullable String etag,
                          @Nullable String lastModified,
                          @NonNull YoDownload.State state,
                          long createdAt,
                          long updatedAt,
                          @NonNull Map<String, String> headers,
                          int blockCount,
                          @NonNull YoDownload.NetworkType networkType,
                          @NonNull YoDownload.Priority priority,
                          @Nullable String title,
                          @Nullable String mimeType) {
        this.taskId = taskId;
        this.url = url;
        this.targetFile = targetFile;
        this.tempFile = tempFile;
        this.totalLength = totalLength;
        this.etag = etag;
        this.lastModified = lastModified;
        this.state = state;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.headers = Collections.unmodifiableMap(new HashMap<>(headers));
        this.blockCount = Math.max(1, blockCount);
        this.networkType = networkType;
        this.priority = priority;
        this.title = title;
        this.mimeType = mimeType;
    }

    @NonNull
    public String taskId() {
        return taskId;
    }

    @NonNull
    public String url() {
        return url;
    }

    @NonNull
    public File targetFile() {
        return targetFile;
    }

    @NonNull
    public File tempFile() {
        return tempFile;
    }

    public long totalLength() {
        return totalLength;
    }

    public void totalLength(long totalLength) {
        this.totalLength = totalLength;
    }

    @Nullable
    public String etag() {
        return etag;
    }

    public void etag(@Nullable String etag) {
        this.etag = etag;
    }

    @Nullable
    public String lastModified() {
        return lastModified;
    }

    public void lastModified(@Nullable String lastModified) {
        this.lastModified = lastModified;
    }

    @NonNull
    public YoDownload.State state() {
        return state;
    }

    public void state(@NonNull YoDownload.State state) {
        this.state = state;
    }

    public long createdAt() {
        return createdAt;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public void updatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    @NonNull
    public List<BlockInfo> blocks() {
        return Collections.unmodifiableList(blocks);
    }

    @NonNull
    public Map<String, String> headers() {
        return headers;
    }

    public int blockCount() {
        return blockCount;
    }

    @NonNull
    public YoDownload.NetworkType networkType() {
        return networkType;
    }

    @NonNull
    public YoDownload.Priority priority() {
        return priority;
    }

    @Nullable
    public String title() {
        return title;
    }

    @Nullable
    public String mimeType() {
        return mimeType;
    }

    public void replaceBlocks(@NonNull List<BlockInfo> newBlocks) {
        blocks.clear();
        blocks.addAll(newBlocks);
    }

    public long downloadedBytes() {
        long total = 0L;
        for (BlockInfo block : blocks) {
            total += block.downloadedBytes();
        }
        return total;
    }
}
