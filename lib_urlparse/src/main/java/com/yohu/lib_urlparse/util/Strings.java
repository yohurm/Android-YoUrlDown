package com.yohu.lib_urlparse.util;

/**
 * 字符串小工具。零依赖、零 IO。
 *
 * <p>存在的理由：这个库里到处要对"用户粘来的分享文本"做裁剪与判定，
 * 每处各写一遍 {@code s == null || s.trim().isEmpty()} 既啰嗦又容易漏判 {@code null}。
 */
public final class Strings {

    private Strings() {
    }

    /** {@code null} 视为空串。 */
    public static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 去掉首尾空白；{@code null} 返回空串。 */
    public static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** 是否为空或全空白。 */
    public static boolean isBlank(String s) {
        if (s == null) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 是否非空。 */
    public static boolean isNotBlank(String s) {
        return !isBlank(s);
    }

    /** 把多行压成一行（用于日志与摘要行）。 */
    public static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\n', ' ').replace('\r', ' ');
    }

    /** 截断并加省略号；{@code max <= 0} 返回空串。 */
    public static String truncate(String s, int max) {
        if (s == null || max <= 0) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 取第一个非空值；全空返回 {@code fallback}。 */
    public static String firstNotBlank(String fallback, String... values) {
        if (values != null) {
            for (String v : values) {
                if (isNotBlank(v)) {
                    return v;
                }
            }
        }
        return fallback;
    }

    /** 安全的 {@code substring}：越界不抛异常。 */
    public static String safeSubstring(String s, int start, int end) {
        if (s == null) {
            return "";
        }
        int from = Math.max(0, Math.min(start, s.length()));
        int to = Math.max(from, Math.min(end, s.length()));
        return s.substring(from, to);
    }
}
