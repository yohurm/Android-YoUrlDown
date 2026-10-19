package com.yohu.lib_urlparse.provider.instagram;

import com.yohu.lib_urlparse.api.FailureReason;
import com.yohu.lib_urlparse.api.MediaAsset;
import com.yohu.lib_urlparse.api.ParseFailure;
import com.yohu.lib_urlparse.api.Platform;
import com.yohu.lib_urlparse.api.UrlParseEngine;
import com.yohu.lib_urlparse.api.UrlParseRequest;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.lib_urlparse.spi.HttpClient;
import com.yohu.lib_urlparse.spi.HttpException;
import com.yohu.lib_urlparse.spi.HttpRequest;
import com.yohu.lib_urlparse.spi.HttpResponse;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Instagram 通道测试：**用真机抓取的嵌入页固件**（182,922 B）驱动，纯 JVM。
 *
 * <p>旧报告里 Instagram 记为"未通过"是**网络层不可达**，不是解析逻辑问题。
 * 所以这里用已抓到的固件证明解析逻辑本身是通的，把"网络不可达"与"解析失败"分开。
 */
public class InstagramEmbedProviderTest {

    private static final String SHORTCODE = "Ca3V3zGIZ08";
    private static final String INPUT = "https://www.instagram.com/p/" + SHORTCODE + "/";

    static final class FixtureHttp implements HttpClient {
        private final String html;
        private final boolean failNetwork;
        String lastUrl;

        FixtureHttp(String html, boolean failNetwork) {
            this.html = html;
            this.failNetwork = failNetwork;
        }

        @Override
        public HttpResponse execute(HttpRequest request) throws HttpException {
            lastUrl = request.url();
            if (failNetwork) {
                throw new HttpException(HttpException.Kind.CONNECT, 0, "unreachable host");
            }
            return new HttpResponse(200, html, request.url(), null, 1L);
        }
    }

    private static String fixture() throws IOException {
        InputStream in = InstagramEmbedProviderTest.class.getClassLoader()
                .getResourceAsStream("instagram_embed_googlebot.html");
        assertNotNull("缺少固件 instagram_embed_googlebot.html", in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return out.toString("UTF-8");
    }

    private static UrlParseEngine engine(HttpClient http) {
        return UrlParseEngine.builder()
                .httpClient(http)
                .provider(new InstagramEmbedProvider())
                .timeoutMs(5_000L)
                .build();
    }

    @Test
    public void parsesRealEmbedFixture() throws Exception {
        FixtureHttp http = new FixtureHttp(fixture(), false);
        UrlParseResult r = engine(http).parse(UrlParseRequest.builder(INPUT).build());

        assertEquals(Platform.INSTAGRAM, r.platform());
        assertEquals("instagram.embed", r.channel());
        assertEquals(SHORTCODE, r.workId());
        assertTrue("必须请求嵌入页（不是主站）", http.lastUrl.contains("/embed/captioned/"));

        int media = r.assets(MediaAsset.Kind.VIDEO).size() + r.assets(MediaAsset.Kind.IMAGE).size();
        assertTrue("真实嵌入页里必须解析出媒体，层次诊断=" + r.extras().get("instagram.layers"),
                media > 0);
        for (MediaAsset m : r.assets(MediaAsset.Kind.VIDEO)) {
            assertTrue("媒体地址应为 http(s)", m.url().startsWith("http"));
        }
    }

    @Test
    public void networkUnreachableIsNetworkReasonNotParseFailure() {
        FixtureHttp http = new FixtureHttp("", true);
        ParseFailure f = assertThrows(ParseFailure.class,
                () -> engine(http).parse(UrlParseRequest.builder(INPUT).build()));
        assertEquals("网络不可达必须是 NETWORK（与旧报告口径一致）",
                FailureReason.NETWORK, f.reason());
        assertTrue("说明要区分网络与解析: " + f.getMessage(), f.getMessage().contains("不可达"));
    }

    @Test
    public void emptyBodyIsUnexpectedResponse() {
        FixtureHttp http = new FixtureHttp("", false);
        ParseFailure f = assertThrows(ParseFailure.class,
                () -> engine(http).parse(UrlParseRequest.builder(INPUT).build()));
        assertEquals(FailureReason.UNEXPECTED_RESPONSE, f.reason());
    }

    @Test
    public void noMediaGivesLayerDiagnostics() {
        FixtureHttp http = new FixtureHttp("<html><body>empty embed</body></html>", false);
        ParseFailure f = assertThrows(ParseFailure.class,
                () -> engine(http).parse(UrlParseRequest.builder(INPUT).build()));
        assertEquals(FailureReason.NO_MEDIA_FOUND, f.reason());
        assertTrue("应带层次诊断: " + f.getMessage(), f.getMessage().contains("层次诊断"));
    }

    @Test
    public void acceptsReelAndTvForms() throws Exception {
        String html = fixture();
        UrlParseResult reel = engine(new FixtureHttp(html, false))
                .parse(UrlParseRequest.builder("https://www.instagram.com/reel/CxYz123_-ab/").build());
        assertEquals("CxYz123_-ab", reel.workId());
        UrlParseResult tv = engine(new FixtureHttp(html, false))
                .parse(UrlParseRequest.builder("https://instagram.com/tv/CabcDEF1234/").build());
        assertEquals("CabcDEF1234", tv.workId());
    }

    @Test
    public void declinesUnrelatedInput() {
        InstagramEmbedProvider p = new InstagramEmbedProvider();
        assertFalse(p.canHandle(null, UrlParseRequest.builder("https://example.test/x").build()));
        assertTrue(p.canHandle(null, UrlParseRequest.builder(INPUT).build()));
    }
}
