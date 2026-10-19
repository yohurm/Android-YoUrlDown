package com.yohu.lib_urlparse.sniff;

import java.util.Locale;

/**
 * 「图集模式」下，哪些被黑名单拒掉的地址应当**额外**作为图片候选收下。
 *
 * <p>抽成纯逻辑是为了能单测：真正的判断发生在 Android 的 WebView 回调里，
 * 那里没法在没有设备的情况下验证。而这段判断恰恰是最容易"越界"的地方——
 * 稍不注意就会把已还原的"只找视频流"语义改掉。
 *
 * <p><b>边界（刻意保守）</b>：只有同时满足以下三条才收：
 * <ol>
 *   <li>宿主**显式**打开了图集模式；</li>
 *   <li>该地址是被**黑名单**拒的（{@code filtered}）——即"已还原规则明确排除"的那一类；
 *       其它拒绝理由（缺 Content-Type、排除前缀等）不在这里处理，
 *       它们走原有的探测/分类路径；</li>
 *   <li>它确实是图片：有 {@code image/*} 的 Content-Type 证据，或 URL 形态像图片。</li>
 * </ol>
 */
public final class GalleryImagePolicy {

    private GalleryImagePolicy() {
    }

    /** 图集模式下由本策略收下的候选，其 {@code reason} 用这个固定前缀，便于日志分辨来源。 */
    public static final String REASON_PREFIX = "gallery image: ";

    /**
     * 是否应当把该地址当作图片候选收下。
     *
     * @param url            原始地址
     * @param contentType    已知的 Content-Type（可能为 null）
     * @param decisionReason 已还原规则给出的拒绝理由
     * @param galleryMode    宿主是否显式打开图集模式
     */
    public static boolean shouldCollect(String url, String contentType, String decisionReason,
                                       boolean galleryMode) {
        if (!galleryMode || url == null) {
            return false;
        }
        if (!MediaUrlSniffer.MSG_FILTERED.equals(decisionReason)) {
            return false;
        }
        // 站点自己的界面资源不是"作品内容"。
        // 实测踩到：公众号文章页会被收下一个 res.wx.qq.com/a/wx_fed/assets/res/*.png
        // 的微信前端图标，于是"解析通过(1 条)"——但那个文件与文章内容毫无关系。
        // 这类**假阳性**比"什么都没找到"更糟：它会让统计与用户都以为平台已经支持。
        if (looksLikeUiAsset(url)) {
            return false;
        }
        String mime = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).trim();
        if (mime.startsWith("image/")) {
            return true;
        }
        return MediaExtensions.looksLikeImage(url);
    }

    /** 站点界面资源（图标/样式/脚本/打包产物）的路径与主机特征。 */
    private static final String[] UI_PATH_MARKERS = {
            "/assets/", "/static/", "/rsrc.php", "/dist/", "/build/",
            "/chunk", "/vendor", "/polyfill", "/webpack", "favicon", "/sprite",
    };

    private static final String[] UI_HOST_MARKERS = {
            "res.wx.qq.com", "static.", "fe-static.", "assets.",
    };

    /**
     * 该地址看起来是站点界面资源而非作品内容吗？
     *
     * <p>判据刻意保守：只在命中明确的资源目录/主机前缀时才排除，
     * 以免把真正的内容图（例如 `mmbiz.qpic.cn`、`scontent.*.cdninstagram.com`）一起误杀。
     */
    public static boolean looksLikeUiAsset(String url) {
        if (url == null) {
            return false;
        }
        String u = url.toLowerCase(Locale.ROOT);
        String path = MediaExtensions.pathOf(u);
        for (String m : UI_PATH_MARKERS) {
            if (path.contains(m)) {
                return true;
            }
        }
        String host = hostOf(u);
        for (String m : UI_HOST_MARKERS) {
            if (host.startsWith(m) || host.equals(m)) {
                return true;
            }
        }
        return false;
    }

    private static String hostOf(String url) {
        try {
            int s = url.indexOf("://");
            if (s < 0) {
                return "";
            }
            int e = url.indexOf('/', s + 3);
            String h = e < 0 ? url.substring(s + 3) : url.substring(s + 3, e);
            int colon = h.indexOf(':');
            return colon < 0 ? h : h.substring(0, colon);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 收下时用于记录的理由（含判据，便于事后追溯为什么它会出现在结果里）。 */
    public static String reason(String url, String contentType) {
        boolean byMime = contentType != null
                && contentType.toLowerCase(Locale.ROOT).trim().startsWith("image/");
        return REASON_PREFIX + (byMime ? "content-type" : "url-shape");
    }

    /** 由 URL 猜图片 MIME（拿不到 Content-Type 时用；猜不出返回 null）。 */
    public static String guessMime(String url) {
        String p = MediaExtensions.pathOf(url);
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (p.endsWith(".png")) {
            return "image/png";
        }
        if (p.endsWith(".webp")) {
            return "image/webp";
        }
        if (p.endsWith(".gif")) {
            return "image/gif";
        }
        if (p.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (p.endsWith(".heic")) {
            return "image/heic";
        }
        if (p.endsWith(".avif")) {
            return "image/avif";
        }
        return null;
    }
}
