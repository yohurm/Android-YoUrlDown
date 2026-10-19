package com.yohu.lib_urlparse.sniff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * WebView 媒体嗅探判定逻辑——**纯逻辑、无 Android 类型、可单测**。
 *
 * <p>这是机制 D「媒体嗅探」的重建。证据对这条通道的评价是四条取源通道里唯一
 * "逻辑 100% 已取证、可直接移植"的一条：
 *
 * <blockquote>
 * 「重建动作：按上表 1:1 移植即可（**这是唯一可以做到行为等价的部分**）；
 * 把 {@code SSLTool} 换成正规 TLS 校验。」
 * <br>—— {@code 09_reports/15_解析能力重建规格书.md} §15.1 通道 D
 * </blockquote>
 *
 * <h2>证据来源</h2>
 * <table border="1">
 *   <caption>本类每条规则的出处</caption>
 *   <tr><th>内容</th><th>出处</th></tr>
 *   <tr>
 *     <td>{@link #BLACKLIST_URLS}（3 条整串）与 {@link #BLACKLIST_LABELS}（27 条扩展名）</td>
 *     <td>{@code 09_reports/08_嗅探_播放_下载.md} §8.1.2；
 *         原始恢复过程见 {@code 09_reports/14_字节码级常量恢复.md} §14.3(1)
 *         （{@code com.kai.sniffwebkit.net.Util.<clinit>} 的 30 条 {@code const-string}，
 *         {@code code_off=0x7ace34}，99 指令单元）；两份报告的列表逐字一致</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #SNIFF_USER_AGENT}</td>
 *     <td>同 08 章 §8.1.2 与 14 章 §14.3(2)（{@code SniffTool.<clinit>} 中唯一的字符串常量，
 *         {@code code_off=0x7ae130}）</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #ERROR_NONE} / {@link #ERROR_TIMEOUT_JS} / {@link #ERROR_HTTP} /
 *         {@link #ERROR_TIMEOUT} / {@link #STATUS_ERROR}</td>
 *     <td>08 章 §8.1.1「错误码（DEX {@code static_values}）」；并可与
 *         {@code 04_strings/app_owned_static_constants.txt} 中
 *         {@code Lcom/kai/sniffwebkit/sniff/SniffTool;} 的 {@code ERROR_HTTP="2"}、
 *         {@code ERROR_NONE="-1"}、{@code ERROR_TIMEOUT="0"}、{@code ERROR_TIMEOUT_JS="1"}、
 *         {@code STATUS_ERROR="-1"} 逐条对上</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #filter(String, String, String)} 的判定树</td>
 *     <td>15 章 §15.1 通道 D「判定树（观测）」；原始来源
 *         {@code 05_decompile/batch3_sources.txt} 的
 *         {@code MySniffingFilter.getElement} / {@code MySniffingFilter.onFilter} /
 *         {@code net.Util.getContent} / {@code net.Util.isFiltered}</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #MSG_FILTERED} / {@link #MSG_VIDEO_MPEG_URL}</td>
 *     <td>{@code net.Util.getContent} 的哨兵返回值（15 章 §15.1 通道 D
 *         「异常时 m3u8/mp4 → "video/mpeg-url"，否则 → "filtered"」）</td>
 *   </tr>
 * </table>
 *
 * <h2>【推断】的部分（明确标注，均可用 setter 覆盖）</h2>
 * <ol>
 *   <li>{@link #MIME_FLV} 与 {@link #MIME_AVI} 的**具体取值**：
 *       证据只给出 {@code MimeTypes.VIDEO_FLV} 与 {@code PictureMimeType.AVI_Q}
 *       这两个**第三方常量名**，其字面量属于第三方类、不在 app 包内，
 *       因此未被静态常量恢复覆盖（{@code 04_strings/app_owned_static_constants.txt}
 *       里没有它们）。默认值取 DEX 字符串池中确实存在的
 *       {@code "video/x-flv"} 与 {@code "video/x-msvideo"}（见该文件第 271348、271354 行），
 *       但"这两个串就是那两个常量的值"是推断。</li>
 *   <li>{@link #MIME_OCTET_STREAM}：证据（{@code 08_evidence/_working_notes.md} 对
 *       {@code onFilter} 最内层分发的转写）注明 {@code Client.DefaultMime = "application/octet-stream"}，
 *       该串亦在 DEX 池中（第 162425 行）。</li>
 *   <li>{@link #LENGTH_THRESHOLD}：证据把阈值写成
 *       {@code PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE}，**其数值未被恢复**，
 *       默认值 {@code 4194304} 是推断。</li>
 * </ol>
 *
 * <h2>【刻意未实现】的部分</h2>
 * <ul>
 *   <li>网络探测本身（{@code Util.getContent} 的 Jsoup HEAD/GET）。本类是**判定**，
 *       不做 IO；需要真实 MIME 时由宿主探测后经
 *       {@link #filter(String, String, String)} 的 {@code contentType} 传入。
 *       这样单元测试不触网，也满足"纯逻辑"要求。</li>
 *   <li>原文的 TLS 旁路（{@code SSLTool} / {@code trustAllHosts} / {@code DO_NOT_VERIFY}）。
 *       15 章明确要求「把 {@code SSLTool} 换成正规 TLS 校验」，故此处不提供。</li>
 *   <li>{@code HttpReferer} 的域名→Referer 映射：证据只记录该类存在
 *       （{@code 08_evidence/_working_notes.md} §6.3 末段：「**未获得方法体与字段**
 *       → Referer 生成表不可得」），因此不编造。</li>
 * </ul>
 */
public final class MediaUrlSniffer {

    // ------------------------------------------------------------ 30 项黑名单（【观测】）

    /**
     * 整串 URL 黑名单：**子串包含**即过滤（3 条）。
     *
     * <p>证据：{@code 09_reports/08_嗅探_播放_下载.md} §8.1.2「{@code urls}：整串 URL 黑名单（3 条）」
     * 与 {@code 09_reports/14_字节码级常量恢复.md} §14.3(1)「{@code urls}（整串包含匹配）」，
     * 两份列表逐字相同。恢复自 {@code com.kai.sniffwebkit.net.Util.<clinit>} 的 {@code const-string}
     * （{@code code_off=0x7ace34}）。
     *
     * <p>【推断】08 章对该表作用的判断是"抑制误报：多数是静态资源/网页扩展名，
     * 另加 3 条已知会误判的视频地址"。
     */
    public static final List<String> BLACKLIST_URLS = Collections.unmodifiableList(Arrays.asList(
            "41115.m3u8",
            "api.jhdyw.vip/1.m3u8",
            "https://yuncache.52e.cc/m3u8/b079195c40f264fe9f876968c7b3c821.m3u8"
    ));

    /**
     * 扩展名黑名单：URL **去查询串后最后一段**的后缀匹配即过滤（27 条，按
     * {@code <clinit>} 中的出现顺序）。
     *
     * <p>证据：同 {@link #BLACKLIST_URLS}。注意列表首尾的
     * {@code ".js"} 与 {@code "webp"} 是**没有前导点**的形态差异：
     * {@code "jpe"}、{@code "f4v"}、{@code "webp"} 三条按原文**不带前导点**，
     * 本实现严格照抄（匹配逻辑是 {@code endsWith}，所以 {@code "webp"} 也会命中
     * {@code "a.webp"}）——不做"修正"。
     */
    public static final List<String> BLACKLIST_LABELS = Collections.unmodifiableList(Arrays.asList(
            ".js", ".html", ".png", ".jpg", ".json", "jpe", ".wbmp", ".net", ".fax", ".gif", ".jpeg",
            ".htm", ".jsp", ".asp", ".ico", ".xml", ".xhtml", ".tif", ".tiff", ".wasm", ".woff",
            ".svga", ".ts", ".ttf", "f4v", ".dat", "webp"
    ));

    // ------------------------------------------------------------ 嗅探 UA（【观测】）

    /**
     * 嗅探请求使用的 UA：伪装成 **macOS 桌面版 Chrome 78**。
     *
     * <p>证据：{@code 09_reports/08_嗅探_播放_下载.md} §8.1.2 与
     * {@code 09_reports/14_字节码级常量恢复.md} §14.3(2)——
     * 由 {@code SniffTool.<clinit>} 的字节码恢复取得（该 {@code <clinit>} 中唯一的字符串常量）。
     *
     * <p>用 {@code net.Util.getContent} 的 {@code .userAgent(SniffTool.getUserAgent())} 传入，
     * 见 15 章 §15.1 通道 D 的探测伪码。这是"某浏览器的 UA 字符串"，可安全复用；
     * 请按需替换为你自己的标识。
     */
    public static final String SNIFF_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/78.0.3904.70 Safari/537.36";

    // ------------------------------------------------------------ 错误码（【观测】）

    /** {@code ERROR_NONE = -1}（证据：08 章 §8.1.1「错误码（DEX {@code static_values}）」）。 */
    public static final int ERROR_NONE = -1;

    /**
     * {@code ERROR_TIMEOUT_JS = 1}（证据：同上）。
     *
     * <p>语义见 {@code SniffTool.completeSniff}：只有 {@code -1}（NONE）与 {@code 2}（HTTP）
     * 会取消超时回调，其余错误码保留超时状态。
     */
    public static final int ERROR_TIMEOUT_JS = 1;

    /** {@code ERROR_HTTP = 2}（证据：同上）。 */
    public static final int ERROR_HTTP = 2;

    /** {@code ERROR_TIMEOUT = 0}（证据：同上）。 */
    public static final int ERROR_TIMEOUT = 0;

    /** {@code STATUS_ERROR = -1}（证据：同上）。 */
    public static final int STATUS_ERROR = -1;

    // ------------------------------------------------------------ 载体的超时（转写 §5 / §6.6）

    /**
     * 嗅探/网页抓取宿主 {@code widget/ParseWebView#startTimeout} 的整体超时
     * （证据：{@code 09_reports/19_解析子系统原实现转写.md} §5.2 与 §6.6
     * 「超时 | <b>20000 ms</b> | {@code widget/ParseWebView#startTimeout} |
     * {@code code_va 0x13ecce} | 站点 {@code 0x13ece4}」）。
     *
     * <p>这不是嗅探判定的一部分，但宿主往往就是同一个 {@code ParseWebView}，
     * 所以放在这里便于调用方对齐时序。
     */
    public static final long PARSE_WEBVIEW_TIMEOUT_MS = 20_000L;

    /**
     * {@code widget/ParseWebView#fetchHtmlSource$lambda-15} 的临时 WebView 加载超时
     * （证据：转写 §5.3 与 §6.6「超时 | <b>15000 ms</b> | … | {@code code_va 0x13f0c2} |
     * 站点 {@code 0x13f15e}」）。
     */
    public static final long FETCH_HTML_TIMEOUT_MS = 15_000L;

    /**
     * {@code ParseWebView$okHttpClient$2#invoke} 的 OkHttp connect/read 超时
     * （证据：转写 §5.4 与 §6.6 的两行「5000 ms（connect）/（read）」，
     * {@code code_va 0x13ade6}，站点 {@code 0x13adf4} / {@code 0x13ae04}）。
     * 注意 §5.4 同时记录 {@code followRedirects = false}——与本库
     * {@code DefaultHttpClient} 跟随重定向的默认行为不同，宿主需自行注意。
     */
    public static final long OKHTTP_TIMEOUT_MS = 5_000L;

    // ------------------------------------------------------------ 哨兵与 MIME

    /** {@code net.Util.getContent} 的"被黑名单过滤"哨兵（证据：{@code Util.isFiltered} 命中时 {@code objects[1]="filtered"}）。 */
    public static final String MSG_FILTERED = "filtered";

    /**
     * 探测异常时对 m3u8/mp4 的兜底 MIME（证据：{@code Util.getContent} 的
     * {@code catch} 分支 {@code url.endsWith("m3u8") || url.endsWith("mp4") → "video/mpeg-url"}）。
     *
     * <p>注意原文拼写就是 {@code mpeg-url}（不是 mpegurl），此处照抄。
     */
    public static final String MSG_VIDEO_MPEG_URL = "video/mpeg-url";

    /**
     * m3u8 分支直接产出的 Content-Type。
     *
     * <p>证据：{@code MySniffingFilter.onFilter} 里
     * {@code url2.contains(".m3u8?vkey=") → new SniffingVideo(url2, "image/vnd.microsoft.icon", 0, "m3u8")}
     * （08 章 §8.1.2、15 章 §15.1 通道 D、{@code 05_decompile/batch3_sources.txt}）。
     * 该字面量看起来"不像视频"，但它是原文照抄——原实现的写法如此。
     */
    public static final String MIME_M3U8_VKEY = "image/vnd.microsoft.icon";

    /** 直接产出的 mp4 MIME（证据：{@code onFilter} 的 {@code "video/mp4"} 分支）。 */
    public static final String MIME_MP4 = "video/mp4";

    /**
     * DEX 池中确实存在的 {@code video/x-flv}（{@code 04_strings/dex_strings_unique.txt} 第 271348 行）。
     *
     * <p><b>【推断】</b>：证据把 FLV 判定写成 {@code s.equals(MimeTypes.VIDEO_FLV)}
     * （{@code MimeTypes} 是第三方类，字面量不在 app 包静态常量里），
     * 因此"该常量就是 {@code video/x-flv}"是推断。可用
     * {@link #withFlvMime(String)} 覆盖。
     */
    public static final String MIME_FLV = "video/x-flv";

    /**
     * 【推断】AVI 判定用的 MIME。
     *
     * <p>证据：{@code onFilter} 的 {@code s.equals(PictureMimeType.AVI_Q)}；
     * 第三方常量 {@code AVI_Q} 的字面量不可得。默认取 DEX 池中存在的
     * {@code "video/x-msvideo"}（{@code dex_strings_unique.txt} 第 271354 行），属推断。
     * 可用 {@link #withAviMime(String)} 覆盖；设为 null 即关闭 AVI 分支。
     */
    public static final String MIME_AVI = "video/x-msvideo";

    /**
     * {@code Client.DefaultMime}（证据：{@code 08_evidence/_working_notes.md} 对 {@code onFilter}
     * 最内层分发的转写，注释原文 {@code // DefaultMime = "application/octet-stream"}；
     * 该串亦在 DEX 池，{@code dex_strings_unique.txt} 第 162425 行）。
     */
    public static final String MIME_OCTET_STREAM = "application/octet-stream";

    /** m3u8 类 MIME 片段（证据：{@code onFilter} 的 {@code s.contains("mpeg")} /
     *  {@code s.contains("mpegurl")} 判定与 {@code "video/x-mpegurl"} / {@code "video/x-mepgurl"} 字面量）。 */
    public static final String MIME_MPEGURL = "video/x-mpegurl";

    /**
     * 【推断】长度阈值。
     *
     * <p>证据把该阈值写成 {@code Util.getContentLength(url2) >= PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE}
     * （{@code ActionManager}-系常量，属第三方库），**数值未被恢复**。
     * 默认 {@code 4194304}（4 MiB）是推断，可用 {@link #withLengthThreshold(long)} 覆盖。
     */
    public static final long LENGTH_THRESHOLD = 4L * 1024 * 1024;

    /**
     * URL 里出现即视为"带防盗链参数"的片段（证据：{@code onFilter} 的 {@code url2.contains("vkey")}、
     * {@code .m3u8?vkey=}、{@code .mp4?}、{@code .m3u8?} 判定族；15 章 §15.1 通道 D 的
     * 「排除：{@code index.m3u8}、含 {@code filename}、含 {@code vkey}」）。
     */
    public static final String PARAM_VKEY = "vkey";

    /** 排除片段（证据：同上，{@code url2.contains("filename")}）。 */
    public static final String EXCLUDE_FILENAME = "filename";

    /** 排除后缀（证据：同上，{@code url2.endsWith("index.m3u8")}）。 */
    public static final String EXCLUDE_INDEX_M3U8 = "index.m3u8";

    /** {@code ?url=http} 包装（证据：{@code onFilter} 首个分支 {@code url2.contains("?url=http")}，
     *  命中时原实现做 {@code url2 = url2.replaceAll(".*?url=", "")} 解包）。 */
    public static final String WRAPPER_URL_HTTP = "?url=http";

    /** 需要排除的 CDN 前缀（证据：{@code onFilter} 的 {@code url2.startsWith("https://ycache.parwix.com:4433/c")}；
     *  {@code 08_evidence/_working_notes.md} 亦把 {@code https://ycache.parwix.com:4433/} 与
     *  {@code …:4433/c} 列为观测到的字符串常量）。 */
    public static final String EXCLUDE_PREFIX_YCACHE = "https://ycache.parwix.com:4433/c";

    /** m3u8 后缀片段（证据：{@code Video.SUFFIX.SUFFIX_M3U8} 在 {@code onFilter} 中与
     *  {@code url2.contains("m3u8")} 并用；其值 {@code ".m3u8"} 属【推断】，
     *  见 {@code 08_evidence/_working_notes.md} §6.4 末段「推断其值为 {@code ".m3u8"}…但**未直接观测到**」）。 */
    public static final String SUFFIX_M3U8 = ".m3u8";

    /** flv 片段（证据：{@code onFilter} 的 {@code url2.contains(".flv")}）。 */
    public static final String SUFFIX_FLV = ".flv";

    /** mp4 后缀（证据：{@code onFilter} 的 {@code url2.endsWith(".mp4")}）。 */
    public static final String SUFFIX_MP4 = ".mp4";

    // ------------------------------------------------------------ 可覆盖项

    private final String flvMime;
    private final String aviMime;
    private final long lengthThreshold;
    private final List<String> blacklistUrls;
    private final List<String> blacklistLabels;

    /** 默认实例（全部按证据默认值）。 */
    public MediaUrlSniffer() {
        this(MIME_FLV, MIME_AVI, LENGTH_THRESHOLD, BLACKLIST_URLS, BLACKLIST_LABELS);
    }

    private MediaUrlSniffer(String flvMime, String aviMime, long lengthThreshold,
                            List<String> blacklistUrls, List<String> blacklistLabels) {
        this.flvMime = flvMime;
        this.aviMime = aviMime;
        this.lengthThreshold = lengthThreshold;
        this.blacklistUrls = blacklistUrls;
        this.blacklistLabels = blacklistLabels;
    }

    public static MediaUrlSniffer defaults() {
        return new MediaUrlSniffer();
    }

    /** 覆盖 FLV 的 MIME 常量（对应不可得的 {@code MimeTypes.VIDEO_FLV}）。 */
    public MediaUrlSniffer withFlvMime(String v) {
        return new MediaUrlSniffer(v, aviMime, lengthThreshold, blacklistUrls, blacklistLabels);
    }

    /** 覆盖 AVI 的 MIME 常量（对应不可得的 {@code PictureMimeType.AVI_Q}）；传 null 关闭该分支。 */
    public MediaUrlSniffer withAviMime(String v) {
        return new MediaUrlSniffer(flvMime, v, lengthThreshold, blacklistUrls, blacklistLabels);
    }

    /** 覆盖长度阈值（对应未恢复数值的 {@code ACTION_SET_SHUFFLE_MODE}）。 */
    public MediaUrlSniffer withLengthThreshold(long v) {
        return new MediaUrlSniffer(flvMime, aviMime, v, blacklistUrls, blacklistLabels);
    }

    public List<String> blacklistUrls() {
        return blacklistUrls;
    }

    public List<String> blacklistLabels() {
        return blacklistLabels;
    }

    /** 嗅探 UA（实例方法形式，便于宿主链式取用）。 */
    public String userAgent() {
        return SNIFF_USER_AGENT;
    }

    // ------------------------------------------------------------ 判定：黑名单

    /**
     * 复刻 {@code net.Util.isFiltered(String)}：
     * <pre>
     * for (String u : urls)   if (url.contains(u)) return true;
     * String url2 = url.replaceAll("\\?.*", "");
     * String url3 = url2.substring(url2.lastIndexOf('/'));
     * for (String label : labels) if (url3.endsWith(label)) return true;
     * return false;
     * </pre>
     * 证据：{@code 05_decompile/batch3_sources.txt: com.kai.sniffwebkit.net.Util.isFiltered}
     * 与 {@code 08_evidence/_working_notes.md} §6.3 的等价转写。
     *
     * <p>注意原实现 {@code url2.substring(url2.lastIndexOf('/'))} 在**没有斜杠**时
     * 会取 {@code substring(-1)} 抛异常；本实现按 {@code getElement} 的写法
     * （{@code lastIndexOf} 的返回值仅在 {@code < length} 时才截断）保守处理为"整串当末段"，
     * 这是**行为差异**，已在此标注。
     */
    public boolean isFiltered(String url) {
        if (url == null || url.isEmpty()) {
            return true;
        }
        for (String u : blacklistUrls) {
            if (url.contains(u)) {
                return true;
            }
        }
        String noQuery = url.replaceAll("\\?.*", "");
        String last = lastPathSegment(noQuery, true);
        for (String label : blacklistLabels) {
            if (last.endsWith(label)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 复刻 {@code MySniffingFilter.getElement(String)}——取"最后路径段的扩展名"：
     * <pre>
     * url2 = url.replaceAll("\\?.*", "");
     * int last = url2.lastIndexOf('/');
     * if (last &lt; url2.length()) url2 = url2.substring(last);
     * if (url2.contains(".")) return url2.substring(url2.lastIndexOf("."));
     * return "none";
     * </pre>
     * 证据：{@code 05_decompile/batch3_sources.txt: MySniffingFilter.getElement}
     * （JADX {@code status=available}，方法体完整可读）。
     *
     * @return 形如 {@code ".m3u8"}；无扩展名时返回 {@code "none"}（与原文一致）
     */
    public static String getElement(String url) {
        if (url == null) {
            return "none";
        }
        String url2 = url.replaceAll("\\?.*", "");
        int last = url2.lastIndexOf('/');
        if (last >= 0 && last < url2.length()) {
            url2 = url2.substring(last);
        }
        if (url2.contains(".")) {
            return url2.substring(url2.lastIndexOf('.'));
        }
        return "none";
    }

    /**
     * 取 URL 去掉查询串后的最后一段。
     *
     * @param keepLeadingSlash 是否保留原实现 {@code substring(lastIndexOf('/'))} 的前导斜杠
     */
    private static String lastPathSegment(String s, boolean keepLeadingSlash) {
        int last = s.lastIndexOf('/');
        if (last < 0 || last >= s.length()) {
            return s;
        }
        return keepLeadingSlash ? s.substring(last) : s.substring(last + 1);
    }

    /**
     * 解 {@code ?url=http…} 包装（证据：{@code onFilter} 首个分支
     * {@code if (url2.contains("?url=http")) url2 = url2.replaceAll(".*?url=", "");}）。
     *
     * @return 解包后的 URL；不含该包装时原样返回
     */
    public static String unwrap(String url) {
        if (url != null && url.contains(WRAPPER_URL_HTTP)) {
            return url.replaceAll(".*?url=", "");
        }
        return url;
    }

    // ------------------------------------------------------------ 判定：决策树

    /**
     * 判定一个请求是否是"要抓的媒体流"。
     *
     * <p>与 Android 无关：宿主在 {@code WebViewClient.shouldInterceptRequest} 里
     * 取到 URL 后调用本方法即可。
     *
     * @param rawUrl        请求 URL（{@code WebResourceRequest.getUrl().toString()}）
     * @param contentType   已探测到的 Content-Type（可为 null / 空 = 未知，此时不做基于内容的判定）
     * @param contentLength 已探测到的长度（&le; 0 = 未知）
     * @return 判定结果，见 {@link Decision}
     */
    public Decision filter(String rawUrl, String contentType, long contentLength) {
        if (rawUrl == null || rawUrl.isEmpty()) {
            return Decision.rejected(rawUrl, "empty url");
        }
        // 1) 黑名单优先：与 Util.getContent 的第一步一致，不发任何请求
        if (isFiltered(rawUrl)) {
            return Decision.rejected(rawUrl, MSG_FILTERED);
        }

        // 2) ?url=http 解包（原文在 m3u8 分支内做，这里提前做，语义等价于原文的 url2）
        String url = unwrap(rawUrl);
        String lower = url.toLowerCase(Locale.ROOT);
        String mime = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        int semi = mime.indexOf(';');
        if (semi >= 0) {
            mime = mime.substring(0, semi).trim();
        }

        // 3) 排除项（15 章 §15.1 通道 D「排除：index.m3u8、含 filename、含 vkey」）
        if (lower.endsWith(EXCLUDE_INDEX_M3U8) || lower.contains(EXCLUDE_FILENAME)) {
            return Decision.rejected(rawUrl, "excluded by " + EXCLUDE_INDEX_M3U8 + "/" + EXCLUDE_FILENAME);
        }
        // 站点特例：该 CDN 前缀被原文显式排除
        if (lower.startsWith(EXCLUDE_PREFIX_YCACHE)) {
            return Decision.rejected(rawUrl, "excluded prefix");
        }

        // 4) 带 vkey 的 m3u8：直接产出（不走 HTTP 探测）
        boolean isM3U8 = lower.contains(SUFFIX_M3U8);
        if (isM3U8 && lower.contains(SUFFIX_M3U8 + "?" + PARAM_VKEY + "=")) {
            return Decision.captured(rawUrl, MIME_M3U8_VKEY, "m3u8", true, "m3u8?vkey=");
        }

        // 5) 带 vkey 的通用分支（原文：url 含 vkey 且（含 .mp4? 或（含 .m3u8? 且非 ycache））
        if (lower.contains(PARAM_VKEY)
                && (lower.contains(SUFFIX_MP4 + "?")
                    || (lower.contains(SUFFIX_M3U8 + "?") && !lower.contains("ycache.parwix.com:4433")))) {
            String m = isM3U8 ? MIME_MPEGURL : MIME_FLV;
            return Decision.captured(rawUrl, m, "vkey", true, "vkey");
        }

        // 6) flv：URL 含 .flv 且不是脚本；内容类型必须是 FLV 才正式接受
        if (lower.contains(SUFFIX_FLV) && !lower.contains(".js")) {
            if (mime.isEmpty()) {
                return Decision.captured(rawUrl, flvMime, "flv", false, "flv by url (content-type unknown)");
            }
            if (mime.equals(flvMime) || "video/flv".equals(mime)) {
                return Decision.captured(rawUrl, mime, "flv", true, "flv by content-type");
            }
            return Decision.rejected(rawUrl, "flv url but content-type=" + mime);
        }

        // 7) mp4：.mp4 结尾或含 .mp4?；或内容类型 video/mp4；或 octet-stream + 长度达阈值
        if (lower.endsWith(SUFFIX_MP4) || lower.contains(SUFFIX_MP4 + "?")) {
            if (mime.isEmpty()) {
                return Decision.captured(rawUrl, MIME_MP4, "mp4", false, "mp4 by url (content-type unknown)");
            }
            if (mime.equals(MIME_MP4) || mime.contains("video") || mime.contains("mpeg")) {
                return Decision.captured(rawUrl, mime, "mp4", true, "mp4 by content-type");
            }
            if (mime.equals(MIME_OCTET_STREAM)) {
                return Decision.captured(rawUrl, MIME_OCTET_STREAM, "mp4", true, "mp4 by octet-stream url");
            }
            return Decision.rejected(rawUrl, "mp4 url but content-type=" + mime);
        }

        // 8) 基于内容类型的分发（对应 onFilter 最内层；无内容类型则无法判定）
        if (mime.isEmpty()) {
            return Decision.rejected(rawUrl, "no content-type evidence");
        }
        if (mime.equals(MIME_MP4)) {
            return Decision.captured(rawUrl, mime, "mp4", true, "content-type video/mp4");
        }
        // AVI 判定必须排在泛化的 "video/mpeg" 之前：否则像 video/x-msvideo 这类
        // 也含 "video" 的取值会被泛化分支吃掉，withAviMime(null) 就关不掉它。
        if (aviMime != null && mime.equals(aviMime)) {
            return Decision.captured(rawUrl, mime, "avi", true, "content-type avi");
        }
        if (mime.equals(MIME_OCTET_STREAM)) {
            if (".mp4".equals(getElement(url))) {
                return Decision.captured(rawUrl, mime, "mp4", true, "octet-stream + .mp4");
            }
            if (contentLength >= lengthThreshold) {
                return Decision.captured(rawUrl, mime, "mp4", false, "octet-stream + length>=threshold");
            }
            if (isM3U8) {
                return Decision.captured(rawUrl, mime, "m3u8", false, "octet-stream + m3u8");
            }
            return Decision.rejected(rawUrl, "octet-stream without media evidence");
        }
        if (mime.contains("video") || mime.contains("mpeg")) {
            String type = (isM3U8 || mime.contains("mpegurl")) ? "m3u8" : "mp4";
            return Decision.captured(rawUrl, mime, type, true, "content-type " + mime);
        }
        if (mime.contains("text/html") && (lower.endsWith(SUFFIX_M3U8) || lower.contains(SUFFIX_M3U8 + "?"))
                && !lower.contains("?url=")) {
            return Decision.captured(rawUrl, mime, "m3u8", false, "text/html served for m3u8");
        }
        if (mime.contains("image") && isM3U8) {
            // 原文的站点特例：有些站点用 image/* 伪装 m3u8（并额外有 renrenmi / image/fuck/you 判定）
            return Decision.captured(rawUrl, mime, "m3u8", false, "image/* served for m3u8");
        }
        if (mime.isEmpty() && lower.contains(SUFFIX_MP4)) {
            return Decision.captured(rawUrl, MIME_MP4, "mp4", false, "empty content-type + .mp4");
        }
        return Decision.rejected(rawUrl, "filtered");
    }

    /**
     * 便捷重载：只有 URL 时的判定（等价于"内容类型未知"）。
     *
     * <p>宿主若懒于探测 Content-Type，可先用它做快速预判：
     * 返回 {@link Decision#captured()} 且 {@link Decision#certain()} 为 false 的项，
     * 表示"按 URL 看起来是媒体，但还需内容类型确认"（与原文"先 URL 字面量、后探测"的顺序一致）。
     */
    public Decision filter(String url) {
        return filter(url, null, -1L);
    }

    /**
     * 给宿主从 {@code WebViewClient.shouldInterceptRequest} 调用的薄封装。
     *
     * <p>注意：被分析样本的嗅探器是一个**独立的 AgentWeb 嗅探状态机**
     * （{@code SniffTool}），它并不通过 {@code shouldInterceptRequest} 拿请求；
     * 15 章 §15.1 通道 D 只定义了"URL → 判定"这一步。
     * 因此本方法**不做任何资源类型/主框架过滤**——证据里没有任何基于
     * {@code resourceType} 的规则，编造一个会违背"不发明规则"。
     * 宿主若需要按类型预筛（例如只处理 {@code media}/{@code xhr}），请自行在调用点判断。
     *
     * @param url          请求 URL
     * @param method       HTTP 方法（本实现不使用；保留以贴合宿主签名）
     * @param contentType  已探测的 Content-Type，可为 null
     * @param contentLength 已探测的长度，&le;0 表示未知
     */
    public Decision shouldInterceptRequest(String url, String method, String contentType, long contentLength) {
        return filter(url, contentType, contentLength);
    }

    // ------------------------------------------------------------ 结果

    /** 一次嗅探判定的结果。不可变、无 Android 依赖。 */
    public static final class Decision {

        private final String url;
        private final boolean captured;
        private final String contentType;
        private final String format;
        private final boolean certain;
        private final String reason;

        private Decision(String url, boolean captured, String contentType, String format,
                         boolean certain, String reason) {
            this.url = url;
            this.captured = captured;
            this.contentType = contentType;
            this.format = format;
            this.certain = certain;
            this.reason = reason;
        }

        /**
         * 产出"要抓的媒体"。
         *
         * @param certain true = 已由 Content-Type 或明确字面量确认；false = 仅按 URL 形态推断
         */
        static Decision captured(String url, String contentType, String format, boolean certain, String reason) {
            return new Decision(url, true, contentType, format, certain, reason);
        }

        static Decision rejected(String url, String reason) {
            return new Decision(url, false, null, null, false, reason);
        }

        /** 是否为需要捕获的媒体流。 */
        public boolean captured() {
            return captured;
        }

        /** 判定为捕获时的 Content-Type；未捕获为 null。 */
        public String contentType() {
            return contentType;
        }

        /**
         * 归类格式：{@code m3u8} / {@code flv} / {@code mp4} / {@code avi} / {@code vkey}。
         *
         * <p>证据：{@code 08_evidence/_working_notes.md} §6.3 末「归类输出的 {@code type}
         * 只有 5 种：{@code m3u8}、{@code flv}、{@code mp4}、{@code avi}、{@code vkey}」。
         */
        public String format() {
            return format;
        }

        /** 判定是否确定（true = 有内容类型/明确字面量支撑；false = 仅 URL 形态推断）。 */
        public boolean certain() {
            return certain;
        }

        /** 判定原因，便于排障（{@link #MSG_FILTERED} 表示被黑名单过滤）。 */
        public String reason() {
            return reason;
        }

        /** 捕获到的 URL（可能是 {@code ?url=http} 解包后的形态）。 */
        public String url() {
            return url;
        }

        /** 便捷：供下载器/播放器使用的候选标记。 */
        public boolean isStream() {
            return captured && ("m3u8".equals(format) || "flv".equals(format)
                    || "mp4".equals(format) || "avi".equals(format));
        }

        @Override
        public String toString() {
            return "Decision{captured=" + captured + ", format=" + format
                    + ", contentType=" + contentType + ", certain=" + certain
                    + ", reason=" + reason + '}';
        }
    }

    /** 便于宿主把黑名单原样导出做审计（返回不可变副本）。 */
    public static List<String> allBlacklistEntries() {
        List<String> all = new ArrayList<>(BLACKLIST_URLS.size() + BLACKLIST_LABELS.size());
        all.addAll(BLACKLIST_URLS);
        all.addAll(BLACKLIST_LABELS);
        return Collections.unmodifiableList(all);
    }
}
