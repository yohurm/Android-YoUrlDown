package com.yohu.lib_download.internal.core;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.yohu.lib_download.YoDownload;
import com.yohu.lib_download.internal.engine.DownloadCall;
import com.yohu.lib_download.internal.engine.DownloadDispatcher;
import com.yohu.lib_download.internal.engine.DownloadStrategy;
import com.yohu.lib_download.internal.engine.DownloadWorker;
import com.yohu.lib_download.internal.exception.DownloadException;
import com.yohu.lib_download.internal.exception.FileBusyException;
import com.yohu.lib_download.internal.impl.DownloadConfigImpl;
import com.yohu.lib_download.internal.impl.DownloadRequestImpl;
import com.yohu.lib_download.internal.impl.DownloadTaskImpl;
import com.yohu.lib_download.internal.platform.callback.CallbackDispatcher;
import com.yohu.lib_download.internal.util.DownloadId;
import com.yohu.lib_download.internal.util.DownloadUtil;
import com.yohu.lib_download.util.IoUtil;
import com.yohu.lib_download.model.BreakpointInfo;
import com.yohu.lib_download.log.YoLog;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 下载域内部编排核心，持有默认实例。
 */
public final class DownloadCore implements StatusStore {

    private static final String TAG = "DownloadCore";

    private static volatile DownloadCore instance;

    private final DownloadConfigImpl config;
    private final CallbackDispatcher callbacks;
    private final DownloadDispatcher dispatcher;
    private final DownloadStrategy strategy = new DownloadStrategy();
    private YoDownload.Monitor monitor = new YoDownload.Monitor() {
    };

    private DownloadCore(@NonNull DownloadConfigImpl config) {
        this.config = config;
        this.callbacks = new CallbackDispatcher(
                config.callbackHandler(), config.progressCallbackIntervalMs());
        this.dispatcher = new DownloadDispatcher(config, callbacks);
        config.breakpointStore().onInit();
    }

    public static synchronized void install(@NonNull Context context,
                                            @NonNull YoDownload.Config config) {
        DownloadCore previous = instance;
        if (previous != null) {
            previous.shutdown();
        }
        DownloadConfigImpl internal = config instanceof DownloadConfigImpl
                ? (DownloadConfigImpl) config
                : DownloadConfigImpl.from(context, config);
        instance = new DownloadCore(internal);
        YoLog.i(TAG, "下载引擎已安装");
    }

    @NonNull
    public static DownloadCore get() {
        DownloadCore local = instance;
        if (local == null) {
            throw new IllegalStateException("YoDownload is not initialized. Call YoDownload.init() first.");
        }
        return local;
    }

    /** 供测试/诊断使用的非抛异常访问。 */
    @Nullable
    public static DownloadCore peek() {
        return instance;
    }

