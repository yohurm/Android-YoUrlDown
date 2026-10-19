package com.yohu.lib_urlparse.rules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 云 API 通道（{@code Platform.KUAISHOU / WEIBO / BILIBILI / XIGUA / XIAOHONGSHU /
 * PIPIXIA / YOUTUBE} 等）的规则常量。
 *
 * <p><b>这个类为什么这么薄</b>：证据显示，样本**本身并不含平台清单**——
 * 接口清单由服务端下发并可本地缓存：
 * <pre>
 * SimpleHelperBridge.setConfig(JSONObject)
 *     键：短视频解析接口 / 图集解析接口 / 影视解析接口 / 官方影视
 *     元素：{名称, 请求方式, 请求头, 接口, 网址}          ← DEX 字符串池
 *     拉取：http://api.1foo.com/xl_config/config.php（+ configV2.php）
 *     回退：加载服务器配置失败 / 读取本地配置失败 / 保持使用本地/default配置
 * </pre>
 * （证据：{@code 09_reports/16_解析规则全集.md} §16.8；该节对该机制标为【推断】，
 * 对字面量标为【观测】。）同理 §16.9 把快手/微博/B 站/小红书/皮皮虾等列为
 * 「服务端」规则载体，并注明"APK 不含平台清单"。
 *
 * <p>因此本类**只收录能从证据里落地为字面量的部分**：
 * <ol>
 *   <li>{@link #SAMPLE_HOSTS}：唯一可从"样例链接语料"直接读出的分享域（16 章 §16.2）；</li>
 *   <li>{@link #SERVER_CONFIG_KEYS} / {@link #SERVER_CONFIG_ELEMENT_KEYS}：
 *       §16.8 逐字给出的配置下发结构（可直接用于对接**你自己的**配置服务）；</li>
 *   <li>若干【观测】提示语（11 章 §11.3、16 章 §16.7），用于把失败原因说清楚。</li>
 * </ol>
 * 其余一切（端点、字段名、鉴权）都必须由调用方注入，见
 * {@code CloudApiParseProvider}。
 */
public final class CloudApiRules {

    private CloudApiRules() {
    }

    // ---------------------------------------------------------------- 样例语料里的分享域

    /**
     * 从**样例链接语料**里直接读出的分享域 → 平台。
     *
     * <p>证据：{@code 09_reports/16_解析规则全集.md} §16.2「平台覆盖面：被抽走代码里的
     * **样例链接语料**」表，逐行原文：
     * <ul>
     *   <li>快手（短链）：{@code https://v.kuaishou.com/g2c244} → {@code v.kuaishou.com}；</li>
     *   <li>B 站：{@code https://www.bilibili.com/video/BV1HU4y1K7TT?spm_id_from=...}
     *       → {@code bilibili.com}；</li>
     *   <li>YouTube：{@code https://youtu.be/6f4oKjlLYK0} → {@code youtu.be}；</li>
     *   <li>西瓜视频：{@code https://www.ixigua.com/7035542323689685535?logTag=...}
     *       → {@code ixigua.com}。</li>
     * </ul>
     *
     * <p>B 站另有一条**直接 API 端点**证据：§16.9「B 站 | {@code bilibili.com} +
     * {@code api.bilibili.com/x/web-interface/view?bvid=} | 直接 API」，
     * 见 {@link #BILIBILI_VIEW_API}（该端点是公开 API，不含凭据）。
     *
     * <p><b>刻意留空</b>：微博、小红书、皮皮虾在证据里**只有本机私有目录路径**
     * （{@code /Android/data/com.sina.weibo/}、{@code /Android/data/com.xingin.xhs/cache/}；
     * 11 章 §11.1、13 章「未决问题清单」），**没有任何分享链接主机名**。
     * 故这里不为它们编造域名；调用方需用
     * {@code CloudApiMapping.Builder.host(...)} 自行登记，或用
     * {@code ParseRequest.platform(...)} 显式指定平台。
     */
    public static final Map<String, String> SAMPLE_HOSTS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("v.kuaishou.com", "kuaishou");
        m.put("bilibili.com", "bilibili");
        m.put("youtu.be", "youtube");
        m.put("ixigua.com", "ixigua");
        m.put("toutiao.com", "toutiao");
        SAMPLE_HOSTS = Collections.unmodifiableMap(m);
    }

    /**
     * B 站公开取详情端点（证据：{@code 09_reports/16_解析规则全集.md} §16.9 B 站行，
     * 原文 {@code https://api.bilibili.com/x/web-interface/view?bvid=}；
     * 11 章 §11.1 B 站行同一字面量，标注属于 @{code libnmmp} + DEX 字符串池）。
     *
     * <p>仅作**来源记录**：{@link #SAMPLE_HOSTS} 用它引出 {@code bilibili.com} 亦为该族域。
     * 本工程不请求它。
     */
    public static final String BILIBILI_VIEW_API =
            "https://api.bilibili.com/x/web-interface/view?bvid=";

    /** 由 {@link #BILIBILI_VIEW_API} 得到的主域，用于主机判定。 */
    public static final String BILIBILI_HOST = "bilibili.com";

    // ---------------------------------------------------------------- 服务端配置下发结构

    /**
     * 服务端配置的顶层键（证据：{@code 09_reports/16_解析规则全集.md} §16.8，
     * 原文「键：短视频解析接口 / 图集解析接口 / 影视解析接口 / 官方影视」）。
     */
    public static final List<String> SERVER_CONFIG_KEYS = Collections.unmodifiableList(Arrays.asList(
            "短视频解析接口",
            "图集解析接口",
            "影视解析接口",
            "官方影视"
    ));

    /**
     * 服务端配置里**每个元素**的键（证据：同上 §16.8，原文
     * 「元素：{名称, 请求方式, 请求头, 接口, 网址} ← DEX 字符串池」）。
     *
     * <p>这正是"平台可远程增删"的载体，也正是本 provider 的
     * {@code RequestMapping} 想要对齐的字段：{@code 网址} ≈ url 字段、
     * {@code 请求方式} ≈ method、{@code 请求头} ≈ headers。
     */
    public static final List<String> SERVER_CONFIG_ELEMENT_KEYS = Collections.unmodifiableList(Arrays.asList(
            "名称", "请求方式", "请求头", "接口", "网址"
    ));

    // ---------------------------------------------------------------- 提示语（【观测】）

    /** 结果校验提示（证据：11 章 §11.3 列表中的 {@code 解析失败，失败原因：}）。 */
    public static final String MSG_PARSE_FAILED = "解析失败，失败原因：";

    /** 取链失败提示（证据：16 章 §16.7「结果校验」行 {@code 无法获取视频链接}）。 */
    public static final String MSG_NO_VIDEO_URL = "无法获取视频链接";

    /** 只拿到图片时的提示（证据：16 章 §16.7「结果校验」行 {@code 图片数据为空}）。 */
    public static final String MSG_NO_IMAGES = "图片数据为空";

    /** 服务端返回为空（证据：16 章 §16.7「响应解析」行 {@code 解析服务返回为空}）。 */
    public static final String MSG_EMPTY_RESPONSE = "解析服务返回为空";

    /**
     * 通用短视频 API Key 的英文标识（证据：06 章 §6.1
     * {@code InclusiveVideoParseHelper.SHORT_VIDEO_API_KEY}）——
     * 只作为"原本需要凭据"的证据记录，**不包含其值**。
     */
    public static final String KEY_NAME_SHORT_VIDEO = "SHORT_VIDEO_API_KEY";

    /**
     * 主机是否命中 {@link #SAMPLE_HOSTS}（判等或后缀匹配）。
     *
     * @return 命中的平台 id；未命中返回 null
     */
    public static String platformIdOfHost(String host) {
        if (host == null) {
            return null;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : SAMPLE_HOSTS.entrySet()) {
            String registered = e.getKey();
            if (h.equals(registered) || h.endsWith("." + registered)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 便捷：把 {@link #SAMPLE_HOSTS} 反查成该平台的候选主机名列表（含子域通配提示）。 */
    public static List<String> hostsFor(String platformId) {
        List<String> out = new ArrayList<>();
        if (platformId == null) {
            return out;
        }
        for (Map.Entry<String, String> e : SAMPLE_HOSTS.entrySet()) {
            if (platformId.equalsIgnoreCase(e.getValue())) {
                out.add(e.getKey());
            }
        }
        return out;
    }
}
