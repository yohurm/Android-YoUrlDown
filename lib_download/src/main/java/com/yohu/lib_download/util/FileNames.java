package com.yohu.lib_download.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 文件/条目名净化工具。统一处理路径分隔符与平台保留字符，避免调用方拼接出越界路径。
 */
public final class FileNames {

    private FileNames() {
    }

    /**
     * 将任意字符串净化成单段文件名（不含路径分隔符）。
     *
     * @return 净化后的名称；空结果时返回 fallback。
     */
    @NonNull
    public static String sanitize(@Nullable String value, @NonNull String fallback) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty()) {
            return fallback;
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '\\' || c == '/' || c == ':' || c == '*'
                    || c == '?' || c == '"' || c == '<' || c == '>' || c == '|'
                    || c == '\0') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String result = sb.toString();
        if (result.isEmpty() || ".".equals(result) || "..".equals(result)) {
            return fallback;
        }
        return result;
    }
}
