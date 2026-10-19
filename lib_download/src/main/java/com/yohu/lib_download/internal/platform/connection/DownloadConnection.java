package com.yohu.lib_download.internal.platform.connection;

import androidx.annotation.NonNull;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description 下载网络连接抽象。
 */
public interface DownloadConnection extends Closeable {

    void addHeader(@NonNull String key, @NonNull String value);

    void addHeaders(@NonNull Map<String, String> headers);

    void setRange(long start, long end);

    @NonNull
    DownloadResponse connect() throws IOException;

    @NonNull
    InputStream inputStream() throws IOException;
}
