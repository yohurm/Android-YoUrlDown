package com.yohu.lib_urlparse.core;

import com.yohu.lib_urlparse.util.UrlUtils;

/**
 * 输入归一化：把"用户粘来的一段分享文本"变成可用的 URL。
 *
 * <p>只做无网络的一步。短链展开需要发请求，属于通道自己的职责（不同平台的短链域名不同）。
 */
public final class RequestResolver {

    private RequestResolver() {
    }

    /**
     * 从原始输入里取 URL。
     *
     * @return 首个 http(s) URL；没有则 {@code null}
     */
    public static String extractUrl(String rawInput) {
        if (rawInput == null) {
            return null;
        }
        String url = UrlUtils.findUrlStrict(rawInput);
        if (url == null) {
            url = UrlUtils.extractFirstUrl(rawInput);
        }
        return url;
    }

    /**
     * 输入是否像是"裸 ID"（纯数字作品号）。
     *
     * <p>裸 ID 本身不足以定位作品——不同平台对同一个 ID 的解释不同，所以这里只做标记，
     * 由通道决定要不要接受（例如平台提示已明确时才接受）。
     */
    public static boolean looksLikeBareId(String rawInput) {
        return UrlUtils.looksLikeBareId(rawInput);
    }
}
