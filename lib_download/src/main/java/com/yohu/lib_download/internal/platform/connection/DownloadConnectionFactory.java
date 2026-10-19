package com.yohu.lib_download.internal.platform.connection;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description 下载连接工厂，允许替换不同网络实现。
 */
public interface DownloadConnectionFactory {

    @NonNull
    DownloadConnection create(@NonNull String url,
                              int connectTimeoutMs,
                              int readTimeoutMs) throws IOException;
}
