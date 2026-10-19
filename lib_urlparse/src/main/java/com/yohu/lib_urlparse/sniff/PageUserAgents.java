package com.yohu.lib_urlparse.sniff;

import java.util.Locale;

/**
 * 按站点选择 User-Agent。
 *
 * <p><b>为什么这是必需的，而不是可选优化</b>：实测两个平台的失败原因是同一个——
 * 服务端按 UA 决定给不给内容：
 * <ul>
 *   <li><b>微信公众号</b>：用桌面 Chrome UA 访问文章链接，返回的是通用外壳
 *       （标题只有"微信公众平台"，并且加载了 {@code error.js}），正文根本不发。
 *       微信只对带 {@code MicroMessenger} 的 UA 返回文章正文。</li>
 *   <li><b>快手分享页</b>：桌面 UA 下页面几乎什么都不请求（实测只有 2 个请求：自身 + favicon），
 *       拿不到任何可判定为媒体的地址。</li>
 * </ul>
 *
 * <p>这些都是**公开的 UA 字符串**，不涉及任何凭据或私有服务——属于"像正常客户端一样访问"。
 *
 * <p>归属说明：本类是本工程新增的工程手段，不是从样本还原的规则。
 * 样本的 {@code ParseWebView} 只用了固定的桌面 Chrome UA（报告 08），
 * 所以它对这些站点同样拿不到内容——这也解释了为什么样本对这些平台的"支持"
 * 实际依赖云 API 与远程脚本，而不是嗅探。
 */
public final class PageUserAgents {

    private PageUserAgents() {
    }

    /** 微信内置浏览器：微信服务端据此返回文章正文。 */
    public static final String WECHAT_IN_APP =
            "Mozilla/5.0 (Linux; Android 15; NEPI4E0106 Build/V1TQ35H.114-34-3) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/139.0.0.0 "
                    + "Mobile Safari/537.36 MicroMessenger/8.0.49.2600(0x2800313D) "
                    + "WeChat/arm64 Weixin NetType/WIFI Language/zh_CN ABI/arm64";

    /** 移动端 Chrome：多数国内站点的分享页只对移动 UA 渲染。 */
    public static final String MOBILE_CHROME =
            "Mozilla/5.0 (Linux; Android 15; NEPI4E0106) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36";

    /** 桌面 Chrome：样本 {@code ParseWebView} 用的就是这一类（报告 08）。 */
    public static final String DESKTOP_CHROME =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36";

    /**
     * 选一个 UA。
     *
     * @param url 目标页面；null 或识别不出时返回桌面 Chrome（与样本一致）
     */
    public static String forUrl(String url) {
        String host = hostOf(url);
        if (host.isEmpty()) {
            return DESKTOP_CHROME;
        }
        // 微信域：文章正文只给微信客户端
        if (host.endsWith("mp.weixin.qq.com") || host.endsWith("weixin.qq.com")
                || host.endsWith("weixin110.qq.com")) {
            return WECHAT_IN_APP;
        }
        // 分享页只对移动 UA 渲染的站点
        if (host.endsWith("kuaishou.com") || host.endsWith("chenzhongtech.com")
                || host.endsWith("xiaohongshu.com") || host.endsWith("xhslink.com")
                || host.endsWith("weibo.cn") || host.endsWith("weibo.com")
                || host.endsWith("pipix.com") || host.endsWith("ippx")
                || host.endsWith("douyin.com") || host.endsWith("iesdouyin.com")
                || host.endsWith("toutiao.com") || host.endsWith("ixigua.com")
                || host.endsWith("weishi.com") || host.endsWith("qq.com")) {
            return MOBILE_CHROME;
        }
        return DESKTOP_CHROME;
    }

    /** 是否是"微信内置浏览器"UA（供日志与测试判断）。 */
    public static boolean isWeChatUa(String ua) {
        return ua != null && ua.contains("MicroMessenger");
    }

    static String hostOf(String url) {
        if (url == null) {
            return "";
        }
        String s = url.trim().toLowerCase(Locale.ROOT);
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        int at = s.indexOf('@');
        if (at >= 0) {
            s = s.substring(at + 1);
        }
        int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(0, colon);
        }
        return s;
    }
}
