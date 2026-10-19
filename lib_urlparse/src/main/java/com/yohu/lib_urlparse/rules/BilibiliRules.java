package com.yohu.lib_urlparse.rules;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B 站通道的常量。
 *
 * <p><b>溯源分级（重要，不要混为一谈）</b>：
 * <ul>
 *   <li><b>【观测】来自样本</b>：{@link #VIEW_API} —— DEX 字符串池里的原始字面量
 *       {@code https://api.bilibili.com/x/web-interface/view?bvid=}
 *       （证据：{@code 07_native/recovered_endpoints.json} L1455、
 *       {@code 09_reports/11_平台与功能清单.md} L26、{@code 16_解析规则全集.md} §326
 *       把 B 站标为「直接 API … 部分（公开 API）」）。也就是说**样本自己就是直连公开 API**，
 *       不是走私有服务——这是它与抖音/视频号最大的不同。</li>
 *   <li><b>【本工程新增】</b>：{@link #PLAYURL_API}、{@link #HTML5_PLAYURL_API}、
 *       {@link #REFERER}、WBI 相关端点等。样本只留下了 view 接口，
 *       "怎么把 cid 变成可下载地址"没有留在可观测证据里，因此这部分是**我们自己的实现选择**，
 *       不属于"还原"。之所以仍然用它，是因为它完全基于**公开接口**，不涉及任何私有凭据。</li>
 * </ul>
 */
public final class BilibiliRules {

    private BilibiliRules() {
    }

    // ---------------------------------------------------------------- 端点

    /** 【观测】样本 DEX 字符串池里的原始字面量。 */
    public static final String VIEW_API = "https://api.bilibili.com/x/web-interface/view?bvid=";

    /** 【本工程新增】DASH 播放地址接口（分离音视频轨，质量更高）。 */
    public static final String PLAYURL_API = "https://api.bilibili.com/x/player/playurl";

    /** 【本工程新增】html5 渐进式接口：拿单一 mp4，匿名最稳。 */
    public static final String HTML5_PLAYURL_API =
            "https://api.bilibili.com/x/player/playurl?platform=html5&high_quality=1";

    /** 【本工程新增】取 WBI 密钥的导航接口（密钥来自公开响应，无需私有凭据）。 */
    public static final String NAV_API = "https://api.bilibili.com/x/web-interface/nav";

    // ---------------------------------------------------------------- 主机与请求头

    /** 可识别的站点主机（短链 b23.tv 需要先展开）。 */
    public static final List<String> HOSTS = Collections.unmodifiableList(Arrays.asList(
            "bilibili.com", "b23.tv", "bili2233.cn", "acg.tv"
    ));

    /**
     * 【本工程新增】CDN 会校验 Referer：不带 B 站来源的请求会被 403。
     * 因此不仅 API 请求要带，**下载媒体时也必须带**（见
     * {@code util/DownloadHeaders}）。
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

    /** 动态/图集 ID（opus 或 dynamic）。 */
    public static final String REGEX_OPUS_ID = "(?:opus|dynamic)/(\\d+)";

    public static final Pattern PATTERN_BVID = Pattern.compile(REGEX_BVID);
    public static final Pattern PATTERN_AVID = Pattern.compile(REGEX_AVID, Pattern.CASE_INSENSITIVE);
    public static final Pattern PATTERN_OPUS_ID = Pattern.compile(REGEX_OPUS_ID);

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

    /** 从动态/图集链接里取 ID；取不到返回 null。 */
    public static String extractOpusId(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = PATTERN_OPUS_ID.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
