package com.yohu.lib_download.internal.engine;

import androidx.annotation.NonNull;

import com.yohu.lib_download.internal.impl.DownloadConfigImpl;
import com.yohu.lib_download.YoDownload;
import com.yohu.lib_download.internal.impl.DownloadTaskImpl;
import com.yohu.lib_download.model.BlockInfo;
import com.yohu.lib_download.model.BreakpointInfo;
import com.yohu.lib_download.internal.platform.callback.CallbackDispatcher;
import com.yohu.lib_download.internal.platform.connection.DownloadConnection;
import com.yohu.lib_download.internal.platform.connection.DownloadResponse;
import com.yohu.lib_download.internal.exception.DownloadException;
import com.yohu.lib_download.internal.exception.ResumeUnsupportedException;
import com.yohu.lib_download.internal.platform.file.DownloadFileWriter;
import com.yohu.lib_download.internal.platform.file.MultiBlockFileWriter;
import com.yohu.lib_download.internal.platform.notify.DownloadNotificationController;
import com.yohu.lib_download.internal.util.DownloadUtil;
import com.yohu.lib_download.internal.util.DownloadNetwork;
import com.yohu.lib_download.util.IoUtil;
import com.yohu.lib_download.log.YoLog;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description 单任务下载执行控制器。
 */
public final class DownloadWorker {

    private static final String TAG = "DownloadWorker";

    /** 断点进度落盘阈值：避免每读一个 buffer 就写一次 SQLite。 */
    private static final long PROGRESS_PERSIST_THRESHOLD_BYTES = 256L * 1024L;

    private final DownloadConfigImpl config;
    private final CallbackDispatcher callbacks;
    private final DownloadStrategy strategy;
    private final YoDownload.Monitor monitor;

    public DownloadWorker(@NonNull DownloadConfigImpl config,
                          @NonNull CallbackDispatcher callbacks,
                          @NonNull DownloadStrategy strategy,
                          @NonNull YoDownload.Monitor monitor) {
        this.config = config;
        this.callbacks = callbacks;
        this.strategy = strategy;
        this.monitor = monitor;
    }

    public void run(@NonNull DownloadCall call) {
        DownloadTaskImpl task = call.task();
        task.setState(YoDownload.State.RUNNING);
        config.breakpointStore().updateState(task.id(), YoDownload.State.RUNNING);
        callbacks.start(call.listener(), task);
        monitor.onTaskStarted(task);
        YoLog.d(TAG, "任务开始: " + task.id());

        try {
            ensureNetworkConstraint(task.request());
            BreakpointInfo info = prepareBreakpoint(task);
            runTaskWithRetry(call, info);
            if (call.isRemoved()) {
                finishRemoved(call);
                return;
            }
            if (call.isCanceled()) {
                end(call, YoDownload.State.CANCELED, YoDownload.EndCause.CANCELED, YoDownload.Error.CANCELED, null);
                if (!task.request().keepTempFileOnCancel()) {
                    IoUtil.deleteQuietly(task.tempFile());
                }
                return;
            }
            if (call.isPaused()) {
                end(call, YoDownload.State.PAUSED, YoDownload.EndCause.PAUSED, null, null);
                return;
            }
            if (!config.fileStrategy().complete(task.tempFile(), task.destinationFile())) {
                throw new DownloadException(YoDownload.Error.STORAGE, "Cannot move temp file");
            }
            task.setState(YoDownload.State.COMPLETED);
            config.breakpointStore().updateState(task.id(), YoDownload.State.COMPLETED);
            YoDownload.Result result = YoDownload.Result.success(task);
            callbacks.progress(call.listener(), task, task.progress(), true);
            callbacks.success(call.listener(), task, result);
            monitor.onTaskEnd(task, result);
            YoLog.i(TAG, "任务完成: " + task.id());
        } catch (Throwable e) {
            YoLog.e(TAG, "任务失败: " + task.id(), e);
            YoDownload.Error error = mapError(e);
            end(call, YoDownload.State.FAILED, YoDownload.EndCause.ERROR, error, e);
        }
    }

