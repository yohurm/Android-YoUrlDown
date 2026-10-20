package com.yohu.lib_urlparse.rules;

/**
 * 微信视频号通道的规则常量。
 *
 * <p>视频号没有可匿名访问的取流接口：本库不持有任何账号凭据，
 * 因此该通道只给出明确的"能力受限"说明（见 {@link #CLOUD_CHANNEL_NOTE}），
 * 需要时由宿主通过 {@code EngineConfig.cloudEndpoint(...)} 接入自己的服务。
 */
public final class WeChatRules {

    private WeChatRules() {
    }

    /**
     * 视频号不可直接解析的原因（作为 CREDENTIALS_MISSING 的说明文本）。
     *
     * <ul>
     *   <li>取流必须持有腾讯侧登录会话，分享短链（{@code https://weixin.qq.com/sph/<id>}）
     *       本身不足以匿名取流；</li>
     *   <li>视频文件是加密 MP4，需要同一次响应里的 {@code decode_key} 才能解出可播放文件。</li>
     * </ul>
     */
    public static final String CLOUD_CHANNEL_NOTE =
            "WeChat Channels extraction needs a logged-in Tencent/WeChat session: the public "
                    + "official interface is session-bound, and third-party resolvers merely host "
                    + "such a session server-side. A share link (weixin.qq.com/sph/...) alone is "
                    + "not enough, and the media is an encrypted MP4 requiring the matching "
                    + "decode_key. This library holds no account credentials by design.";
}
