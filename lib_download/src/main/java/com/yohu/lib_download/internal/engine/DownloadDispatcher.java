package com.yohu.lib_download.internal.engine;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.yohu.lib_download.internal.impl.DownloadConfigImpl;
import com.yohu.lib_download.YoDownload;
import com.yohu.lib_download.internal.impl.DownloadTaskImpl;
import com.yohu.lib_download.internal.platform.callback.CallbackDispatcher;
import com.yohu.lib_download.internal.exception.FileBusyException;
import com.yohu.lib_download.log.YoLog;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Iterator;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description 下载任务调度器，负责队列、并发限制、文件冲突与任务控制。
 */
public final class DownloadDispatcher {

    private static final String TAG = "DownloadDispatcher";

    private final DownloadConfigImpl config;
    private final CallbackDispatcher callbacks;
    private final PriorityQueue<DownloadCall> readyCalls = new PriorityQueue<>();
    private final Map<String, DownloadCall> runningCalls = new HashMap<>();
    private final Map<String, DownloadCall> allCalls = new HashMap<>();

    public DownloadDispatcher(@NonNull DownloadConfigImpl config,
                              @NonNull CallbackDispatcher callbacks) {
        this.config = config;
        this.callbacks = callbacks;
    }

    public synchronized void enqueue(@NonNull DownloadCall call) throws FileBusyException {
        checkBusy(call);
        allCalls.put(call.task().id(), call);
        call.task().setState(YoDownload.State.QUEUED);
        config.breakpointStore().updateState(call.task().id(), YoDownload.State.QUEUED);
        callbacks.queued(call.listener(), call.task());
        readyCalls.add(call);
        processCalls();
        YoLog.d(TAG, "任务已入队: " + call.task().id());
    }

    public synchronized boolean pause(@NonNull String taskId) {
        DownloadCall call = allCalls.get(taskId);
        if (call == null) {
            return false;
        }
        call.pause();
        if (readyCalls.remove(call)) {
            call.task().setState(YoDownload.State.PAUSED);
            config.breakpointStore().updateState(taskId, YoDownload.State.PAUSED);
            callbacks.paused(call.listener(), call.task(),
                    YoDownload.Result.end(call.task(), YoDownload.EndCause.PAUSED, null, null));
            allCalls.remove(taskId);
            YoLog.d(TAG, "任务已暂停: " + taskId);
        }
        return true;
    }

    public synchronized boolean cancel(@NonNull String taskId) {
        DownloadCall call = allCalls.get(taskId);
        if (call == null) {
            return false;
        }
        call.cancel();
        if (readyCalls.remove(call)) {
            call.task().setState(YoDownload.State.CANCELED);
            call.task().setError(YoDownload.Error.CANCELED);
            config.breakpointStore().updateState(taskId, YoDownload.State.CANCELED);
            callbacks.canceled(call.listener(), call.task(),
                    YoDownload.Result.end(call.task(), YoDownload.EndCause.CANCELED,
                            YoDownload.Error.CANCELED, null));
            allCalls.remove(taskId);
            YoLog.d(TAG, "任务已取消: " + taskId);
        }
        return true;
    }

    /**
     * 移除任务：取消并标记为 removed。运行中的任务由 worker 在终态分支完成文件清理，
     * 避免 remove 与 worker 写入/重命名互相踩踏。
     */
    public synchronized boolean remove(@NonNull String taskId, boolean deleteFile) {
        DownloadCall call = allCalls.get(taskId);
        if (call == null) {
            return false;
        }
        call.cancel();
        call.markRemoved(deleteFile);
        if (readyCalls.remove(call)) {
            call.task().setState(YoDownload.State.REMOVED);
            allCalls.remove(taskId);
            YoLog.d(TAG, "任务已移除: " + taskId);
        }
        return true;
    }

    @Nullable
    public synchronized DownloadTaskImpl findTask(@NonNull String taskId) {
        DownloadCall call = allCalls.get(taskId);
        return call != null ? call.task() : null;
    }

    /** 返回所有内存中任务（排队 + 运行中）的只读快照，供统一查询合并。 */
    @NonNull
    public synchronized List<DownloadTaskImpl> liveTasks() {
        List<DownloadTaskImpl> tasks = new ArrayList<>(allCalls.size());
        for (DownloadCall call : allCalls.values()) {
            tasks.add(call.task());
        }
        return tasks;
    }

    synchronized void finished(@NonNull DownloadCall call) {
        runningCalls.remove(call.task().id());
        allCalls.remove(call.task().id());
        processCalls();
    }

    /** 取消所有未完成任务，供引擎关停使用。 */
    public synchronized void shutdown() {
        YoLog.i(TAG, "调度器关闭中，任务数=" + allCalls.size());
        for (DownloadCall call : allCalls.values()) {
            call.cancel();
            call.markRemoved(false);
        }
        readyCalls.clear();
        allCalls.clear();
        runningCalls.clear();
    }

    private void processCalls() {
        while (runningCalls.size() < config.maxParallelTasks() && !readyCalls.isEmpty()) {
            DownloadCall call = readyCalls.poll();
            runningCalls.put(call.task().id(), call);
            config.workerExecutor().execute(call);
        }
    }

    private void checkBusy(@NonNull DownloadCall newCall) throws FileBusyException {
        if (allCalls.containsKey(newCall.task().id())) {
            YoLog.w(TAG, "忙碌：相同任务已在队列或运行中: " + newCall.task().id());
            throw new FileBusyException("Same task is already queued or running");
        }
        File target = newCall.task().destinationFile();
        for (Iterator<DownloadCall> iterator = allCalls.values().iterator(); iterator.hasNext(); ) {
            DownloadCall call = iterator.next();
            if (target.equals(call.task().destinationFile())) {
                YoLog.w(TAG, "忙碌：文件正被任务 " + call.task().id()
                        + " 使用: " + target.getAbsolutePath());
                throw new FileBusyException("File is busy: " + target.getAbsolutePath());
            }
        }
    }
}
