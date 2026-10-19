package com.yohu.lib_download.internal.platform.callback;

import android.os.Handler;

import androidx.annotation.NonNull;

import com.yohu.lib_download.YoDownload;
import com.yohu.lib_download.log.YoLog;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description 下载事件回调分发器，负责线程切换与进度节流。
 */
public final class CallbackDispatcher {

    private static final String TAG = "CallbackDispatcher";

    private final Handler handler;
    private final long progressIntervalMs;
    private final Map<String, Long> lastProgressAt = new ConcurrentHashMap<>();

    public CallbackDispatcher(@NonNull Handler handler, long progressIntervalMs) {
        this.handler = handler;
        this.progressIntervalMs = progressIntervalMs;
    }

    public void added(@NonNull YoDownload.Listener listener, @NonNull YoDownload.Task task) {
        post(listener, task, l -> l.onAdded(task));
    }

    public void queued(@NonNull YoDownload.Listener listener, @NonNull YoDownload.Task task) {
        post(listener, task, l -> l.onQueued(task));
    }

    public void start(@NonNull YoDownload.Listener listener, @NonNull YoDownload.Task task) {
        post(listener, task, l -> l.onStart(task));
    }

    public void blockStart(@NonNull YoDownload.Listener listener,
                           @NonNull YoDownload.Task task,
                           int blockIndex,
                           long start,
                           long end) {
        post(listener, task, l -> l.onBlockStart(task, blockIndex, start, end));
    }

    public void progress(@NonNull YoDownload.Listener listener,
                         @NonNull YoDownload.Task task,
                         @NonNull YoDownload.Progress progress,
                         boolean force) {
        long now = System.currentTimeMillis();
        Long last = lastProgressAt.get(task.id());
        if (!force && last != null && now - last < progressIntervalMs) {
            return;
        }
        lastProgressAt.put(task.id(), now);
        post(listener, task, l -> l.onProgress(task, progress));
    }

    public void retry(@NonNull YoDownload.Listener listener,
                      @NonNull YoDownload.Task task,
                      int retryIndex,
                      @NonNull Throwable cause) {
        post(listener, task, l -> l.onRetry(task, retryIndex, cause));
    }

    public void paused(@NonNull YoDownload.Listener listener,
                       @NonNull YoDownload.Task task,
                       @NonNull YoDownload.Result result) {
        post(listener, task, l -> l.onPaused(task, result));
    }

    public void canceled(@NonNull YoDownload.Listener listener,
                         @NonNull YoDownload.Task task,
                         @NonNull YoDownload.Result result) {
        post(listener, task, l -> l.onCanceled(task, result));
    }

    public void success(@NonNull YoDownload.Listener listener,
                        @NonNull YoDownload.Task task,
                        @NonNull YoDownload.Result result) {
        post(listener, task, l -> l.onSuccess(task, result));
    }

    public void error(@NonNull YoDownload.Listener listener,
                      @NonNull YoDownload.Task task,
                      @NonNull YoDownload.Result result) {
        post(listener, task, l -> l.onError(task, result));
    }

    public void removed(@NonNull YoDownload.Listener listener, @NonNull YoDownload.Task task) {
        post(listener, task, l -> l.onRemoved(task));
    }

    private void post(@NonNull YoDownload.Listener listener,
                      @NonNull YoDownload.Task task,
                      @NonNull Consumer<YoDownload.Listener> event) {
        Runnable runnable = () -> {
            try {
                event.accept(listener);
            } catch (RuntimeException e) {
                YoLog.w(TAG, "监听器回调失败，任务: " + task.id(), e);
            }
        };
        if (task.request().autoCallbackToMainThread()) {
            handler.post(runnable);
        } else {
            runnable.run();
        }
    }
}
