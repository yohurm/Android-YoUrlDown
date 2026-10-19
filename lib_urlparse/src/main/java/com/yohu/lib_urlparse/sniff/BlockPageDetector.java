package com.yohu.lib_urlparse.sniff;

import java.util.Locale;

/**
 * 识别"站点反爬/错误页"，避免把**拦截页当成作品内容**。
 *
 * <p>为什么需要它：真机实测到过一次假阳性——设备被系统冻结期间，抖音页面实际拿到的是
 * ByteDance 的拦截页（标题「網頁無法使用」），而通用嗅探仍从该页里收了 7 条图片，
 * 于是矩阵把它记成"解析通过(7 条)"。那 7 条**不是作品**，是错误页上的静态资源。
 *
 * <p>这类假阳性的危害比"解析失败"大得多：失败会暴露，假通过会污染结论。
 * 因此这里做**白名单式**判定：只有标题明确是拦截/错误页、且没有任何可信媒体信号时才拒绝。
 *
 * <p>判定只用标题与少量标志串，不做模糊猜测：宁可漏判（当成普通页继续走），
 * 也不误杀正常作品页。
 */
public final class BlockPageDetector {

    private BlockPageDetector() {
    }

    /** 已知的拦截/错误页标题片段（简体 + 繁体 + 英文）。 */
    private static final String[] TITLE_MARKERS = {
            "網頁無法使用", "网页无法使用",          // ByteDance 安全 SDK
            "访问异常", "訪問異常",
            "安全验证", "安全驗證", "请完成验证", "請完成驗證",
            "人机验证", "人機驗證",
            "captcha", "verify you are human",
            "access denied", "forbidden",
            "页面不存在", "頁面不存在", "404 not found",
    };

    /**
     * 标题看起来是不是拦截/错误页。
     *
     * @param title 页面标题（可为 null）
     * @return true 表示应拒绝该结果
     */
    public static boolean isBlockTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            return false;
        }
        String t = title.toLowerCase(Locale.ROOT);
        for (String marker : TITLE_MARKERS) {
            if (t.contains(marker.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 综合判断：标题是拦截页**且**没有任何视频信号时，才判为拦截页。
     *
     * <p>为什么还要看"有无视频信号"：有些正常作品页标题里恰好带 "access denied" 之类的词
     * （比如吐槽类内容），但只要它真有 {@code <video>}/mp4 请求，就不该被拒。
     *
     * @param title          页面标题
     * @param hasVideoSignal 页面里是否观测到视频类请求/元素
     */
    public static boolean isBlockPage(String title, boolean hasVideoSignal) {
        return !hasVideoSignal && isBlockTitle(title);
    }
}
