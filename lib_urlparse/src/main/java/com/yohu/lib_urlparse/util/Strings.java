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
}
