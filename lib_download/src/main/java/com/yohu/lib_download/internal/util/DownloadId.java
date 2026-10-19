package com.yohu.lib_download.internal.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description 生成稳定下载任务 ID。显式 ID 优先；未指定时使用 URL + 目标文件绝对路径
 * 生成 SHA-256 摘要，保证重启后仍可定位同一任务。
 */
public final class DownloadId {

    private DownloadId() {
    }

    @NonNull
    public static String create(@Nullable String explicitId,
                                @NonNull String url,
                                @NonNull File destinationFile) {
        if (explicitId != null && !explicitId.trim().isEmpty()) {
            return explicitId.trim();
        }
        return sha256(url + "\n" + destinationFile.getAbsolutePath());
    }

    @NonNull
    private static String sha256(@NonNull String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
