package com.yohu.lib_urlparse.provider.wechat;

import com.yohu.lib_urlparse.api.FailureReason;
import com.yohu.lib_urlparse.api.ParseFailure;
import com.yohu.lib_urlparse.api.UrlParseRequest;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.lib_urlparse.rules.WeChatRules;
import com.yohu.lib_urlparse.spi.ParseContext;
import com.yohu.lib_urlparse.spi.ParseProvider;
import com.yohu.lib_urlparse.spi.ProviderPriority;
import com.yohu.lib_urlparse.util.UrlUtils;

/**
 * 微信视频号：**如实报告"需要登录态"**，不做无效尝试。
 *
 * <p>为什么不实现取流：
 * <ul>
 *   <li>视频号取流必须持有腾讯侧登录会话——公开的第三方实现（含开源项目）也是把会话放在服务端代跑；</li>
 *   <li>分享短链（{@code weixin.qq.com/sph/...}）本身不足以匿名取流；</li>
 *   <li>视频文件是**加密 MP4**，需要同一次响应里的 {@code decode_key} 才能解出可播放文件。</li>
 * </ul>
 *
 * <p>这三点都不是"再写点代码就能绕过"的，所以这里抛
 * {@link FailureReason#CREDENTIALS_MISSING} 并把原因说清楚，而不是返回空结果、
 * 或去访问任何第三方服务。这与旧报告里"视频号未验证（需 root/登录）"的结论一致。
 */
public final class WechatChannelsProvider implements ParseProvider {

    @Override
    public String channel() {
        return "wechat.channels";
    }

    @Override
    public ProviderPriority priority() {
        return ProviderPriority.DEDICATED_API;
    }

    @Override
    public boolean canHandle(ParseContext context, UrlParseRequest request) {
        if (request == null) {
            return false;
        }
        String url = context != null && context.resolvedUrl() != null
                ? context.resolvedUrl() : request.rawInput();
        String host = UrlUtils.host(url);
        if (host == null) {
            return false;
        }
        if (host.equals("channels.weixin.qq.com") || host.endsWith(".channels.weixin.qq.com")) {
            return true;
        }
        // 分享短链落在 weixin.qq.com/sph/...
        return host.endsWith("weixin.qq.com") && url.contains("/sph");
    }

    @Override
    public UrlParseResult parse(ParseContext context, UrlParseRequest request) throws ParseFailure {
        throw new ParseFailure(FailureReason.CREDENTIALS_MISSING, channel(),
                WeChatRules.CLOUD_CHANNEL_NOTE);
    }
}
