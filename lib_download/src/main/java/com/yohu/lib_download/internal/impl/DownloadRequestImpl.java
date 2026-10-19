package com.yohu.lib_download.internal.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.yohu.lib_download.YoDownload;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link YoDownload.Request} 内部实现。
 */
public final class DownloadRequestImpl implements YoDownload.Request {

    private final String id;
    private final String url;
    private final File destinationDir;
    private final File destinationFile;
    private final String fileName;
    private final String title;
    private final String mimeType;
    private final Map<String, String> headers;
    private final YoDownload.Priority priority;
    private final YoDownload.NetworkType networkType;
    private final int blockCount;
    private final boolean autoCallbackToMainThread;
    private final boolean replaceExisting;
    private final boolean keepTempFileOnCancel;
    private final Object tag;

    private DownloadRequestImpl(@NonNull Builder builder) {
        this.id = builder.id;
        this.url = builder.url;
        this.destinationDir = builder.destinationDir;
        this.destinationFile = builder.destinationFile;
        this.fileName = builder.fileName;
        this.title = builder.title;
        this.mimeType = builder.mimeType;
        this.headers = Collections.unmodifiableMap(new HashMap<>(builder.headers));
        this.priority = builder.priority;
        this.networkType = builder.networkType;
        this.blockCount = Math.max(1, builder.blockCount);
        this.autoCallbackToMainThread = builder.autoCallbackToMainThread;
        this.replaceExisting = builder.replaceExisting;
        this.keepTempFileOnCancel = builder.keepTempFileOnCancel;
        this.tag = builder.tag;
    }

    @NonNull
    public static Builder builder(@NonNull String url) {
        return new Builder(url);
    }

    @Nullable
    @Override
    public String id() {
        return id;
    }

    @NonNull
    @Override
    public String url() {
        return url;
    }

    @Nullable
    @Override
    public File destinationDir() {
        return destinationDir;
    }

    @Nullable
    @Override
    public File destinationFile() {
        return destinationFile;
    }

    @Nullable
    @Override
    public String fileName() {
        return fileName;
    }

    @Nullable
    @Override
    public String title() {
        return title;
    }

    @Nullable
    @Override
    public String mimeType() {
        return mimeType;
    }

    @NonNull
    @Override
    public Map<String, String> headers() {
        return headers;
    }

    @NonNull
    @Override
    public YoDownload.Priority priority() {
        return priority;
    }

    @NonNull
    @Override
    public YoDownload.NetworkType networkType() {
        return networkType;
    }

    @Override
    public int blockCount() {
        return blockCount;
    }

    @Override
    public boolean autoCallbackToMainThread() {
        return autoCallbackToMainThread;
    }

    @Override
    public boolean replaceExisting() {
        return replaceExisting;
    }

    @Override
    public boolean keepTempFileOnCancel() {
        return keepTempFileOnCancel;
    }

    @Nullable
    @Override
    public Object tag() {
        return tag;
    }

    public static final class Builder implements YoDownload.Request.Builder {

        private final String url;
        private String id;
        private File destinationDir;
        private File destinationFile;
        private String fileName;
        private String title;
        private String mimeType;
        private final Map<String, String> headers = new HashMap<>();
        private YoDownload.Priority priority = YoDownload.Priority.NORMAL;
        private YoDownload.NetworkType networkType = YoDownload.NetworkType.ALL;
        private int blockCount = 1;
        private boolean autoCallbackToMainThread = true;
        private boolean replaceExisting;
        private boolean keepTempFileOnCancel = true;
        private Object tag;

        private Builder(@NonNull String url) {
            this.url = url;
        }

        @NonNull
        @Override
        public Builder id(@Nullable String id) {
            this.id = id;
            return this;
        }

        @NonNull
        @Override
        public Builder destinationDir(@Nullable File destinationDir) {
            this.destinationDir = destinationDir;
            return this;
        }

        @NonNull
        @Override
        public Builder destinationFile(@Nullable File destinationFile) {
            this.destinationFile = destinationFile;
            return this;
        }

        @NonNull
        @Override
        public Builder fileName(@Nullable String fileName) {
            this.fileName = fileName;
            return this;
        }

        @NonNull
        @Override
        public Builder title(@Nullable String title) {
            this.title = title;
            return this;
        }

        @NonNull
        @Override
        public Builder mimeType(@Nullable String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        @NonNull
        @Override
        public Builder header(@NonNull String key, @NonNull String value) {
            headers.put(key, value);
            return this;
        }

        @NonNull
        @Override
        public Builder headers(@Nullable Map<String, String> headers) {
            if (headers != null) {
                this.headers.putAll(headers);
            }
            return this;
        }

        @NonNull
        @Override
        public Builder priority(@NonNull YoDownload.Priority priority) {
            this.priority = priority;
            return this;
        }

        @NonNull
        @Override
        public Builder networkType(@NonNull YoDownload.NetworkType networkType) {
            this.networkType = networkType;
            return this;
        }

        @NonNull
        @Override
        public Builder blockCount(int blockCount) {
            this.blockCount = blockCount;
            return this;
        }

        @NonNull
        @Override
        public Builder autoCallbackToMainThread(boolean autoCallbackToMainThread) {
            this.autoCallbackToMainThread = autoCallbackToMainThread;
            return this;
        }

        @NonNull
        @Override
        public Builder replaceExisting(boolean replaceExisting) {
            this.replaceExisting = replaceExisting;
            return this;
        }

        @NonNull
        @Override
        public Builder keepTempFileOnCancel(boolean keepTempFileOnCancel) {
            this.keepTempFileOnCancel = keepTempFileOnCancel;
            return this;
        }

        @NonNull
        @Override
        public Builder tag(@Nullable Object tag) {
            this.tag = tag;
            return this;
        }

        @NonNull
        @Override
        public DownloadRequestImpl build() {
            return new DownloadRequestImpl(this);
        }
    }
}
