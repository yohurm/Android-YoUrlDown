package com.yohu.lib_urlparse.rules;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B 站通道的规则：公开接口地址、主机、请求头与 BV / av 号抽取。
 *
 * <p>只用公开接口（view 取 cid → playurl 取播放地址），不涉及任何私有凭据。
 */
public final class BilibiliRules {

    private BilibiliRules() {
    }

    // ---------------------------------------------------------------- 端点

    /** 作品详情（含 cid、标题、作者、封面）。 */
    public static final String VIEW_API = "https://api.bilibili.com/x/web-interface/view?bvid=";

    /** DASH 播放地址接口（分离音视频轨，质量更高）。 */
    public static final String PLAYURL_API = "https://api.bilibili.com/x/player/playurl";

    /** html5 渐进式接口：拿单一 mp4，匿名最稳。 */
    public static final String HTML5_PLAYURL_API =
            "https://api.bilibili.com/x/player/playurl?platform=html5&high_quality=1";

    // ---------------------------------------------------------------- 主机与请求头

    /** 可识别的站点主机（短链 b23.tv 需要先展开）。 */
    public static final List<String> HOSTS = Collections.unmodifiableList(Arrays.asList(
            "bilibili.com", "b23.tv", "bili2233.cn", "acg.tv"
    ));

    /**
     * CDN 会校验 Referer：不带 B 站来源的请求会被 403。
     * 因此不仅 API 请求要带，下载媒体时也必须带（放进 {@code MediaAsset} 的 headers）。
     */
    public static final String REFERER = "https://www.bilibili.com";

    /** 浏览器 UA：B 站对非浏览器 UA 会降级或拒绝。 */
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36";

    // ---------------------------------------------------------------- 抽取规则

    /** BV 号：{@code BV} + 10 位 base58 字符。 */
    public static final String REGEX_BVID = "BV[0-9A-Za-z]{10}";

    /** av 号：{@code av} + 数字。 */
    public static final String REGEX_AVID = "av(\\d+)";

    public static final Pattern PATTERN_BVID = Pattern.compile(REGEX_BVID);
    public static final Pattern PATTERN_AVID = Pattern.compile(REGEX_AVID, Pattern.CASE_INSENSITIVE);

    /** 判定主机是否属于 B 站。 */
    public static boolean isBilibiliHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        for (String s : HOSTS) {
            if (h.equals(s) || h.endsWith("." + s)) {
                return true;
            }
        }
        return false;
    }

    /** 是不是需要先展开的短链（b23.tv）。 */
    public static boolean isShortLinkHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        return h.equals("b23.tv") || h.endsWith(".b23.tv");
    }

    /** 从任意文本里取 BV 号；取不到返回 null。 */
    public static String extractBvid(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = PATTERN_BVID.matcher(text);
        return m.find() ? m.group() : null;
    }

    /** 从任意文本里取 av 号（返回纯数字）；取不到返回 null。 */
    public static String extractAid(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = PATTERN_AVID.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
