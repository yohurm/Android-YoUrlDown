package com.yohu.lib_download.internal.impl;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.yohu.lib_download.YoDownload;
import com.yohu.lib_download.internal.platform.connection.DownloadConnectionFactory;
import com.yohu.lib_download.internal.platform.connection.UrlConnectionFactory;
import com.yohu.lib_download.internal.platform.file.DownloadFileStrategy;
import com.yohu.lib_download.internal.platform.store.BreakpointStore;
import com.yohu.lib_download.internal.platform.store.SqliteBreakpointStore;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@link YoDownload.Config} 内部实现，保留内部依赖装配入口。
 */
public final class DownloadConfigImpl implements YoDownload.Config {

    private final Context appContext;
    private final File defaultDownloadDir;
    private final int maxParallelTasks;
    private final int maxBlocksPerTask;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int bufferSize;
    private final int maxRetryCount;
    private final long progressCallbackIntervalMs;
    private final BreakpointStore breakpointStore;
    private final DownloadConnectionFactory connectionFactory;
    private final DownloadFileStrategy fileStrategy;
    private final Handler callbackHandler;
    private final ExecutorService workerExecutor;
    private final boolean ownsWorkerExecutor;

    private DownloadConfigImpl(@NonNull Builder builder) {
        this.appContext = builder.context;
        this.defaultDownloadDir = builder.defaultDownloadDir;
        this.maxParallelTasks = Math.max(1, builder.maxParallelTasks);
        this.maxBlocksPerTask = Math.max(1, builder.maxBlocksPerTask);
        this.connectTimeoutMs = Math.max(1_000, builder.connectTimeoutMs);
        this.readTimeoutMs = Math.max(1_000, builder.readTimeoutMs);
        this.bufferSize = Math.max(1024, builder.bufferSize);
        this.maxRetryCount = Math.max(0, builder.maxRetryCount);
        this.progressCallbackIntervalMs = Math.max(50L, builder.progressCallbackIntervalMs);
        this.breakpointStore = builder.breakpointStore;
        this.connectionFactory = builder.connectionFactory;
        this.fileStrategy = builder.fileStrategy;
        this.callbackHandler = builder.callbackHandler != null
                ? builder.callbackHandler
                : new Handler(Looper.getMainLooper());
        this.ownsWorkerExecutor = builder.workerExecutor == null;
        if (builder.workerExecutor != null) {
            this.workerExecutor = builder.workerExecutor;
        } else {
            // 有界线程池：单任务最多占 1 + maxBlocksPerTask 个线程，乘以最大并行任务数。
            int workerCount = Math.max(2,
                    this.maxParallelTasks * (this.maxBlocksPerTask + 1));
            this.workerExecutor = Executors.newFixedThreadPool(workerCount);
        }
    }

    @NonNull
    public static DownloadConfigImpl defaults(@NonNull Context context) {
        return builder(context).build();
    }

    @NonNull
    public static Builder builder(@NonNull Context context) {
        return new Builder(context.getApplicationContext());
    }

    @NonNull
    public static DownloadConfigImpl from(@NonNull Context context,
                                          @NonNull YoDownload.Config config) {
        Builder builder = builder(context)
                .defaultDownloadDir(config.defaultDownloadDir())
                .maxParallelTasks(config.maxParallelTasks())
                .maxBlocksPerTask(config.maxBlocksPerTask())
                .timeoutMs(config.connectTimeoutMs(), config.readTimeoutMs())
                .bufferSize(config.bufferSize())
                .maxRetryCount(config.maxRetryCount())
                .progressCallbackIntervalMs(config.progressCallbackIntervalMs());
        return builder.build();
    }

    @NonNull
    @Override
    public File defaultDownloadDir() {
        return defaultDownloadDir;
    }

    @Override
    public int maxParallelTasks() {
        return maxParallelTasks;
    }

    @Override
    public int maxBlocksPerTask() {
        return maxBlocksPerTask;
    }

    @Override
    public int connectTimeoutMs() {
        return connectTimeoutMs;
    }

    @Override
    public int readTimeoutMs() {
        return readTimeoutMs;
    }

    @Override
    public int bufferSize() {
        return bufferSize;
    }

