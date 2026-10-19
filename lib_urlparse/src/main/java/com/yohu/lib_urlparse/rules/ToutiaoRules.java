package com.yohu.lib_urlparse.rules;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 头条 / 西瓜视频通道（{@code Platform.TOUTIAO}）的规则常量。
 *
 * <p><b>还原依据</b>：样本 {@code com.one.downloadtools.helper.InclusiveVideoParseHelper}
 * 的 Toutiao 分支。该通道是机制 C「服务端聚合解析 API」的一支，入口
 * {@code parseToutiao}（**native，无 code_item**），结果模型
 * {@code InclusiveVideoParseHelper$ToutiaoData} 与其内部类
 * {@code ToutiaoVideoInfo} 的字段由 DEX {@code class_data} 直接读出。
 *
 * <p><b>首选证据</b>：{@code 09_reports/19_解析子系统原实现转写.md}（VMP 字节码逐条还原，
 * 377 方法 / 99.7% 精确吻合）§4.1–§4.3 与 §6 规则总表。下文凡引 §4/§6 均指该报告；
 * 转写未覆盖处才回落到 06/15/16 号报告并明确标注。
 *
 * <p><b>证据链</b>
 * <ul>
 *   <li>§4.1：{@code parseShortVideo} / {@code parseDouyinHome} / {@code parseToutiao}
 *       三个入口**结构完全一致**（各 78 码元，{@code exact_fit=true}），
 *       {@code code_va} 分别为 {@code 0x1f7142} / {@code 0x1f70a6} / {@code 0x1f71de}。
 *       头条入口的 {@code code_va} = {@code 0x1f71de}；</li>
 *   <li>§4.2：{@code parseToutiao$$inlined$call$1#invokeSuspend}（{@code code_va 0x1f1fa2}，
 *       458 码元，{@code exact_fit=true}）——请求 payload、业务码、解密、错误文案；</li>
 *   <li>§4.3：{@code ToutiaoData.bestVideoUrl()}（{@code code_va 0x1f3194}，88 码元，
 *       {@code exact_fit=true}）与模型 ↔ 字段一览；</li>
 *   <li>§6.5 的 JSON 键名表（22 条）中属于本通道的 {@code apiKey} / {@code data} / {@code url}
 *       与 {@code code} / {@code message} / {@code data}；</li>
 *   <li>§6.6 的 {@code code == 200}（业务成功码，站点 {@code 0x1ebb0e}）。</li>
 * </ul>
 *
 * <p><b>关于端点与凭据</b>：样本把头条解析发往作者私有云函数：§6.3 的 E3 =
 * {@code https://app.yyai8.com/api/functions/call}（{@code code_va 0x1eb8a4} 等），
 * 凭据见 §6.7 第 1 行（解析服务 apiKey，{@code code_va 0x1f7170}）。
 * 按本工程的一贯做法，这些**私有资产不内置**：端点与 key 必须由调用方通过
 * {@code EngineConfig.cloudEndpoint(...)} / {@code cloudApiKey(...)} 注入，
 * 未配置时以 {@code ParseException.Reason.CREDENTIALS_MISSING} 明确失败。
 * 本类只保留 {@link #SAMPLE_LINK_IXIGUA} 作为**来源记录**（来自 16 章 §16.2 的样例链接语料）。
 *
 * <p><b>刻意保留的缺口</b>：
 * <ol>
 *   <li>{@code ApiResponse} / {@code ParseApiResponse} 的 {@code code}/{@code message}/{@code data}
 *       是 **Gson 直接反序列化的字段名**，不是显式 {@code optString} 读的键
 *       ⇒ 键名属【推断】（转写 §4.3 脚注与 §7 U6 已如此标注）。本类据此标注，不写成"已还原"；</li>
 *   <li>{@code medias} 元素的字段名由 §4.3 的 {@code MediaData{url, separate, quality}} 给出，
 *       但**转写没有把 {@code ToutiaoData.medias} 的元素类型明确写成 {@code MediaData}**；
 *       故 {@link #MEDIA_ITEM_URL_KEYS} 仍按候选列表处理（首项 {@code url} 有强证据）；</li>
 *   <li>图集（{@code type} 取值）在证据里**没有任何字面量**，故不提供默认取值，
 *       由 {@code ToutiaoParseProvider.ResultMapping} 让调用方声明。</li>
 * </ol>
 */
public final class ToutiaoRules {

    private ToutiaoRules() {
    }

    // ---------------------------------------------------------------- 请求 payload（§4.2 / §6.5）

    /**
     * payload 顶层键：凭据（证据：转写 §4.2 步骤 2
     * {@code payload.put("apiKey", apiKey)}，{@code str#131 @0x1eb940} → {@code #1637}；
     * 头条段的对应站点为 {@code 0x1f1fa2} 段内偏移）。
     *
     * <p>§6.5 的 JSON 键名表把它归入
     * 「{@code apiKey} {@code data} {@code url} | {@code InclusiveVideoParseHelper$*$$inlined$call$1#invokeSuspend} | 写（payload）」。
     */
    public static final String PAYLOAD_KEY_API_KEY = "apiKey";

    /** payload 顶层键：业务数据（证据：同上，{@code payload.put("data", data)}，{@code str#159 @0x1eb94a}）。 */
    public static final String PAYLOAD_KEY_DATA = "data";

    /** payload 内层键：待解析链接（证据：同上，{@code data.put("url", originalLink)}，{@code str#277 @0x1eb95c}）。 */
    public static final String PAYLOAD_KEY_URL = "url";

    /**
     * 完整 payload 形状（证据：转写 §4.2「payload 结构」原文
     * {@code {"apiKey": <apiKey>, "data": {"url": <originalLink>}}}）。
     *
     * <p>仅作文档性常量：真正的序列化在 provider 里用 {@code JSONObject.quote} 完成。
     */
    public static final String PAYLOAD_SHAPE = "{\"apiKey\": <apiKey>, \"data\": {\"url\": <link>}}";

    // ---------------------------------------------------------------- 业务码与错误文案（§4.2 / §6.6）

    /**
     * 成功业务码（证据：转写 §4.2 步骤 11 与 §6.6「阈值 | {@code code == 200}（业务成功码）|
     * {@code InclusiveVideoParseHelper$*$$inlined$call$1#invokeSuspend} | {@code 0x1eb8a4} 等 |
     * 站点 {@code 0x1ebb0e}」）。
     */
    public static final int BUSINESS_CODE_SUCCESS = 200;

    /**
     * 外层信封里的状态码键（**Gson 字段名 ⇒【推断】**）。
     *
     * <p>证据：转写 §4.3 脚注「{@code ApiResponse}/{@code ParseApiResponse} 的
     * {@code code}/{@code message}/{@code data} 是 **Gson 直接反序列化的字段名**
     * （经 {@code $$inlined$call$1$1}/{@code $2} 的 {@code getType()} + {@code Gson.fromJson}），
     * **不是**显式 {@code optString} 读的键 → 键名【推断】（依据 Gson 默认字段名策略）」，
     * 并由 §7 U6 复述。故 {@link #CODE_KEY} / {@link #MESSAGE_KEY} / {@link #DATA_KEY}
     * 三个键名只能算推断。
     */
    public static final String CODE_KEY_INFERRED = "code";

    /** 外层信封里的消息键（**推断**，理由同 {@link #CODE_KEY_INFERRED}）。 */
    public static final String MESSAGE_KEY_INFERRED = "message";

    /** 外层信封里的数据键（**推断**，理由同 {@link #CODE_KEY_INFERRED}）。 */
    public static final String DATA_KEY_INFERRED = "data";

    /** 失败文案（证据：转写 §4.2「错误文案全集」{@code 解析失败}，{@code str#327 @0x1eb8b6}）。 */
    public static final String MSG_PARSE_FAILED_T = "解析失败";

    /** 调用失败文案（证据：同上，{@code 解析服务调用失败}，{@code str#331 @0x1eba34}）。 */
    public static final String MSG_CALL_FAILED = "解析服务调用失败";

    /** 结果格式错误（证据：同上，{@code 解析结果格式错误}，{@code str#335 @0x1ebb98}）。 */
    public static final String MSG_RESULT_FORMAT_ERROR = "解析结果格式错误";

    /** 解密失败（证据：同上，{@code 解析结果解密失败}，{@code str#336 @0x1ebba8}；步骤 9 的空白分支）。 */
    public static final String MSG_DECRYPT_FAILED = "解析结果解密失败";

    /** 响应格式错误（证据：同上，{@code 解析服务返回格式错误}，{@code str#333 @0x1ebbb8}；步骤 8 的"data 非字符串"分支）。 */
    public static final String MSG_RESPONSE_FORMAT_ERROR = "解析服务返回格式错误";

    /** 响应为空（证据：同上，{@code 解析服务返回为空}，{@code str#332 @0x1ebbc8}）。 */
    public static final String MSG_RESPONSE_EMPTY = "解析服务返回为空";

    // ---------------------------------------------------------------- 输入识别

    /**
     * 样本样例链接语料中的西瓜视频链接（证据：{@code 09_reports/16_解析规则全集.md}
     * §16.2「平台覆盖面：被抽走代码里的**样例链接语料**」表中「西瓜视频」行，原样为
     * {@code https://www.ixigua.com/7035542323689685535?logTag=...}）。
     *
     * <p>仅作**来源记录**：本实现不请求任何样本侧地址。它的用途是证明
     * {@code ixigua.com} 属于该通道原本覆盖的站点。
     *
     * <p>注：转写 §6.2 的「主机字面量（12 条）」里**没有** {@code ixigua.com}
     * （那 12 条全部属于抖音判定或硬编码端点），因此本域的唯一证据仍是 16 章 §16.2 的样例语料。
     */
    public static final String SAMPLE_LINK_IXIGUA = "https://www.ixigua.com/7035542323689685535";

    /**
     * 西瓜视频主域（证据：同上 §16.2 的样例链接主机名，以及 §16.9
     * 「头条/西瓜视频 | 样例链接（ixigua）| C（专用 Key）」一行）。
     */
    public static final String DOT_IXIGUA_COM = ".ixigua.com";

    /** 裸域（不含前导点），对应"主机恰为 ixigua.com"的情形。 */
    public static final String IXIGUA_COM = "ixigua.com";

    /**
     * 头条侧公开域。**推断**：§16.2 的样例语料里只给了 {@code ixigua.com}，
     * 没有任何 {@code toutiao.com} 样例；此处依据 §16.9「头条/西瓜视频」被并为同一通道
     * 而推断其同族域也应归该通道。调用方可用
     * {@code ToutiaoParseProvider} 的构造参数覆盖，或用
     * {@code ParseRequest.platform(Platform.TOUTIAO)} 直接指定。
     */
    public static final List<String> INFERRED_TOUTIAO_HOSTS = Collections.unmodifiableList(Arrays.asList(
            "toutiao.com",
            "www.toutiao.com",
            "m.toutiao.com"
    ));

    // ---------------------------------------------------------------- 响应模型（【观测】字段名）

    /**
     * <b>默认不使用</b>的信封候选包装键。
     *
     * <p>证据（转写 §4.2 步骤 8/10）：{@code data} 被取出**解密后**再
     * {@code Gson.fromJson(decrypted, ParseApiResponse)} —— 也就是说
     * 业务对象就是**解密后的字符串本身**，没有再套一层 {@code data}。
     * 因此本数组默认为空；只有当调用方自己的服务多包了一层时才需要设置。
     *
     * <p>（旧实现曾把 {@code {"data","result","toutiaoData"}} 当默认值，
     * 那是基于 16 章 §16.6 的信封模板做的**推断**；转写落地后该推断被证伪，
     * 故改为空数组并把原推断值保留在 javadoc 里备查。）
     */
    public static final String[] ENVELOPE_WRAPPERS = {};

    /**
     * 媒体数组键（证据：转写 §4.3「模型 ↔ 字段一览」表的 {@code ToutiaoData} 行
     * （{@code code_va 0x1f3194}）：{@code type:String title:String cover:String video:String
     * medias:List<…> videoInfo:ToutiaoVideoInfo}）。
     */
    public static final String[] MEDIA_ARRAY_KEYS = {"medias"};

    /**
     * 标题字段（证据：同上，{@code ToutiaoData.title:String}）。
     */
    public static final String[] TITLE_KEYS = {"title"};

    /**
     * 封面字段（证据：同上，{@code ToutiaoData.cover:String}）。
     */
    public static final String[] COVER_KEYS = {"cover"};

    /**
     * 主视频直链字段（证据：同上，{@code ToutiaoData.video:String}；
     * 且 §4.3 的 {@code bestVideoUrl()} 第一条就是"若 {@code video} 非空白则返回它"）。
     */
    public static final String[] VIDEO_KEYS = {"video"};

    /**
     * 类型字段（证据：同上，{@code ToutiaoData.type:String}）。
     *
     * <p>但"其取值用于区分 video / 图集"仍是【推断】，且证据中
     * **没有出现任何具体取值字面量**（转写 §6 全表亦无）。因此本实现不猜测取值，
     * 由 {@code ToutiaoParseProvider.ResultMapping} 让调用方声明。
     */
    public static final String TYPE_KEY = "type";

    /**
     * 清晰度信息对象键（证据：同上，{@code ToutiaoData.videoInfo:ToutiaoVideoInfo}）。
     */
    public static final String VIDEO_INFO_KEY = "videoInfo";

    // ------------------------------ ToutiaoVideoInfo 字段（全部【观测】）

    /**
     * 备用地址（证据：转写 §4.3 模型表 {@code ToutiaoVideoInfo} 行
     * （{@code code_va 0x1ec116} 起）：{@code backupUrl:String definition:String format:String
     * height:int width:int size:long}）。06 章 §6.3 的 DEX 字段表给出同一组字段，可互证。
     */
    public static final String VIDEO_INFO_BACKUP_URL_KEY = "backupUrl";

    /** 清晰度名（证据：同上 {@code ToutiaoVideoInfo.definition:String}）。 */
    public static final String VIDEO_INFO_DEFINITION_KEY = "definition";

    /** 封装格式（证据：同上 {@code ToutiaoVideoInfo.format:String}）。 */
    public static final String VIDEO_INFO_FORMAT_KEY = "format";

    /** 高（证据：同上 {@code ToutiaoVideoInfo.height:int}）。 */
    public static final String VIDEO_INFO_HEIGHT_KEY = "height";

    /** 字节数（证据：同上 {@code ToutiaoVideoInfo.size:long}）。 */
    public static final String VIDEO_INFO_SIZE_KEY = "size";

    /** 宽（证据：同上 {@code ToutiaoVideoInfo.width:int}）。 */
    public static final String VIDEO_INFO_WIDTH_KEY = "width";

    /**
     * 单条媒体对象内取直链的候选键。
     *
     * <p>{@code url} 有强证据：转写 §4.3 的 {@code MediaData} 行
     * （{@code code_va 0x1f5390}）为 {@code url:String separate:Integer quality:Integer}，
     * 且 §4.3 给出的 {@code bestVideoUrl()} 逻辑正是"遍历 {@code medias}，
     * 取第一个 {@code url} 非空白者"。
     *
     * <p>其余候选是【推断】：转写**没有**把 {@code ToutiaoData.medias} 的元素类型
     * 明确写成 {@code MediaData}（§4.3 的模型表里写作 {@code medias:List<…>}），
     * 所以这里保留候选列表以容忍同形但字段名不同的服务端实现。
     */
    public static final String[] MEDIA_ITEM_URL_KEYS = {
            "url", "video", "backupUrl", "playAddr", "playUrl", "videoUrl", "mainUrl"
    };

    /**
     * 单条媒体对象内的清晰度/画质候选键。
     *
     * <p>证据：转写 §4.3 的 {@code MediaData} 行给出 {@code quality:Integer}
     * 与 {@code separate:Integer}——后者语义未确定（§7 未列，属未使用字段），
     * 故只把 {@code quality} 列为有据候选，{@code definition} 为【推断】兼容项。
     */
    public static final String[] MEDIA_ITEM_DEFINITION_KEYS = {"quality", "definition"};

    // ---------------------------------------------------------------- 选优

    /**
     * <b>已废弃的推断项</b>：{@code bestVideoUrl()} 的"清晰度排序"。
     *
     * <p>转写 §4.3 已把选优规则逐条还原（{@code ToutiaoData.bestVideoUrl()}
     * {@code code_va 0x1f3194}，88 码元，{@code exact_fit=true}）：
     * <pre>
     * if (video 非空白) return video                       // StringsKt.isBlank
     * for (m in medias) if (m.url 非空白) return m.url      // MediaData.getUrl
     * return ""
     * </pre>
     * 即**没有**按清晰度排序——原实现只取"第一个非空"。因此本常量**不再被 provider 使用**，
     * 仅保留常量名以兼容已发布的 API；新代码请勿依赖它。
     *
     * @deprecated 选优规则已由转写 §4.3 确定（首非空，不排序），见
     *             {@code ToutiaoParseProvider#bestVideoUrl(org.json.JSONObject)}。
     */
    @Deprecated
    public static final List<String> INFERRED_QUALITY_ORDER = Collections.unmodifiableList(Arrays.asList(
            "1080p", "720p", "540p", "480p", "360p"
    ));

    /**
     * 判定字符串是否"非空白"。
     *
     * <p>证据：转写 §4.3 的 {@code bestVideoUrl()} 使用
     * {@code StringsKt.isBlank}（{@code #1568 @0x1f87e0}）作判据。
     * Kotlin 的 {@code isBlank()} 对 {@code null} 返回 true、对全空白返回 true；
     * 本方法在 Java 侧给出等价语义。
     */
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

    /**
     * 判定直链是否可用。**推断**：转写未给出该判定，
     * 但 11 章 §11.3 有提示语 {@code 头条解析结果没有视频地址}，
     * 说明原实现确实存在"结果里没有视频地址"这一失败分支。
     */
    public static boolean looksLikeDirectUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    // ---------------------------------------------------------------- 提示语（【观测】）

    /**
     * 失败提示（证据：{@code 09_reports/11_平台与功能清单.md} §11.3「解析结果与错误提示」列表中的
     * {@code 头条解析失败：}）。
     */
    public static final String MSG_PARSE_FAILED = "头条解析失败：";

    /**
     * "结果里没有视频地址"提示（证据：同上列表中的 {@code 头条解析结果没有视频地址}）。
     */
    public static final String MSG_NO_VIDEO_URL = "头条解析结果没有视频地址";

    /**
     * 样本被保护代码里的英文失败串（证据：06 章 §6.4「{@code libnmmp.so} 字符串池中同时存在该类的
     * 调用点…以及英文错误串」，原文 {@code Inclusive toutiao parse failed:}）。
     */
    public static final String LOG_PARSE_FAILED_EN = "Inclusive toutiao parse failed:";

    /**
     * 日志标签（证据：转写 §4.2「日志标签：{@code InclusiveVideoParse}（str#85）」，
     * 并由 §6.7「固定凭据 / 常量」表的「日志标签」行复述：
     * {@code DouyinWebParser} / {@code InclusiveVideoParse} / {@code ParseWebViewKt}；
     * 另见 11 章 §11.1 头条行的平台词 {@code 头条}）。
     */
    public static final String TAG = "InclusiveVideoParse";

    // ---------------------------------------------------------------- 判定

    /** 判定主机是否属于西瓜/头条族域（西瓜来自样例语料；头条来自 {@link #INFERRED_TOUTIAO_HOSTS}）。 */
    public static boolean isToutiaoHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase(java.util.Locale.ROOT);
        if (IXIGUA_COM.equals(h) || h.endsWith(DOT_IXIGUA_COM)) {
            return true;
        }
        for (String s : INFERRED_TOUTIAO_HOSTS) {
            if (h.equals(s)) {
                return true;
            }
        }
        return false;
    }
}
