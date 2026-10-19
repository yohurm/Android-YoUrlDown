package com.yohu.lib_urlparse.rules;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 抖音通道的规则常量。
 *
 * <p><b>出处</b>：全部取自对被分析样本 {@code com.one.downloadtools.helper.DouyinWebParser}
 * 的 VMP 字节码反汇编（方法块地址见括号），证据链见
 * {@code base_apk_full_analysis/09_reports/18_VMP字节码反汇编.md} §18.8 与
 * {@code 10_native_arm64/vm/resolve_table_spec.md}。每条都标注了来源常量池序号，
 * 便于回溯核对。
 *
 * <p>这些是**行为规则**（正则、域名判定、脚本模板），不含任何私有凭据；
 * 但其中的远端脚本地址属于第三方资产，默认不启用，需要时由调用方在
 * {@link #PARSER_JS_URL} 的基础上自行替换。
 */
public final class DouyinRules {

    private DouyinRules() {
    }

    // ---------------------------------------------------------------- 主机判定
    /**
     * 抖音主域（证据：{@code extractAwemeId} 之外的 {@code isDouyinUrl} 使用
     * {@code ".douyin.com"} 字面量，常量池 T2[83] → 段内序号 60）。
     */
    public static final String DOT_DOUYIN_COM = ".douyin.com";

    /** 裸域（不含前导点），对应样本中 {@code host == "douyin.com"} 的比较。 */
    public static final String DOUYIN_COM = "douyin.com";

    /**
     * 短链判定用的字面量（证据：{@code resolveShortLinkIfNeeded} 块 0x1f0fb0 / 0x1f0fca）。
     *
     * <p><b>比较方式是 {@code contains}，不是主机等值匹配</b>——样本直接对字符串做包含判断，
     * 因此这里保留原始语义（{@link #containsShortLinkMarker(String)}），
     * 而不是"取 host 再等值比较"，后者会在带端口/多级子域时产生不同结果。
     */
    public static final List<String> SHORT_LINK_MARKERS = Collections.unmodifiableList(Arrays.asList(
            "v.douyin.com",
            "iesdouyin.com"
    ));

    /** 抖音主页（证据：{@code loadHome} 的常量池串 198 → {@code LUT3[198]}）。 */
    public static final String HOME_URL = "https://www.douyin.com/";

    // ---------------------------------------------------------------- 抽取规则
    /**
     * 作品 ID 抽取规则 1（证据：{@code extractAwemeId} 块 0x1f132c，常量池 T2[61]）。
     * 匹配 {@code /video/&lt;id&gt;}、{@code /note/&lt;id&gt;}、{@code /share/video/&lt;id&gt;}、
     * {@code /share/slides/&lt;id&gt;}。
     */
    public static final String REGEX_AWEME_ID_PATH =
            "/(?:video|note|share/video|share/slides)/(\\d+)";

    /**
     * 作品 ID 抽取规则 2（证据：同一方法的后半段，常量池 T2[122]）。
     * 匹配查询参数 {@code aweme_id=}。
     */
    public static final String REGEX_AWEME_ID_QUERY = "[?&]aweme_id=(\\d+)";

    /**
     * 纯数字 ID 判定（证据：{@code extractUrl} 块 0x1f13e6，常量池 T2[125]）。
     * 用于识别"输入本身已经是作品 ID"的情形。
     */
    public static final String REGEX_BARE_ID = "^\\d{15,25}$";

    /**
     * 从整段分享文本中抽取首个链接（证据：{@code extractUrl} 块 0x1f13e6，常量池 T2[199]，
     * 以 {@code RegexOption.IGNORE_CASE} 构造，取首个匹配后 trimEnd 掉若干字符）。
     */
    public static final String REGEX_FIRST_URL = "https?://[^\\s<>\"'()]+";

    /**
     * 更严格的链接抽取规则（证据：{@code QsyUrlParse_trans#findUrlByStr} 块 0x307c32）。
     *
     * <p>与 {@link #REGEX_FIRST_URL} 的区别：本规则显式限定首尾字符集，因此不会把
     * 结尾的标点吞进 URL。样本里两条规则服务于不同入口。
     */
    public static final String REGEX_FIND_URL_STR =
            "https?://[-A-Za-z0-9+&@#/%?=~_|!:,.;]+[-A-Za-z0-9+&@#/%=~_|]";

    /**
     * {@code resolveAwemeId} 使用的 ID 规则（证据：块 0x1f18e2）。
     *
     * <p><b>注意与 {@link #REGEX_AWEME_ID_PATH} 的区别</b>：本规则要求 ID 为
     * **15–25 位**（{@code \d{15,25}}），而后者只要求任意长度数字（{@code \d+}）。
     * 两条规则来自不同方法，不可互相替代。
     */
    public static final String REGEX_AWEME_ID_PATH_STRICT =
            "/(?:video|note|share/video|share/slides)/(\\d{15,25})";

    /** 抽取后需要从末尾剔除的字符集合（证据：{@code extractUrl} 末尾的 {@code trimEnd(char[])}，常量池 T2[467] 为 {@code [C}）。 */
    public static final char[] TRIM_END_CHARS = new char[]{')', ']', '}', ',', '.', '"', '\'', '。', '，', '、'};

    /** 已编译的规则，避免每次解析重复编译。 */
    public static final Pattern PATTERN_AWEME_ID_PATH = Pattern.compile(REGEX_AWEME_ID_PATH);

    public static final Pattern PATTERN_AWEME_ID_QUERY = Pattern.compile(REGEX_AWEME_ID_QUERY);

    public static final Pattern PATTERN_BARE_ID = Pattern.compile(REGEX_BARE_ID);

    public static final Pattern PATTERN_FIRST_URL =
            Pattern.compile(REGEX_FIRST_URL, Pattern.CASE_INSENSITIVE);

    /** {@code findUrlByStr} 的严格链接规则。 */
    public static final Pattern PATTERN_FIND_URL_STR = Pattern.compile(REGEX_FIND_URL_STR);

    /** {@code resolveAwemeId} 的 15–25 位 ID 规则。 */
    public static final Pattern PATTERN_AWEME_ID_PATH_STRICT =
            Pattern.compile(REGEX_AWEME_ID_PATH_STRICT);

    // ---------------------------------------------------------------- 脚本模板
    /**
     * 远端解析脚本地址（证据：常量池 T2[1337] = {@code https://vp.java68.cn/js/douyin-parser.js}）。
     *
     * <p><b>注意</b>：这是样本作者自有的服务，本实现**不默认使用**；保留该常量仅为记录规则来源。
     * 若你的场景确需自建，请把 {@code EngineConfig} 的对应配置指向你自己的地址。
     */
    public static final String PARSER_JS_URL = "https://vp.java68.cn/js/douyin-parser.js";

    /**
     * 注入脚本的控制头（证据：常量池 T2[5]，即两个字面量双引号 {@code ""}）。
     */
    public static final String SCRIPT_HEAD = "\"\"";

    /**
     * 注入脚本的引导段（证据：{@code buildParseScript} 块 0x1f12ea，常量池 T2[91]；该串同时是
     * 池中就绪探针 T2[1] 的载体）。其完整形态是被保护方法用 StringBuilder 拼接出来的：
     * {@code JSONObject.quote(...)} + 本段 + 尾部段。
     */
    public static final String SCRIPT_BOOTSTRAP_PREFIX =
            ";(function(){function report(r){try{DouyinBridge.postMessage(typeof r==='string'?r:JSON.stringify(r))}catch(e){}}try{DouyinParser.parse(";

    /**
     * 注入脚本的收尾段（证据：{@code buildParseScript}，常量池 T2[40]）。
     */
    public static final String SCRIPT_BOOTSTRAP_SUFFIX =
            ").then(report).catch(function(e){report({code:500,message:'JS error: '+e,data:null})})}catch(e){report({code:500,message:'JS error: '+e,data:null})}})();";

    /** 就绪探针（证据：常量池 T2[1]），用于判断页面里的解析脚本是否已注入完成。 */
    public static final String READY_PROBE = "\n;typeof window.DouyinParser === 'object';";

    /** JS 桥接对象名（证据：常量池 T2[116] / T2[118]）。 */
    public static final String BRIDGE_NAME = "DouyinBridge";

    public static final String PARSER_NAME = "DouyinParser";

    // ---------------------------------------------------------------- 时序参数
    /**
     * 结果等待超时（证据：常量池标识符 {@code JS_RESULT_TIMEOUT_MS}，
     * 被 {@code DouyinWebParser.copy(...)} 引用）。
     *
     * <p>说明：这是**池中常量**；其在指令流中的实际使用点未在转写范围内观测到（见
     * {@code 19_解析子系统原实现转写.md} §7）。此处保留以记录来源。
     */
    public static final long JS_RESULT_TIMEOUT_MS = 15_000L;

    /** HTTP 默认超时（证据：样本常量 {@code HTTP_TIMEOUT_S=15}）。 */
    public static final long HTTP_TIMEOUT_MS = 15_000L;

    /** 页面就绪等待上限（证据：样本常量 {@code PAGE_READY_TIMEOUT_MS=30000}）。 */
    public static final long PAGE_READY_TIMEOUT_MS = 30_000L;

    // ---- 以下四个超时是**指令流级**观测（非池中常量），可直接采信 ----

    /** {@code ParseWebView} 的整体超时（证据：{@code startTimeout} 块 0x13ece4，立即数 {@code 0x52}=82 → 20 s）。 */
    public static final long PARSE_WEBVIEW_TOTAL_TIMEOUT_MS = 20_000L;

    /** {@code ParseWebView.fetchHtmlSource} 抓源码用的独立临时 WebView 超时（证据：块 0x13f15e）。 */
    public static final long PARSE_WEBVIEW_FETCH_TIMEOUT_MS = 15_000L;

    /** 样本 OkHttp 的 connectTimeout（证据：块 0x13adf4）。 */
    public static final long OKHTTP_CONNECT_TIMEOUT_MS = 5_000L;

    /** 样本 OkHttp 的 readTimeout（证据：块 0x13ae04）。 */
    public static final long OKHTTP_READ_TIMEOUT_MS = 5_000L;

    /** 重试间隔（池中常量 {@code RETRY_INTERVAL_MS}；使用点未观测，见下）。 */
    public static final long RETRY_INTERVAL_MS = 1_500L;

    /**
     * 解析尝试次数。
     *
     * <p><b>⚠️ 冲突未解决</b>：早期报告从**常量池**挖出标识符 {@code MAX_PARSE_ATTEMPTS=3}，
     * 但 Track H 在 377 个解析相关方法的**指令流**中**未观测到任何重试结构**。
     * 两者并不互相印证：常量确实存在，其使用点不在转写范围内。
     *
     * <p>因此本实现取**指令流观测值**（不重试 = 1 次），并把重试次数交给
     * {@code EngineConfig} 配置；凡引用 3/1500 这两个数字的地方必须标注
     * "池中常量，使用点未观测"。
     */
    public static final int MAX_PARSE_ATTEMPTS = 1;

    /** 池中常量形式的候选值（**未观测到使用点**，仅供调用方按需启用）。 */
    public static final int MAX_PARSE_ATTEMPTS_POOL_CONSTANT = 3;

    /**
     * 判定主机是否为抖音域。
     *
     * <p>与样本 {@code isDouyinUrl} 的语义一致（先小写化，再判等或后缀匹配）：
     * {@code host == "douyin.com" || host.endsWith(".douyin.com")}。
     */
    public static boolean isDouyinHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase(java.util.Locale.ROOT);
        return DOUYIN_COM.equals(h) || h.endsWith(DOT_DOUYIN_COM);
    }

    /** 判定是否为抖音短链（需要先做重定向展开）。语义与样本一致：对整串做 {@code contains}。 */
    public static boolean containsShortLinkMarker(String urlOrHost) {
        if (urlOrHost == null) {
            return false;
        }
        String s = urlOrHost.toLowerCase(java.util.Locale.ROOT);
        for (String m : SHORT_LINK_MARKERS) {
            if (s.contains(m)) {
                return true;
            }
        }
        return false;
    }
}