    @Override
    public int maxRetryCount() {
        return maxRetryCount;
    }

    @Override
    public long progressCallbackIntervalMs() {
        return progressCallbackIntervalMs;
    }

    @NonNull
    public BreakpointStore breakpointStore() {
        return breakpointStore;
    }

    @NonNull
    public DownloadConnectionFactory connectionFactory() {
        return connectionFactory;
    }

    @NonNull
    public DownloadFileStrategy fileStrategy() {
        return fileStrategy;
    }

    @NonNull
    public Handler callbackHandler() {
        return callbackHandler;
    }

    @NonNull
    public Context context() {
        return appContext;
    }

    @NonNull
    public ExecutorService workerExecutor() {
        return workerExecutor;
    }

    public boolean workerExecutorOwned() {
        return ownsWorkerExecutor;
    }

    public static final class Builder implements YoDownload.Config.Builder {

        private final Context context;
        private File defaultDownloadDir;
        private int maxParallelTasks = 2;
        private int maxBlocksPerTask = 3;
        private int connectTimeoutMs = 15_000;
        private int readTimeoutMs = 15_000;
        private int bufferSize = 8 * 1024;
        private int maxRetryCount = 2;
        private long progressCallbackIntervalMs = 300L;
        private BreakpointStore breakpointStore;
        private DownloadConnectionFactory connectionFactory;
        private DownloadFileStrategy fileStrategy;
        private Handler callbackHandler;
        private ExecutorService workerExecutor;

        private Builder(@NonNull Context context) {
            this.context = context;
            File root = context.getExternalFilesDir("downloads");
            if (root == null) {
                root = new File(context.getFilesDir(), "downloads");
            }
            this.defaultDownloadDir = root;
        }

        @NonNull
        @Override
        public Builder defaultDownloadDir(@NonNull File defaultDownloadDir) {
            this.defaultDownloadDir = defaultDownloadDir;
            return this;
        }

        @NonNull
        @Override
        public Builder maxParallelTasks(int maxParallelTasks) {
            this.maxParallelTasks = maxParallelTasks;
            return this;
        }

        @NonNull
        @Override
        public Builder maxBlocksPerTask(int maxBlocksPerTask) {
            this.maxBlocksPerTask = maxBlocksPerTask;
            return this;
        }

        @NonNull
        @Override
        public Builder timeoutMs(int connectTimeoutMs, int readTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            this.readTimeoutMs = readTimeoutMs;
            return this;
        }

        @NonNull
        @Override
        public Builder bufferSize(int bufferSize) {
            this.bufferSize = bufferSize;
            return this;
        }

        @NonNull
        @Override
        public Builder maxRetryCount(int maxRetryCount) {
            this.maxRetryCount = maxRetryCount;
            return this;
        }

        @NonNull
        @Override
        public Builder progressCallbackIntervalMs(long progressCallbackIntervalMs) {
            this.progressCallbackIntervalMs = progressCallbackIntervalMs;
            return this;
        }

        @NonNull
        public Builder breakpointStore(@Nullable BreakpointStore breakpointStore) {
            this.breakpointStore = breakpointStore;
            return this;
        }

        @NonNull
        public Builder connectionFactory(@Nullable DownloadConnectionFactory connectionFactory) {
            this.connectionFactory = connectionFactory;
            return this;
        }

        @NonNull
        public Builder fileStrategy(@Nullable DownloadFileStrategy fileStrategy) {
            this.fileStrategy = fileStrategy;
            return this;
        }

        @NonNull
        public Builder callbackHandler(@Nullable Handler callbackHandler) {
            this.callbackHandler = callbackHandler;
            return this;
        }

        @NonNull
        public Builder workerExecutor(@Nullable ExecutorService workerExecutor) {
            this.workerExecutor = workerExecutor;
            return this;
        }

        @NonNull
        @Override
        public DownloadConfigImpl build() {
            if (breakpointStore == null) {
                breakpointStore = new SqliteBreakpointStore(context);
            }
            if (connectionFactory == null) {
                connectionFactory = new UrlConnectionFactory();
            }
            if (fileStrategy == null) {
                fileStrategy = new DownloadFileStrategy(defaultDownloadDir);
            }
            return new DownloadConfigImpl(this);
        }
    }
}
