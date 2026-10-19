package com.yohu.lib_download.internal.util;

import androidx.annotation.Nullable;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Locale;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description HTTP 头解析工具。
 */
public final class HeaderUtil {

    private HeaderUtil() {
    }

    public static boolean acceptRanges(@Nullable String value) {
        return value != null && "bytes".equalsIgnoreCase(value.trim());
    }

    @Nullable
    public static String filenameFromContentDisposition(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.split(";");
        for (String part : parts) {
            String trimmed = part.trim();
            String lower = trimmed.toLowerCase(Locale.US);
            if (lower.startsWith("filename*=")) {
                int index = trimmed.indexOf("''");
                String encoded = index >= 0
                        ? trimmed.substring(index + 2)
                        : trimmed.substring(10);
                String filename = decodeRfc5987(encoded);
                return stripQuotes(filename);
            }
            if (lower.startsWith("filename=")) {
                return stripQuotes(trimmed.substring(9));
            }
        }
        return null;
    }

    @Nullable
    private static String decodeRfc5987(@Nullable String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        try {
            return URLDecoder.decode(encoded, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return encoded;
        }
    }

    private static String stripQuotes(String value) {
        String result = value.trim();
        if (result.length() >= 2 && result.startsWith("\"") && result.endsWith("\"")) {
            result = result.substring(1, result.length() - 1);
        }
        return result.isEmpty() ? null : result;
    }
}
