package com.yohu.lib_download.internal.platform.connection;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description 默认 HttpURLConnection 连接工厂。
 */
public final class UrlConnectionFactory implements DownloadConnectionFactory {

    @NonNull
    @Override
    public DownloadConnection create(@NonNull String url,
                                     int connectTimeoutMs,
                                     int readTimeoutMs) throws IOException {
        return new UrlConnectionDownloadConnection(url, connectTimeoutMs, readTimeoutMs);
    }
}