    /**
     * 关停引擎：取消全部任务、停止工作线程并释放数据库连接。
     * 关停后可通过 {@link #install} 重新初始化。
     */
    public synchronized void shutdown() {
        dispatcher.shutdown();
        if (config.workerExecutorOwned()) {
            config.workerExecutor().shutdown();
            try {
                if (!config.workerExecutor().awaitTermination(10, TimeUnit.SECONDS)) {
                    config.workerExecutor().shutdownNow();
                    config.workerExecutor().awaitTermination(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                config.workerExecutor().shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        config.breakpointStore().close();
        instance = null;
        YoLog.i(TAG, "下载引擎已关闭");
    }

    @NonNull
    public YoDownload.Task enqueue(@NonNull YoDownload.Request request,
                                   @Nullable YoDownload.Listener listener) {
        YoDownload.Listener safeListener = listener != null ? listener : new YoDownload.Listener() {
        };
        DownloadTaskImpl task = createTask(request);
        callbacks.added(safeListener, task);
        monitor.onTaskCreated(task);
        try {
            validate(request, task);
            BreakpointInfo info = new BreakpointInfo(task.id(), request.url(),
                    task.destinationFile(), task.tempFile(), -1L, null, null,
                    YoDownload.State.ADDED, System.currentTimeMillis(), System.currentTimeMillis(),
                    request.headers(), request.blockCount(), request.networkType(),
                    request.priority(), request.title(), request.mimeType());
            inheritProgress(info, task);
            config.breakpointStore().upsert(info);
            DownloadWorker worker = new DownloadWorker(config, callbacks, strategy, monitor);
            dispatcher.enqueue(new DownloadCall(task, safeListener, dispatcher, worker));
        } catch (Throwable e) {
            YoLog.e(TAG, "任务入队失败: " + task.id(), e);
            YoDownload.Error error = e instanceof FileBusyException
                    ? YoDownload.Error.FILE_BUSY
                    : e instanceof DownloadException
                    ? ((DownloadException) e).error()
                    : YoDownload.Error.UNKNOWN;
            task.setState(YoDownload.State.FAILED);
            task.setError(error);
            YoDownload.Result result = YoDownload.Result.end(task,
                    e instanceof FileBusyException ? YoDownload.EndCause.FILE_BUSY : YoDownload.EndCause.PRECONDITION_FAILED,
                    error, e);
            callbacks.error(safeListener, task, result);
            monitor.onTaskEnd(task, result);
        }
        return task;
    }

    @NonNull
    public List<YoDownload.Task> enqueue(@NonNull List<YoDownload.Request> requests,
                                         @Nullable YoDownload.Listener listener) {
        List<YoDownload.Task> tasks = new ArrayList<>();
        for (YoDownload.Request request : requests) {
            tasks.add(enqueue(request, listener));
        }
        return tasks;
    }

    public boolean pause(@NonNull String taskId) {
        if (dispatcher.pause(taskId)) {
            YoLog.d(TAG, "任务已暂停: " + taskId);
            return true;
        }
        BreakpointInfo info = config.breakpointStore().query(taskId);
        if (info != null) {
            config.breakpointStore().updateState(taskId, YoDownload.State.PAUSED);
            YoLog.d(TAG, "任务已暂停（已持久化）: " + taskId);
            return true;
        }
        YoLog.w(TAG, "暂停已忽略：未找到任务: " + taskId);
        return false;
    }

    public boolean resume(@NonNull String taskId, @Nullable YoDownload.Listener listener) {
        BreakpointInfo info = config.breakpointStore().query(taskId);
        if (info == null || isTerminal(info.state())) {
            YoLog.w(TAG, "恢复已忽略：任务不可恢复: " + taskId);
            return false;
        }
        YoLog.d(TAG, "恢复任务: " + taskId);
        YoDownload.Request request = DownloadRequestImpl.builder(info.url())
                .id(taskId)
                .destinationFile(info.targetFile())
                .headers(info.headers())
                .blockCount(info.blockCount())
                .networkType(info.networkType())
                .priority(info.priority())
                .title(info.title())
                .mimeType(info.mimeType())
                .replaceExisting(true)
                .build();
        enqueue(request, listener);
        return true;
    }

    public boolean cancel(@NonNull String taskId) {
        if (dispatcher.cancel(taskId)) {
            YoLog.d(TAG, "任务已取消: " + taskId);
            return true;
        }
        BreakpointInfo info = config.breakpointStore().query(taskId);
        if (info != null) {
            config.breakpointStore().updateState(taskId, YoDownload.State.CANCELED);
            YoLog.d(TAG, "任务已取消（已持久化）: " + taskId);
            return true;
        }
        YoLog.w(TAG, "取消已忽略：未找到任务: " + taskId);
        return false;
    }

    public boolean remove(@NonNull String taskId, boolean deleteFile) {
        boolean dispatched = dispatcher.remove(taskId, deleteFile);
        BreakpointInfo info = config.breakpointStore().query(taskId);
        if (info == null && !dispatched) {
            YoLog.w(TAG, "移除已忽略：未找到任务: " + taskId);
            return false;
        }
        if (info != null) {
            config.breakpointStore().remove(taskId);
        }
        // 任务已不在调度器中（终态或不存在于内存）时，文件清理由本方法负责；
        // 运行中任务由 worker 在终态分支按 deleteFile 标志清理。
        if (!dispatched && info != null && deleteFile) {
            IoUtil.deleteQuietly(info.targetFile());
            IoUtil.deleteQuietly(info.tempFile());
        }
        YoLog.d(TAG, "任务已移除: " + taskId);
        return true;
    }

    @Nullable
    @Override
    public YoDownload.Task query(@NonNull String taskId) {
        YoDownload.Task running = dispatcher.findTask(taskId);
        if (running != null) {
            return running;
        }
        BreakpointInfo info = config.breakpointStore().query(taskId);
        return info != null ? taskFromInfo(info) : null;
    }

    @NonNull
    public List<YoDownload.Task> queryAll() {
        Map<String, YoDownload.Task> merged = new LinkedHashMap<>();
        for (BreakpointInfo info : config.breakpointStore().listAll()) {
            merged.put(info.taskId(), taskFromInfo(info));
        }
        // 运行中任务以内存实时状态覆盖存储快照，保证查询新鲜度一致。
        for (YoDownload.Task task : dispatcher.liveTasks()) {
            merged.put(task.id(), task);
        }
        return new ArrayList<>(merged.values());
    }

    @NonNull
    @Override
    public List<YoDownload.Task> queryByState(@NonNull YoDownload.State state) {
        List<YoDownload.Task> tasks = new ArrayList<>();
        for (YoDownload.Task task : queryAll()) {
            if (task.state() == state) {
                tasks.add(task);
            }
        }
        return tasks;
    }

    public void setMonitor(@Nullable YoDownload.Monitor monitor) {
        this.monitor = monitor != null ? monitor : new YoDownload.Monitor() {
        };
    }

    @NonNull
    private DownloadTaskImpl createTask(@NonNull YoDownload.Request request) {
        File destination = config.fileStrategy().destinationFile(request);
        File temp = config.fileStrategy().tempFile(destination);
        String id = DownloadId.create(request.id(), request.url(), destination);
        return new DownloadTaskImpl(id, request, destination, temp);
    }

    private void validate(@NonNull YoDownload.Request request,
                          @NonNull DownloadTaskImpl task) throws DownloadException {
        if (!DownloadUtil.isValidUrl(request.url())) {
            throw new DownloadException(YoDownload.Error.INVALID_REQUEST, "Invalid url");
        }
        if (!config.fileStrategy().prepareDestination(
                task.destinationFile(), request.replaceExisting())) {
            throw new DownloadException(YoDownload.Error.STORAGE, "Destination is not writable");
        }
    }

    /**
     * 同一 ID 重新入队（暂停后继续、失败后重试）时沿用上一次的分块进度。
     *
     * <p>入队会用新的请求参数覆盖断点记录；不沿用的话分块被清空，
     * {@link DownloadWorker} 会从 0 重新下载，断点续传形同虚设。
     * 只在上次未到终态、且临时文件仍在时沿用。
     */
    private void inheritProgress(@NonNull BreakpointInfo info, @NonNull DownloadTaskImpl task) {
        BreakpointInfo previous = config.breakpointStore().query(task.id());
        if (previous == null || isTerminal(previous.state()) || previous.blocks().isEmpty()
                || !task.tempFile().exists()) {
            return;
        }
        info.totalLength(previous.totalLength());
        info.etag(previous.etag());
        info.lastModified(previous.lastModified());
        info.replaceBlocks(previous.blocks());
    }

    private static boolean isTerminal(@NonNull YoDownload.State state) {
        return state == YoDownload.State.COMPLETED
                || state == YoDownload.State.CANCELED
                || state == YoDownload.State.REMOVED;
    }

    @NonNull
    private DownloadTaskImpl taskFromInfo(@NonNull BreakpointInfo info) {
        YoDownload.Request request = DownloadRequestImpl.builder(info.url())
                .id(info.taskId())
                .destinationFile(info.targetFile())
                .headers(info.headers())
                .blockCount(info.blockCount())
                .networkType(info.networkType())
                .priority(info.priority())
                .title(info.title())
                .mimeType(info.mimeType())
                .build();
        DownloadTaskImpl task = new DownloadTaskImpl(
                info.taskId(), request, info.targetFile(), info.tempFile());
        task.setState(info.state());
        task.setProgress(new YoDownload.Progress(
                info.downloadedBytes(), info.totalLength(), 0L, 0L));
        return task;
    }
}