    private void runTaskWithRetry(@NonNull DownloadCall call,
                                  @NonNull BreakpointInfo info) throws Exception {
        int retryIndex = 0;
        while (true) {
            try {
                if (info.blocks().size() > 1) {
                    runMultiBlock(call, info);
                } else {
                    runSequential(call, info);
                }
                return;
            } catch (Throwable e) {
                if (call.shouldStop()
                        || retryIndex >= config.maxRetryCount()
                        || !isRetryable(e)) {
                    throwAsException(e);
                }
                retryIndex++;
                callbacks.retry(call.listener(), call.task(), retryIndex, e);
                YoLog.w(TAG, "任务重试 " + retryIndex + "/" + config.maxRetryCount()
                        + "：任务 " + call.task().id(), e);
            }
        }
    }

    @NonNull
    private BreakpointInfo prepareBreakpoint(@NonNull DownloadTaskImpl task) throws Exception {
        BreakpointInfo existing = config.breakpointStore().query(task.id());
        if (existing != null && task.tempFile().exists() && !existing.blocks().isEmpty()) {
            return existing;
        }

        DownloadConnection connection = null;
        try {
            connection = config.connectionFactory().create(task.request().url(),
                    config.connectTimeoutMs(), config.readTimeoutMs());
            connection.addHeaders(task.request().headers());
            DownloadResponse response = connection.connect();
            if (!DownloadUtil.isSuccessResponse(response.responseCode())) {
                throw new DownloadException(YoDownload.Error.HTTP,
                        "HTTP error: " + response.responseCode());
            }
            long total = response.contentLength();
            BreakpointInfo info = new BreakpointInfo(task.id(), task.request().url(),
                    task.destinationFile(), task.tempFile(), total, response.etag(),
                    response.lastModified(), YoDownload.State.RUNNING,
                    System.currentTimeMillis(), System.currentTimeMillis(),
                    task.request().headers(), task.request().blockCount(),
                    task.request().networkType(), task.request().priority(),
                    task.request().title(), task.request().mimeType());
            List<BlockInfo> blocks = strategy.supportMultiBlock(task.request(), config, response)
                    ? strategy.createBlocks(task.request(), config, total)
                    : Collections.singletonList(new BlockInfo(0, 0L,
                    total > 0L ? total - 1L : -1L, 0L));
            info.replaceBlocks(blocks);
            config.breakpointStore().upsert(info);
            return info;
        } finally {
            IoUtil.closeQuietly(connection);
        }
    }

    private void runSequential(@NonNull DownloadCall call,
                               @NonNull BreakpointInfo info) throws Exception {
        DownloadTaskImpl task = call.task();
        BlockInfo block = info.blocks().get(0);
        // 以真实文件长度为准：文件比断点记录短（被清理/截断）时从头补，
        // 比断点记录长（写盘成功但未落库）时从文件长度续，避免 offset 错位写坏文件。
        long fileLength = task.tempFile().exists() ? task.tempFile().length() : 0L;
        boolean append = fileLength > 0L;
        long startOffset = append ? fileLength : 0L;
        block.currentOffset(startOffset);
        DownloadConnection connection = null;
        InputStream in = null;
        DownloadFileWriter writer = null;
        try {
            connection = config.connectionFactory().create(task.request().url(),
                    config.connectTimeoutMs(), config.readTimeoutMs());
            connection.addHeaders(task.request().headers());
            if (append) {
                connection.setRange(startOffset, block.endOffset());
            }
            DownloadResponse response = connection.connect();
            if (append && !DownloadUtil.isRangeResponse(response.responseCode())) {
                throw new ResumeUnsupportedException("Server ignored range request");
            }
            if (!append && !DownloadUtil.isSuccessResponse(response.responseCode())) {
                throw new DownloadException(YoDownload.Error.HTTP,
                        "HTTP error: " + response.responseCode());
            }
            in = connection.inputStream();
            writer = new DownloadFileWriter(task.tempFile(), append);
            config.breakpointStore().updateBlockProgress(
                    task.id(), block.blockIndex(), block.currentOffset());
            AtomicLong downloaded = new AtomicLong(startOffset);
            long startedAt = System.currentTimeMillis();
            long lastPersisted = -1L;
            byte[] buffer = new byte[config.bufferSize()];
            int read;
            while (!call.shouldStop() && (read = in.read(buffer)) != -1) {
                long position = writer.write(buffer, read);
                block.currentOffset(position);
                if (position - lastPersisted >= PROGRESS_PERSIST_THRESHOLD_BYTES) {
                    config.breakpointStore().updateBlockProgress(
                            task.id(), block.blockIndex(), block.currentOffset());
                    lastPersisted = position;
                }
                onBytes(call, downloaded.addAndGet(read), info.totalLength(), startedAt);
            }
            if (block.currentOffset() != lastPersisted) {
                config.breakpointStore().updateBlockProgress(
                        task.id(), block.blockIndex(), block.currentOffset());
            }
            writer.flush();
        } finally {
            IoUtil.closeQuietly(writer);
            IoUtil.closeQuietly(in);
            IoUtil.closeQuietly(connection);
        }
    }

