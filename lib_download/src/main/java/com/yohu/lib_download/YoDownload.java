package com.yohu.lib_download;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.yohu.lib_download.internal.core.DownloadCore;
import com.yohu.lib_download.internal.impl.DownloadConfigImpl;
import com.yohu.lib_download.internal.impl.DownloadRequestImpl;
import com.yohu.lib_download.internal.platform.notify.DownloadNotificationController;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 下载域唯一对外静态门面。
 *
 * <p>所有静态方法只负责一行委托，业务实现统一在 {@code download.internal}。</p>
 */
public final class YoDownload {

    private YoDownload() {
    }

    public static void init(@NonNull Context context, @Nullable Config config) {
        DownloadCore.install(context, config != null ? config : Config.defaults(context));
    }

    /**
     * 关停下载引擎：取消全部任务、释放线程池与数据库连接。
     * 需要重新使用时再次调用 {@link #init}。
     */
    public static void shutdown() {
        DownloadCore core = DownloadCore.peek();
        if (core != null) {
            core.shutdown();
        }
    }

    @NonNull
    public static Task enqueue(@NonNull Request request, @Nullable Listener listener) {
        return DownloadCore.get().enqueue(request, listener);
    }

    @NonNull
    public static List<Task> enqueue(@NonNull List<Request> requests,
                                     @Nullable Listener listener) {
        return DownloadCore.get().enqueue(requests, listener);
    }

    public static boolean pause(@NonNull String taskId) {
        return DownloadCore.get().pause(taskId);
    }

    public static boolean resume(@NonNull String taskId, @Nullable Listener listener) {
        return DownloadCore.get().resume(taskId, listener);
    }

    public static boolean cancel(@NonNull String taskId) {
        return DownloadCore.get().cancel(taskId);
    }

    public static boolean remove(@NonNull String taskId, boolean deleteFile) {
        return DownloadCore.get().remove(taskId, deleteFile);
    }

    @Nullable
    public static Task query(@NonNull String taskId) {
        return DownloadCore.get().query(taskId);
    }

    @NonNull
    public static List<Task> queryAll() {
        return DownloadCore.get().queryAll();
    }

    @NonNull
    public static List<Task> queryByState(@NonNull State state) {
        return DownloadCore.get().queryByState(state);
    }

    public static void setMonitor(@Nullable Monitor monitor) {
        DownloadCore.get().setMonitor(monitor);
    }

    @NonNull
    public static Notification notification() {
        return DownloadNotificationController.get();
    }

    public interface Request {

        @Nullable
        String id();

        @NonNull
        String url();

        @Nullable
        File destinationDir();

        @Nullable
        File destinationFile();

        @Nullable
        String fileName();

        @Nullable
        String title();

        @Nullable
        String mimeType();

        @NonNull
        Map<String, String> headers();

        @NonNull
        Priority priority();

        @NonNull
        NetworkType networkType();

        int blockCount();

        boolean autoCallbackToMainThread();

        boolean replaceExisting();

        boolean keepTempFileOnCancel();

        @Nullable
        Object tag();

        static Builder builder(@NonNull String url) {
            return DownloadRequestImpl.builder(url);
        }

        interface Builder {
            Builder id(@Nullable String id);
            Builder destinationDir(@Nullable File destinationDir);
            Builder destinationFile(@Nullable File destinationFile);
            Builder fileName(@Nullable String fileName);
            Builder title(@Nullable String title);
            Builder mimeType(@Nullable String mimeType);
            Builder header(@NonNull String key, @NonNull String value);
            Builder headers(@Nullable Map<String, String> headers);
            Builder priority(@NonNull Priority priority);
            Builder networkType(@NonNull NetworkType networkType);
            Builder blockCount(int blockCount);
            Builder autoCallbackToMainThread(boolean autoCallbackToMainThread);
            Builder replaceExisting(boolean replaceExisting);
            Builder keepTempFileOnCancel(boolean keepTempFileOnCancel);
            Builder tag(@Nullable Object tag);
            Request build();
        }
    }

    public interface Task {
        @NonNull
        String id();

        @NonNull
        Request request();

        @NonNull
        File destinationFile();

        @NonNull
        File tempFile();

        @NonNull
        State state();

        @NonNull
        Progress progress();

        @Nullable
        Error error();
    }

    public interface Listener {
        default void onAdded(@NonNull Task task) {
        }

        default void onQueued(@NonNull Task task) {
        }

        default void onStart(@NonNull Task task) {
        }

        default void onBlockStart(@NonNull Task task, int blockIndex, long start, long end) {
        }

        default void onProgress(@NonNull Task task, @NonNull Progress progress) {
        }

        default void onRetry(@NonNull Task task, int retryIndex, @NonNull Throwable cause) {
        }

        default void onPaused(@NonNull Task task, @NonNull Result result) {
        }

        default void onCanceled(@NonNull Task task, @NonNull Result result) {
        }

        default void onSuccess(@NonNull Task task, @NonNull Result result) {
        }

        default void onError(@NonNull Task task, @NonNull Result result) {
        }

        default void onRemoved(@NonNull Task task) {
        }
    }

