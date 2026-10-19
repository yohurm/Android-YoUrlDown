package com.yohu.lib_download.internal.platform.connection;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.Locale;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description 基于 {@link HttpURLConnection} 的默认下载连接实现。
 */
public final class UrlConnectionDownloadConnection implements DownloadConnection {

    private final HttpURLConnection connection;
    private DownloadResponse response;

    UrlConnectionDownloadConnection(@NonNull String url,
                                    int connectTimeoutMs,
                                    int readTimeoutMs) throws IOException {
        connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "YoDownload/1.0");
    }

    @Override
    public void addHeader(@NonNull String key, @NonNull String value) {
        connection.setRequestProperty(key, value);
    }

    @Override
    public void addHeaders(@NonNull Map<String, String> headers) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            addHeader(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void setRange(long start, long end) {
        String value = end >= start
                ? String.format(Locale.US, "bytes=%d-%d", start, end)
                : String.format(Locale.US, "bytes=%d-", start);
        connection.setRequestProperty("Range", value);
    }

    @NonNull
    @Override
    public DownloadResponse connect() throws IOException {
        connection.connect();
        response = new DownloadResponse(
                connection.getResponseCode(),
                connection.getContentLengthLong(),
                connection.getHeaderField("ETag"),
                connection.getHeaderField("Last-Modified"),
                connection.getHeaderField("Accept-Ranges"),
                connection.getHeaderField("Content-Type"),
                connection.getHeaderField("Content-Disposition"));
        return response;
    }

    @NonNull
    @Override
    public InputStream inputStream() throws IOException {
        return connection.getInputStream();
    }

    @Override
    public void close() {
        connection.disconnect();
    }
}
