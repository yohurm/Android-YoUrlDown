package com.yohu.lib_download.internal.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.yohu.lib_download.YoDownload;

import java.io.File;

/**
 * {@link YoDownload.Task} 内部实现。
 *
 * <p>状态更新方法保留在内部实现类上，对外契约 {@link YoDownload.Task} 只读。</p>
 */
public final class DownloadTaskImpl implements YoDownload.Task {

    private final String id;
    private final YoDownload.Request request;
    private final File destinationFile;
    private final File tempFile;
    private volatile YoDownload.State state = YoDownload.State.ADDED;
    private volatile YoDownload.Progress progress = YoDownload.Progress.empty();
    private volatile YoDownload.Error error;

    public DownloadTaskImpl(@NonNull String id,
                            @NonNull YoDownload.Request request,
                            @NonNull File destinationFile,
                            @NonNull File tempFile) {
        this.id = id;
        this.request = request;
        this.destinationFile = destinationFile;
        this.tempFile = tempFile;
    }

    @NonNull
    @Override
    public String id() {
        return id;
    }

    @NonNull
    @Override
    public YoDownload.Request request() {
        return request;
    }

    @NonNull
    @Override
    public File destinationFile() {
        return destinationFile;
    }

    @NonNull
    @Override
    public File tempFile() {
        return tempFile;
    }

    @NonNull
    @Override
    public YoDownload.State state() {
        return state;
    }

    @NonNull
    @Override
    public YoDownload.Progress progress() {
        return progress;
    }

    @Nullable
    @Override
    public YoDownload.Error error() {
        return error;
    }

    public void setState(@NonNull YoDownload.State state) {
        this.state = state;
    }

    public void setProgress(@NonNull YoDownload.Progress progress) {
        this.progress = progress;
    }

    public void setError(@Nullable YoDownload.Error error) {
        this.error = error;
    }
}