    public interface Monitor {
        default void onTaskCreated(@NonNull Task task) {
        }

        default void onTaskStarted(@NonNull Task task) {
        }

        default void onTaskEnd(@NonNull Task task, @NonNull Result result) {
        }
    }

    public interface Config {
        @NonNull
        File defaultDownloadDir();

        int maxParallelTasks();

        int maxBlocksPerTask();

        int connectTimeoutMs();

        int readTimeoutMs();

        int bufferSize();

        int maxRetryCount();

        long progressCallbackIntervalMs();

        static Config defaults(@NonNull Context context) {
            return DownloadConfigImpl.defaults(context);
        }

        static Builder builder(@NonNull Context context) {
            return DownloadConfigImpl.builder(context);
        }

        interface Builder {
            Builder defaultDownloadDir(@NonNull File defaultDownloadDir);
            Builder maxParallelTasks(int maxParallelTasks);
            Builder maxBlocksPerTask(int maxBlocksPerTask);
            Builder timeoutMs(int connectTimeoutMs, int readTimeoutMs);
            Builder bufferSize(int bufferSize);
            Builder maxRetryCount(int maxRetryCount);
            Builder progressCallbackIntervalMs(long progressCallbackIntervalMs);
            Config build();
        }
    }

    public interface Notification {
        void attach(@NonNull Context context);

        void showStarted(@NonNull Context context,
                         @NonNull String taskId,
                         @NonNull String title);

        void showProgress(@NonNull Context context,
                          @NonNull String taskId,
                          @NonNull String title,
                          int percent);

        void cancel(@NonNull Context context, @NonNull String taskId);
    }

    public enum State {
        ADDED,
        QUEUED,
        RUNNING,
        PAUSED,
        COMPLETED,
        FAILED,
        CANCELED,
        REMOVED
    }

    public enum Priority {
        LOW,
        NORMAL,
        HIGH
    }

    public enum NetworkType {
        ALL,
        WIFI
    }

    public enum Error {
        INVALID_REQUEST,
        NETWORK,
        HTTP,
        IO,
        STORAGE,
        FILE_BUSY,
        SAME_TASK_BUSY,
        RESUME_UNSUPPORTED,
        CANCELED,
        UNKNOWN
    }

    public enum EndCause {
        COMPLETED,
        CANCELED,
        PAUSED,
        ERROR,
        FILE_BUSY,
        SAME_TASK_BUSY,
        PRECONDITION_FAILED
    }

    public static final class Progress {

        private final long downloadedBytes;
        private final long totalBytes;
        private final long speedBytesPerSecond;
        private final long etaMillis;

        public Progress(long downloadedBytes,
                        long totalBytes,
                        long speedBytesPerSecond,
                        long etaMillis) {
            this.downloadedBytes = Math.max(0L, downloadedBytes);
            this.totalBytes = totalBytes;
            this.speedBytesPerSecond = Math.max(0L, speedBytesPerSecond);
            this.etaMillis = Math.max(0L, etaMillis);
        }

        public static Progress empty() {
            return new Progress(0L, -1L, 0L, 0L);
        }

        public long downloadedBytes() {
            return downloadedBytes;
        }

        public long totalBytes() {
            return totalBytes;
        }

        public long speedBytesPerSecond() {
            return speedBytesPerSecond;
        }

        public long etaMillis() {
            return etaMillis;
        }

        public boolean hasTotalBytes() {
            return totalBytes > 0L;
        }

        public int percent() {
            if (totalBytes <= 0L) {
                return 0;
            }
            return (int) Math.min(100L, downloadedBytes * 100L / totalBytes);
        }
    }

    public static final class Result {

        private final Task task;
        private final YoDownload.EndCause endCause;
        @Nullable
        private final Error error;
        @Nullable
        private final Throwable throwable;
        private final File file;
        private final Progress progress;

        private Result(@NonNull Task task,
                       @NonNull YoDownload.EndCause endCause,
                       @Nullable Error error,
                       @Nullable Throwable throwable,
                       @Nullable File file,
                       @NonNull Progress progress) {
            this.task = task;
            this.endCause = endCause;
            this.error = error;
            this.throwable = throwable;
            this.file = file;
            this.progress = progress;
        }

        @NonNull
        public static Result success(@NonNull Task task) {
            return new Result(task, YoDownload.EndCause.COMPLETED, null, null,
                    task.destinationFile(), task.progress());
        }

        @NonNull
        public static Result end(@NonNull Task task,
                                 @NonNull YoDownload.EndCause cause,
                                 @Nullable Error error,
                                 @Nullable Throwable throwable) {
            return new Result(task, cause, error, throwable,
                    task.destinationFile(), task.progress());
        }

        @NonNull
        public Task task() {
            return task;
        }

        @NonNull
        public YoDownload.EndCause endCause() {
            return endCause;
        }

        @Nullable
        public Error error() {
            return error;
        }

        @Nullable
        public Throwable throwable() {
            return throwable;
        }

        @Nullable
        public File file() {
            return file;
        }

        @NonNull
        public Progress progress() {
            return progress;
        }

        public boolean isSuccess() {
            return endCause == YoDownload.EndCause.COMPLETED;
        }
    }
}
