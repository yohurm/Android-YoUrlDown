package com.yohu.lib_urlparse.rules;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 抖音通道的规则：域名判定、作品 ID 抽取、页面内解析脚本的模板与等待时长。
 *
 * <p>只含行为规则（正则、域名、脚本模板），不含任何凭据。
 */
public final class DouyinRules {

    private DouyinRules() {
    }

    // ---------------------------------------------------------------- 主机判定

    private static final String DOUYIN_COM = "douyin.com";
    private static final String DOT_DOUYIN_COM = ".douyin.com";

    /**
     * 短链标记。用 {@code contains} 而不是取主机后等值比较：
     * 分享文本里的短链常带端口、多级子域或不完整的协议头，包含判断更稳。
     */
    private static final List<String> SHORT_LINK_MARKERS = Collections.unmodifiableList(Arrays.asList(
            "v.douyin.com",
            "iesdouyin.com"
    ));

    // ---------------------------------------------------------------- 作品 ID

    /** 路径里的作品 ID：{@code /video/<id>}、{@code /note/<id>}、{@code /share/video/<id>}、{@code /share/slides/<id>}。 */
    public static final Pattern PATTERN_AWEME_ID_PATH =
            Pattern.compile("/(?:video|note|share/video|share/slides)/(\\d+)");

    /** 查询参数里的作品 ID：{@code aweme_id=<id>}。 */
    public static final Pattern PATTERN_AWEME_ID_QUERY = Pattern.compile("[?&]aweme_id=(\\d+)");

    // ---------------------------------------------------------------- 页面内解析脚本

    /**
     * 注入脚本的前半段。完整脚本 = 本段 + {@code JSONObject.quote(作品地址)} + {@link #SCRIPT_BOOTSTRAP_SUFFIX}，
     * 结果通过 {@code DouyinBridge.postMessage} 回传。
     */
    public static final String SCRIPT_BOOTSTRAP_PREFIX =
            ";(function(){function report(r){try{DouyinBridge.postMessage(typeof r==='string'?r:JSON.stringify(r))}catch(e){}}try{DouyinParser.parse(";

    /** 注入脚本的后半段：成功或异常都回传一个 JSON。 */
    public static final String SCRIPT_BOOTSTRAP_SUFFIX =
            ").then(report).catch(function(e){report({code:500,message:'JS error: '+e,data:null})})}catch(e){report({code:500,message:'JS error: '+e,data:null})}})();";

    /** 等待脚本回传结果的上限。 */
    public static final long JS_RESULT_TIMEOUT_MS = 15_000L;

    // ---------------------------------------------------------------- 判定

    /** 主机是否为抖音域：{@code douyin.com} 或其子域。 */
    public static boolean isDouyinHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        return DOUYIN_COM.equals(h) || h.endsWith(DOT_DOUYIN_COM);
    }

    /** 是否为抖音短链（需要先跟随重定向展开）。 */
    public static boolean containsShortLinkMarker(String urlOrHost) {
        if (urlOrHost == null) {
            return false;
        }
        String s = urlOrHost.toLowerCase(Locale.ROOT);
        for (String m : SHORT_LINK_MARKERS) {
            if (s.contains(m)) {
                return true;
            }
        }
        return false;
    }
}
