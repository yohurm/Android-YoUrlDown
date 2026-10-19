package com.yohu.lib_urlparse.provider.wechat;

import com.yohu.lib_urlparse.api.FailureReason;
import com.yohu.lib_urlparse.api.MediaAsset;
import com.yohu.lib_urlparse.api.ParseFailure;
import com.yohu.lib_urlparse.api.Platform;
import com.yohu.lib_urlparse.api.UrlParseEngine;
import com.yohu.lib_urlparse.api.UrlParseRequest;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.lib_urlparse.spi.HttpClient;
import com.yohu.lib_urlparse.spi.HttpRequest;
import com.yohu.lib_urlparse.spi.HttpResponse;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 微信两条通道的测试。
 *
 * <p>公众号：用按已知页面形态构造的 HTML（内联 mpvideo 视频 + mmbiz 图片 + og:title + nickname）。
 * <b>注意</b>：这里缺一份当前真实文章固件，真实页面结构可能已有差异；真机验证需有效文章链接。
 *
 * <p>视频号：验证"如实报缺凭据"而不是假装成功或访问第三方服务。
 */
public class WechatProviderTest {

    private static final String ARTICLE = "https://mp.weixin.qq.com/s/AbCdEfGhIjKlMn";

    static final class FakeHttp implements HttpClient {
        final String body;
        int calls;

        FakeHttp(String body) {
            this.body = body;
        }

        @Override
        public HttpResponse execute(HttpRequest request) {
            calls++;
            return new HttpResponse(200, body, request.url(), null, 1L);
        }
    }

    private static UrlParseEngine engine(HttpClient http, com.yohu.lib_urlparse.spi.ParseProvider p) {
        return UrlParseEngine.builder().httpClient(http).provider(p).timeoutMs(5_000L).build();
    }

    private static String articleHtml() {
        return "<html><head>"
                + "<meta property=\"og:title\" content=\"一篇文章标题\">"
                + "</head><body>"
                + "<script>var nickname = \"某公众号\"; var msg_title = \"一篇文章标题\";</script>"
                + "<p><img data-src=\"https://mmbiz.qpic.cn/mmbiz_jpg/abc/640?wx_fmt=jpeg\"></p>"
                + "<mpvideo><video src=\"https://mpvideo.qpic.cn/0b2e/abc.mp4?dis_k=x&amp;dis_t=1\"></video></mpvideo>"
                + "</body></html>";
    }

    @Test
    public void parsesWechatArticleIntoVideoAndImages() throws Exception {
        FakeHttp http = new FakeHttp(articleHtml());
        UrlParseResult r = engine(http, new WechatMpProvider())
                .parse(UrlParseRequest.builder(ARTICLE).build());

        assertEquals(Platform.WECHAT_MP, r.platform());
        assertEquals("wechat.mp", r.channel());
        assertEquals("一篇文章标题", r.title());
        assertEquals("某公众号", r.author().nickname());
        assertEquals(1, r.assets(MediaAsset.Kind.VIDEO).size());
        assertTrue(r.assets(MediaAsset.Kind.VIDEO).get(0).url().contains("mpvideo.qpic.cn"));
        assertEquals(1, r.assets(MediaAsset.Kind.IMAGE).size());
        assertTrue(r.assets(MediaAsset.Kind.IMAGE).get(0).url().contains("mmbiz.qpic.cn"));
    }

    @Test
    public void htmlEntityInUrlIsDecoded() throws Exception {
        FakeHttp http = new FakeHttp(articleHtml());
        UrlParseResult r = engine(http, new WechatMpProvider())
                .parse(UrlParseRequest.builder(ARTICLE).build());
        String url = r.assets(MediaAsset.Kind.VIDEO).get(0).url();
        assertFalse("URL 里的 &amp; 必须还原: " + url, url.contains("&amp;"));
        assertTrue(url.contains("&"));
    }

    @Test
    public void emptyArticleIsUnexpectedResponse() {
        FakeHttp http = new FakeHttp("");
        ParseFailure f = assertThrows(ParseFailure.class,
                () -> engine(http, new WechatMpProvider())
                        .parse(UrlParseRequest.builder(ARTICLE).build()));
        assertEquals(FailureReason.UNEXPECTED_RESPONSE, f.reason());
    }

    @Test
    public void articleWithoutMediaIsNoMediaFound() {
        FakeHttp http = new FakeHttp("<html><body>纯文字消息</body></html>");
        ParseFailure f = assertThrows(ParseFailure.class,
                () -> engine(http, new WechatMpProvider())
                        .parse(UrlParseRequest.builder(ARTICLE).build()));
        assertEquals(FailureReason.NO_MEDIA_FOUND, f.reason());
    }

    @Test
    public void mpProviderDeclinesChannelsLinks() {
        WechatMpProvider p = new WechatMpProvider();
        assertTrue(p.canHandle(null, UrlParseRequest.builder(ARTICLE).build()));
        assertFalse("视频号链接不属于公众号通道",
                p.canHandle(null, UrlParseRequest.builder(
                        "https://channels.weixin.qq.com/web/pages/feed?x=1").build()));
    }

    @Test
    public void channelsProviderReportsCredentialsMissingHonestly() {
        String channelsUrl = "https://channels.weixin.qq.com/web/pages/feed?x=1";
        WechatChannelsProvider p = new WechatChannelsProvider();
        assertTrue(p.canHandle(null, UrlParseRequest.builder(channelsUrl).build()));
        assertFalse(p.canHandle(null, UrlParseRequest.builder(ARTICLE).build()));

        FakeHttp http = new FakeHttp("{}");
        ParseFailure f = assertThrows(ParseFailure.class,
                () -> engine(http, p).parse(UrlParseRequest.builder(channelsUrl).build()));
        assertEquals(FailureReason.CREDENTIALS_MISSING, f.reason());
        assertTrue("必须说明需要登录态: " + f.getMessage(),
                f.getMessage().contains("session") || f.getMessage().contains("登录"));
        assertEquals("不允许为此发起任何网络请求", 0, http.calls);
    }
}
