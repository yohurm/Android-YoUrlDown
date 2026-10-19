package com.yohu.lib_download.internal.util;

import android.text.TextUtils;
import android.webkit.URLUtil;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.yohu.lib_download.util.FileNames;

import java.io.File;
import java.util.Locale;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description 下载模块通用校验与文件命名工具。
 */
public final class DownloadUtil {

    private static final String TEMP_SUFFIX = ".download";

    private DownloadUtil() {
    }

    public static boolean isValidUrl(@Nullable String url) {
        return !TextUtils.isEmpty(url) && URLUtil.isNetworkUrl(url);
    }

    @NonNull
    public static File tempFile(@NonNull File destinationFile) {
        return new File(destinationFile.getAbsolutePath() + TEMP_SUFFIX);
    }

    @NonNull
    public static String safeFilename(@Nullable String value, @NonNull String fallback) {
        return FileNames.sanitize(value, fallback);
    }

    @NonNull
    public static String guessFilename(@NonNull String url,
                                       @Nullable String contentDisposition,
                                       @Nullable String mimeType) {
        String fromHeader = HeaderUtil.filenameFromContentDisposition(contentDisposition);
        String guessed = !TextUtils.isEmpty(fromHeader)
                ? fromHeader
                : URLUtil.guessFileName(url, contentDisposition, mimeType);
        if (TextUtils.isEmpty(guessed)) {
            guessed = String.format(Locale.US, "download_%d", System.currentTimeMillis());
        }
        return safeFilename(guessed, "download");
    }

    public static boolean isRangeResponse(int responseCode) {
        return responseCode == 206;
    }

    public static boolean isSuccessResponse(int responseCode) {
        return responseCode >= 200 && responseCode < 300;
    }
}
