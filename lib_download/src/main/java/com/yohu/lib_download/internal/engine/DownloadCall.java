package com.yohu.lib_download.internal.engine;

import androidx.annotation.NonNull;

import com.yohu.lib_download.YoDownload;
import com.yohu.lib_download.internal.impl.DownloadTaskImpl;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description 一个可取消/暂停的下载执行单元。
 */
public final class DownloadCall implements Runnable, Comparable<DownloadCall> {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final long sequence = SEQUENCE.incrementAndGet();
    private final DownloadTaskImpl task;
    private final YoDownload.Listener listener;
    private final DownloadDispatcher dispatcher;
    private final DownloadWorker worker;
    private final AtomicBoolean canceled = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean removed = new AtomicBoolean(false);
    private volatile boolean deleteFilesOnRemoved;

    public DownloadCall(@NonNull DownloadTaskImpl task,
                        @NonNull YoDownload.Listener listener,
                        @NonNull DownloadDispatcher dispatcher,
                        @NonNull DownloadWorker worker) {
        this.task = task;
        this.listener = listener;
        this.dispatcher = dispatcher;
        this.worker = worker;
    }

    @NonNull
    public DownloadTaskImpl task() {
        return task;
    }

    @NonNull
    public YoDownload.Listener listener() {
        return listener;
    }

    public boolean isCanceled() {
        return canceled.get();
    }

    public boolean isPaused() {
        return paused.get();
    }

    public boolean shouldStop() {
        return isCanceled() || isPaused();
    }

    public void cancel() {
        canceled.set(true);
    }

    public void pause() {
        paused.set(true);
    }

    public void markRemoved(boolean deleteFiles) {
        removed.set(true);
        deleteFilesOnRemoved = deleteFiles;
    }

    public boolean isRemoved() {
        return removed.get();
    }

    public boolean deleteFilesOnRemoved() {
        return deleteFilesOnRemoved;
    }

    @Override
    public void run() {
        try {
            worker.run(this);
        } finally {
            dispatcher.finished(this);
        }
    }

    @Override
    public int compareTo(DownloadCall other) {
        int priority = weight(other.task.request().priority())
                - weight(task.request().priority());
        if (priority != 0) {
            return priority;
        }
        return Long.compare(sequence, other.sequence);
    }

    private static int weight(@NonNull YoDownload.Priority priority) {
        switch (priority) {
            case LOW:
                return 0;
            case HIGH:
                return 10;
            case NORMAL:
            default:
                return 5;
        }
    }
}
