package com.yohu.lib_urlparse.rules;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 微信视频号（wxSph）通道的规则常量。
 *
 * <p><b>出处</b>：{@code com.one.downloadtools.api.wxSph.Mytool} 及其同包类的
 * VMP 字节码反汇编。已确认的可复用规则：
 * <ul>
 *   <li>{@code Mytool.decodeWebBase64String(String):String}（块 {@code 0x309892}，33 码元，
 *       exact_fit=true）实测等价于
 *       {@code new String(Base64.decode(s.getBytes(UTF_8), 10), UTF_8)}，
 *       其中 {@code 10 = NO_WRAP | URL_SAFE}（Android Base64 标志位：NO_WRAP=2、URL_SAFE=8）；</li>
 *   <li>{@code Mytool} 还导出 {@code bytesToHex(byte[])}、{@code isPngTs(BufferedInputStream)}、
 *       {@code getGenPathSize}/{@code getGenPathSurplusSize}（内部引用 {@code "AES"}）、
 *       {@code haomiaotofen(long)}（毫秒→时分秒格式化）、{@code getLocalip(Context)}
 *       等辅助方法。</li>
 * </ul>
 *
 * <p><b>未纳入的部分</b>：该通道真正的取流地址来自样本自有的服务端
 * （反汇编常量中的 {@code WechatHost} 指向作者私有 IP:端口）。本实现不内置该地址，
 * 需要时由调用方通过 {@code EngineConfig.cloudEndpoint(...)} 注入自己的服务；
 * 见 {@link #CLOUD_CHANNEL_NOTE}。
 */
public final class WeChatRules {

    private WeChatRules() {
    }

    /** Android {@code Base64.decode} 标志：NO_WRAP(2) | URL_SAFE(8) = 10（证据：反汇编中的立即数 10）。 */
    public static final int BASE64_FLAGS = 10;

    /** 反汇编中该通道使用的字符集字面量。 */
    public static final String CHARSET_UTF8 = "UTF-8";

    /** 明文标记：视频号分享链接常见主机（用于 canHandle 判定，均为公开域名）。 */
    public static final List<String> HOSTS = Collections.unmodifiableList(Arrays.asList(
            "channels.weixin.qq.com",
            "finder.video.qq.com",
            "mp.weixin.qq.com",
            "weixin.qq.com"
    ));

    /** 分享文本里常见的视频号标识片段（公开的 URL 形态，用于保守判定）。 */
    public static final List<String> PATH_HINTS = Collections.unmodifiableList(Arrays.asList(
            "/s/", "/finder", "/sph", "/channels"
    ));

    /**
     * 说明：样本把真正的取流请求发往作者私有服务。本实现只保留"本地解码 + 可配置远端"，
     * 因此需要云通道时请设置自己的 endpoint。
     *
     * <p><b>为什么"没有登录态"是根本原因（而不是"缺一个 endpoint"）</b>：
     * 公开的第三方实现（含开源项目）给出的默认流程是
     * "**腾讯侧登录态** → 视频号官方接口"，自建解析器只是把这层登录态放到服务端代跑。
     * 也就是说：视频号的取流**必须持有腾讯侧登录会话**；分享短链
     * （{@code https://weixin.qq.com/sph/<id>}）本身不足以匿名取流。
     * 另外视频文件是**加密 MP4**，需要同一次响应里的 {@code decode_key} 才能解出可播放文件。
     *
     * <p>这两点都不是本引擎能在"不持有任何账号凭据"的前提下绕过的，
     * 所以这里如实抛出 CREDENTIALS_MISSING，而不是去访问任何第三方服务。
     */
    public static final String CLOUD_CHANNEL_NOTE =
            "WeChat Channels extraction needs a logged-in Tencent/WeChat session: the public "
                    + "official interface is session-bound, and third-party resolvers merely host "
                    + "such a session server-side. A share link (weixin.qq.com/sph/...) alone is "
                    + "not enough, and the media is an encrypted MP4 requiring the matching "
                    + "decode_key. This library holds no account credentials by design.";

    /** 判定主机是否属于视频号相关域。 */
    public static boolean isWeChatHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase(java.util.Locale.ROOT);
        for (String s : HOSTS) {
            if (h.equals(s) || h.endsWith("." + s)) {
                return true;
            }
        }
        return false;
    }
}