    private void runMultiBlock(@NonNull DownloadCall call,
                               @NonNull BreakpointInfo info) throws Exception {
        DownloadTaskImpl task = call.task();
        MultiBlockFileWriter writer = null;
        try {
            long tempLength = task.tempFile().exists() ? task.tempFile().length() : 0L;
            for (BlockInfo block : info.blocks()) {
                // 断点偏移超过真实文件长度说明数据已丢失，重置该块从头下载，
                // 避免在稀疏文件里留下空洞后错误地标记 COMPLETED。
                if (block.currentOffset() > tempLength) {
                    block.currentOffset(block.startOffset());
                }
            }
            writer = new MultiBlockFileWriter(task.tempFile(), info.totalLength());
            AtomicLong downloaded = new AtomicLong(info.downloadedBytes());
            long startedAt = System.currentTimeMillis();
            Map<Integer, Long> lastPersisted = new HashMap<>();
            DownloadBlockWorker.ProgressReporter reporter = (block, bytes) -> {
                long offset = block.currentOffset();
                Long last = lastPersisted.get(block.blockIndex());
                if (last == null || offset - last >= PROGRESS_PERSIST_THRESHOLD_BYTES) {
                    config.breakpointStore().updateBlockProgress(
                            task.id(), block.blockIndex(), offset);
                    lastPersisted.put(block.blockIndex(), offset);
                }
                onBytes(call, downloaded.addAndGet(bytes), info.totalLength(), startedAt);
            };
            Future<?>[] futures = new Future<?>[info.blocks().size()];
            DownloadBlockWorker[] workers = new DownloadBlockWorker[info.blocks().size()];
            for (int i = 0; i < info.blocks().size(); i++) {
                BlockInfo block = info.blocks().get(i);
                callbacks.blockStart(call.listener(), task,
                        block.blockIndex(), block.currentOffset(), block.endOffset());
                workers[i] = new DownloadBlockWorker(config, task.request(), call, block, writer, reporter);
                futures[i] = config.workerExecutor().submit(workers[i]);
            }
            Throwable firstError = null;
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    if (firstError == null) {
                        firstError = e.getCause();
                    }
                    // 任一 block 失败即停止其余 block，且必须等所有 future 结束，
                    // 避免 writer 被提前关闭导致剩余块写坏文件。
                    call.pause();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (firstError == null) {
                        firstError = e;
                    }
                    call.pause();
                }
            }
            if (firstError == null) {
                for (DownloadBlockWorker worker : workers) {
                    if (worker.error() != null) {
                        firstError = worker.error();
                        break;
                    }
                }
            }
            for (BlockInfo block : info.blocks()) {
                config.breakpointStore().updateBlockProgress(
                        task.id(), block.blockIndex(), block.currentOffset());
            }
            if (firstError != null) {
                YoLog.w(TAG, "多分块任务失败: " + task.id(), firstError);
                throwAsException(firstError);
            }
            writer.flush();
        } finally {
            IoUtil.closeQuietly(writer);
        }
    }

    private void throwAsException(@NonNull Throwable throwable) throws Exception {
        if (throwable instanceof Exception) {
            throw (Exception) throwable;
        }
        if (throwable instanceof Error) {
            throw (Error) throwable;
        }
        throw new DownloadException(YoDownload.Error.UNKNOWN, "Unknown block error", throwable);
    }

    private boolean isRetryable(@NonNull Throwable throwable) {
        if (throwable instanceof ResumeUnsupportedException) {
            return false;
        }
        YoDownload.Error error = mapError(throwable);
        return error == YoDownload.Error.NETWORK
                || error == YoDownload.Error.IO
                || error == YoDownload.Error.HTTP
                || error == YoDownload.Error.UNKNOWN;
    }

    private void onBytes(@NonNull DownloadCall call,
                         long downloaded,
                         long total,
                         long startedAt) {
        long elapsed = Math.max(1L, System.currentTimeMillis() - startedAt);
        long speed = downloaded * 1000L / elapsed;
        long remain = total > 0L ? Math.max(0L, total - downloaded) : 0L;
        long eta = speed > 0L && total > 0L ? remain * 1000L / speed : 0L;
        YoDownload.Progress progress = new YoDownload.Progress(downloaded, total, speed, eta);
        call.task().setProgress(progress);
        callbacks.progress(call.listener(), call.task(), progress, false);
    }

    private void end(@NonNull DownloadCall call,
                     @NonNull YoDownload.State state,
                     @NonNull YoDownload.EndCause cause,
                     YoDownload.Error error,
                     Throwable throwable) {
        DownloadTaskImpl task = call.task();
        task.setState(state);
        task.setError(error);
        config.breakpointStore().updateState(task.id(), state);
        YoDownload.Result result = YoDownload.Result.end(task, cause, error, throwable);
        callbacks.progress(call.listener(), task, task.progress(), true);
        if (cause == YoDownload.EndCause.PAUSED) {
            callbacks.paused(call.listener(), task, result);
        } else if (cause == YoDownload.EndCause.CANCELED) {
            callbacks.canceled(call.listener(), task, result);
        } else {
            callbacks.error(call.listener(), task, result);
        }
        monitor.onTaskEnd(task, result);
        if (cause != YoDownload.EndCause.PAUSED) {
            DownloadNotificationController.get().cancel(config.context(), task.id());
        }
    }

    private void finishRemoved(@NonNull DownloadCall call) {
        DownloadTaskImpl task = call.task();
        if (call.deleteFilesOnRemoved()) {
            IoUtil.deleteQuietly(task.tempFile());
            IoUtil.deleteQuietly(task.destinationFile());
        } else if (!task.request().keepTempFileOnCancel()) {
            IoUtil.deleteQuietly(task.tempFile());
        }
        task.setState(YoDownload.State.REMOVED);
        task.setError(null);
        YoDownload.Result result =
                YoDownload.Result.end(task, YoDownload.EndCause.CANCELED, null, null);
        callbacks.removed(call.listener(), task);
        monitor.onTaskEnd(task, result);
        YoLog.d(TAG, "任务已按移除结束: " + task.id());
        DownloadNotificationController.get().cancel(config.context(), task.id());
    }

    private void ensureNetworkConstraint(@NonNull YoDownload.Request request)
            throws DownloadException {
        if (request.networkType() == YoDownload.NetworkType.WIFI
                && !DownloadNetwork.isOnWifi(config.context())) {
            throw new DownloadException(
                    YoDownload.Error.NETWORK, "WIFI network required");
        }
    }

    @NonNull
    private YoDownload.Error mapError(Throwable throwable) {
        if (throwable instanceof DownloadException) {
            return ((DownloadException) throwable).error();
        }
        if (throwable instanceof IOException) {
            return YoDownload.Error.IO;
        }
        return YoDownload.Error.UNKNOWN;
    }
}
