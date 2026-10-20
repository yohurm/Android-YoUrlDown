package com.yohu.lib_urlparse.rules;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微博通道的常量与抽取规则。
 *
 * <p><b>参考实现</b>：端点、JSON 字段路径与访客 cookie 流程对齐公开维护的
 * yt-dlp {@code yt_dlp/extractor/weibo.py}。不涉及任何私有凭据——
 * 访客 cookie 由微博自己下发给未登录访客。
 *
 * <p><b>为什么不能靠嗅探</b>：实测（2026-10 真机）微信/快手这类 H5 分享页根本不在页面里
 * 放媒体地址；微博同理——正文媒体地址在 {@code ajax/statuses/show} 的 JSON 里，
 * 只有直连接口才能拿到。
 */
public final class WeiboRules {

    private WeiboRules() {
    }

    // ---------------------------------------------------------------- 端点

    /** 状态详情 JSON 接口（yt-dlp `WeiboIE._real_extract` 使用的端点）。 */
    public static final String STATUS_API = "https://weibo.com/ajax/statuses/show";

    /** 访客 cookie 流程端点（未登录访客也能过，属于站点公开行为）。 */
    public static final String VISITOR_GENVISITOR = "https://passport.weibo.com/visitor/genvisitor";
    public static final String VISITOR_VISITOR = "https://passport.weibo.com/visitor/visitor";

    public static final String REFERER = "https://weibo.com/";

    /** 移动 UA：m.weibo.cn 与 weibo.com 的 ajax 接口都接受浏览器 UA。 */
    public static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; NEPI4E0106) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36";

    // ---------------------------------------------------------------- URL 形态

    /**
     * 支持的链接形态（对齐 yt-dlp 的 {@code _VALID_URL}）：
     * <ul>
     *   <li>{@code https://m.weibo.cn/status/<id>} / {@code /detail/<id>}</li>
     *   <li>{@code https://weibo.com/<uid>/<mblogid>}</li>
     *   <li>{@code https://weibo.com/tv/show/<id>}（需走 component 接口 → 本实现只取 mid 后转 status 接口）</li>
     *   <li>{@code https://video.weibo.com/show?fid=<id>}</li>
     * </ul>
     */
    public static final String REGEX_MOBILE = "m\\.weibo\\.cn/(?:status|detail)/([A-Za-z0-9]+)";
    public static final String REGEX_DESKTOP = "weibo\\.com/(?:\\d+|u/\\d+)/([A-Za-z0-9]+)";
    public static final String REGEX_TV_SHOW = "weibo\\.com/tv/show/(\\d+):([0-9a-fA-F]{32}|\\d{16,})";
    public static final String REGEX_VIDEO_FID = "video\\.weibo\\.com/show/?\\?(?:[^#]*&)?fid=(\\d+):([0-9a-fA-F]{32}|\\d{16,})";

    public static final Pattern PATTERN_MOBILE =
            Pattern.compile(REGEX_MOBILE, Pattern.CASE_INSENSITIVE);
    public static final Pattern PATTERN_DESKTOP =
            Pattern.compile(REGEX_DESKTOP, Pattern.CASE_INSENSITIVE);
    public static final Pattern PATTERN_TV_SHOW =
            Pattern.compile(REGEX_TV_SHOW, Pattern.CASE_INSENSITIVE);
    public static final Pattern PATTERN_VIDEO_FID =
            Pattern.compile(REGEX_VIDEO_FID, Pattern.CASE_INSENSITIVE);

    /** 是否属于微博（用于 canHandle）。 */
    public static boolean isWeiboHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        return h.equals("weibo.com") || h.endsWith(".weibo.com")
                || h.equals("weibo.cn") || h.endsWith(".weibo.cn")
                || h.equals("t.cn") || h.endsWith(".t.cn");
    }

    /**
     * 从链接里取状态标识。
     *
     * <p>注意：{@code weibo.com/<uid>/<mblogid>} 里的 {@code mblogid} 与数字 {@code mid} **不是一回事**
     * （前者是 base62 短码）。{@code ajax/statuses/show} 两种都接受，所以这里原样返回，
     * 不做（也没有依据做）进制换算。
     *
     * @return 状态标识；取不到返回 null
     */
    public static String extractStatusId(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = PATTERN_MOBILE.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        m = PATTERN_DESKTOP.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * 是否属于"tv/show"或"video.weibo.com/show?fid="形态。
     * 这类链接给的是 oid（形如 {@code 1034:xxxx}），需要先经 component 接口换成 mid。
     */
    public static String extractTvOid(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = PATTERN_TV_SHOW.matcher(url);
        if (m.find()) {
            return m.group(1) + ":" + m.group(2);
        }
        m = PATTERN_VIDEO_FID.matcher(url);
        if (m.find()) {
            return m.group(1) + ":" + m.group(2);
        }
        return null;
    }

    /** tv/show 的 component 接口（yt-dlp `WeiboVideoIE` 使用）。 */
    public static final String TV_COMPONENT_API = "https://weibo.com/tv/api/component";

    /** component 请求体模板：{@code data={"Component_Play_Playinfo":{"oid":"<oid>"}}}。 */
    public static String tvComponentBody(String oid) {
        return "data={\"Component_Play_Playinfo\":{\"oid\":\"" + oid + "\"}}";
    }

    // ---------------------------------------------------------------- 字段路径（对齐 yt-dlp）

    /**
     * 降级判据：{@code media_info} 里形如 {@code label=xxx&template=1280x720} 的地址。
     * yt-dlp 用它作为 playback_list 缺失时的兜底（其注释为 "fallback, should be barely used"）。
     */
    public static final String LABEL_MARKER = "label=";

    public static final Pattern PATTERN_LABEL_TEMPLATE =
            Pattern.compile("label=(\\w+)&template=(\\d+x\\d+)");
}
