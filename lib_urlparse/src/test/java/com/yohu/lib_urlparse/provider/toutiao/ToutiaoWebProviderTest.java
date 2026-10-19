package com.yohu.lib_urlparse.provider.toutiao;

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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 头条通道的测试：**用真机抓取的两份固件**驱动，不联网、不启动 Android。
 *
 * <p>这组测试同时证明了一件事：把 WebView 从核心剥离之后，provider 可以在纯 JVM 上完整跑通
 * 「页面 → RENDER_DATA → 已签名 VOD → 播放地址」这条链路。旧工程里这条链路只能靠真机验证。
 */
public class ToutiaoWebProviderTest {

    private static final String ITEM_ID = "7035542323689685535";
    private static final String PAGE_URL = "https://m.toutiao.com/i" + ITEM_ID + "/";
    private static final String INPUT = "https://www.ixigua.com/" + ITEM_ID;

    /** 按 URL 返回固件的假 HTTP 客户端。 */
    static final class FixtureHttp implements HttpClient {
        private final String pageHtml;
        private final String vodJson;
        int pageCalls;
        int vodCalls;

        FixtureHttp(String pageHtml, String vodJson) {
            this.pageHtml = pageHtml;
            this.vodJson = vodJson;
        }

        @Override
        public HttpResponse execute(HttpRequest request) {
            String url = request.url();
            if (url.startsWith("https://vod.bytedanceapi.com/")) {
                vodCalls++;
                return new HttpResponse(200, vodJson, url, null, 1L);
            }
            pageCalls++;
            return new HttpResponse(200, pageHtml, url, null, 1L);
        }
    }

    private static String fixture(String name) throws IOException {
        InputStream in = ToutiaoWebProviderTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull("缺少测试固件: " + name, in);
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
                .provider(new ToutiaoWebProvider())
                .timeoutMs(5_000L)
                .build();
    }

    @Test
    public void parsesRealPageAndSignedVodIntoPlayableUrls() throws Exception {
        FixtureHttp http = new FixtureHttp(fixture("toutiao_mobile_page.html"),
                fixture("toutiao_vod_response.json"));
        UrlParseResult r = engine(http).parse(UrlParseRequest.builder(INPUT).build());

        assertEquals(Platform.XIGUA, r.platform());   // 输入是 ixigua 域名
        assertEquals("toutiao.webpage", r.channel());
        assertEquals(ITEM_ID, r.workId());
        assertNotNull(r.title());
        assertTrue("标题应含作品内容: " + r.title(), r.title().contains("社会性死亡"));

        List<MediaAsset> videos = r.assets(MediaAsset.Kind.VIDEO);
        assertFalse("必须解析出至少一个视频地址", videos.isEmpty());
        MediaAsset v = videos.get(0);
        assertTrue("播放地址应是 http(s)", v.url().startsWith("http"));
        assertTrue("VOD 给的应是 mp4", "video/mp4".equals(v.mimeType()));
        assertEquals("时长应来自页面 articleInfo（毫秒）", 251_000L, v.durationMs());
        assertTrue("分辨率应被解析出来", v.width() > 0 && v.height() > 0);

        List<MediaAsset> covers = r.assets(MediaAsset.Kind.COVER);
        assertFalse("封面不应缺失", covers.isEmpty());

        // 两次请求：移动页 + VOD（证明链路真的走了两步）
        assertEquals(1, http.pageCalls);
        assertEquals(1, http.vodCalls);
        // trace 必须记下成功的那一跳
        assertTrue(r.trace().hasSuccess());
    }

    @Test
    public void acceptsToutiaoHostToo() throws Exception {
        FixtureHttp http = new FixtureHttp(fixture("toutiao_mobile_page.html"),
                fixture("toutiao_vod_response.json"));
        // 输入换成 m.toutiao.com 时平台应识别为头条
        UrlParseResult r = engine(http).parse(UrlParseRequest.builder(PAGE_URL).build());
        assertEquals(Platform.TOUTIAO, r.platform());
        assertFalse(r.assets(MediaAsset.Kind.VIDEO).isEmpty());
    }

    @Test
    public void emptyPageIsUnexpectedResponse() throws Exception {
        FixtureHttp http = new FixtureHttp("", "{}");
        ParseFailure f = assertThrows(ParseFailure.class,
                () -> engine(http).parse(UrlParseRequest.builder(INPUT).build()));
        assertEquals(FailureReason.UNEXPECTED_RESPONSE, f.reason());
        assertTrue(f.getMessage().contains("空"));
    }

    @Test
    public void pageWithoutRenderDataIsUnexpectedResponse() throws Exception {
        FixtureHttp http = new FixtureHttp("<html><body>nothing</body></html>", "{}");
        ParseFailure f = assertThrows(ParseFailure.class,
                () -> engine(http).parse(UrlParseRequest.builder(INPUT).build()));
        assertEquals(FailureReason.UNEXPECTED_RESPONSE, f.reason());
        assertTrue(f.getMessage().contains("RENDER_DATA"));
    }

    @Test
    public void declinesUnrelatedInput() {
        ToutiaoWebProvider p = new ToutiaoWebProvider();
        assertFalse(p.canHandle(null, UrlParseRequest.builder("https://example.test/x").build()));
        assertTrue("平台提示应直接认领",
                p.canHandle(null, UrlParseRequest.builder("https://example.test/x")
                        .platformHint(Platform.TOUTIAO).build()));
        assertTrue("头条链接应认领",
                p.canHandle(null, UrlParseRequest.builder(PAGE_URL).build()));
    }
}
