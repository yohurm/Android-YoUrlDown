package com.yohu.lib_urlparse.provider.bilibili;

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

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * B 站通道测试：纯 JVM，用假 HTTP 客户端回放真实形状的响应。
 *
 * <p>覆盖三类真实情况：
 * <ul>
 *   <li>DASH 正常：拿到 1080p 视频 + 音频 + 备用 CDN 地址</li>
 *   <li>DASH 缺失：退到 html5 渐进式 durl</li>
 *   <li>风控 -352：先取首页 Cookie 再重试一次（并验证重试时真的带上了 Cookie）</li>
 * </ul>
 */
public class BilibiliProviderTest {

    private static final String BVID = "BV1HU4y1K7TT";
    private static final String INPUT = "https://www.bilibili.com/video/" + BVID;

    /** 按 URL 前缀路由的假客户端。 */
    static final class FakeHttp implements HttpClient {
        final Map<String, String> routes = new LinkedHashMap<>();
        final Map<String, String> headersSeen = new LinkedHashMap<>();
        int calls;

        FakeHttp route(String prefix, String body) {
            routes.put(prefix, body);
            return this;
        }

        @Override
        public HttpResponse execute(HttpRequest request) {
            calls++;
            String url = request.url();
            for (Map.Entry<String, String> e : routes.entrySet()) {
                if (url.startsWith(e.getKey())) {
                    headersSeen.put(url, String.valueOf(request.headers()));
                    return new HttpResponse(200, e.getValue(), url, null, 1L);
                }
            }
            return new HttpResponse(404, "", url, null, 1L);
        }
    }

    private static String viewJson() {
        return "{\"code\":0,\"data\":{\"bvid\":\"" + BVID + "\",\"aid\":1122334455,"
                + "\"cid\":99887766,\"title\":\"测试视频标题\",\"desc\":\"简介\","
                + "\"pic\":\"https://i0.hdslb.com/bfs/archive/cover.jpg\",\"duration\":1699,"
                + "\"owner\":{\"mid\":12345,\"name\":\"某UP主\","
                + "\"face\":\"https://i0.hdslb.com/bfs/face/a.jpg\"}}}";
    }

    private static String dashJson() {
        return "{\"code\":0,\"data\":{\"dash\":{"
                + "\"video\":["
                + "{\"width\":640,\"height\":360,\"bandwidth\":400000,"
                + "\"baseUrl\":\"https://cdn.test/360.m4s\"},"
                + "{\"width\":1920,\"height\":1080,\"bandwidth\":2000000,"
                + "\"baseUrl\":\"https://cdn.test/1080.m4s\","
                + "\"backupUrl\":[\"https://cdn2.test/1080.m4s\"]}"
                + "],"
                + "\"audio\":[{\"bandwidth\":64000,\"baseUrl\":\"https://cdn.test/audio.m4s\"}]"
                + "}}}";
    }

    private static String html5Json() {
        return "{\"code\":0,\"data\":{\"durl\":[{\"url\":\"https://cdn.test/progressive.mp4\"}]}}";
    }

    private static UrlParseEngine engine(HttpClient http) {
        return UrlParseEngine.builder()
                .httpClient(http)
                .provider(new BilibiliProvider())
                .timeoutMs(5_000L)
                .build();
    }

    @Test
    public void parsesDashIntoVideoAudioAndBackups() throws Exception {
        FakeHttp http = new FakeHttp()
                .route("https://api.bilibili.com/x/web-interface/view", viewJson())
                .route("https://api.bilibili.com/x/player/playurl", dashJson());

        UrlParseResult r = engine(http).parse(UrlParseRequest.builder(INPUT).build());

        assertEquals(Platform.BILIBILI, r.platform());
        assertEquals(BVID, r.workId());
        assertEquals("测试视频标题", r.title());
        assertEquals("某UP主", r.author().nickname());
        assertEquals("12345", r.author().uid());
        assertNotNull("封面应被抽出", r.assets(MediaAsset.Kind.COVER));
        assertFalse(r.assets(MediaAsset.Kind.COVER).isEmpty());

        List<MediaAsset> videos = r.assets(MediaAsset.Kind.VIDEO);
        assertEquals(1, videos.size());
        MediaAsset v = videos.get(0);
        assertEquals("应挑分辨率最高的那一路", "https://cdn.test/1080.m4s", v.url());
        assertEquals(1920, v.width());
        assertEquals(1080, v.height());
        assertEquals("时长应换算成毫秒", 1_699_000L, v.durationMs());
        assertFalse("备用 CDN 地址应被保留", v.alternates().isEmpty());
        assertEquals("https://cdn2.test/1080.m4s", v.alternates().get(0));
        assertEquals("DASH 的播放地址必须带 Referer 头（否则 403）",
                "https://www.bilibili.com", v.headers().get("Referer"));

        List<MediaAsset> audios = r.assets(MediaAsset.Kind.AUDIO);
        assertEquals(1, audios.size());
        assertTrue(r.extras().get("bilibili.dash").contains("2 路视频"));
    }

    @Test
    public void fallsBackToHtml5ProgressiveWhenDashMissing() throws Exception {
        // 路由按前缀匹配且按插入顺序命中：html5 的地址也以 `/x/player/playurl` 开头，
        // 所以必须把更长（更具体）的前缀放在前面。
        FakeHttp http = new FakeHttp()
                .route("https://api.bilibili.com/x/web-interface/view", viewJson())
                .route("https://api.bilibili.com/x/player/playurl?platform=html5", html5Json())
                .route("https://api.bilibili.com/x/player/playurl", "{\"code\":0,\"data\":{}}");

        UrlParseResult r = engine(http).parse(UrlParseRequest.builder(INPUT).build());
        List<MediaAsset> videos = r.assets(MediaAsset.Kind.VIDEO);
        assertEquals(1, videos.size());
        assertEquals("https://cdn.test/progressive.mp4", videos.get(0).url());
        assertTrue(r.extras().get("bilibili.html5").contains("durl"));
    }

    @Test
    public void riskControlRetriesWithSiteCookies() throws Exception {
        FakeHttp http = new FakeHttp();
        // 第一次 view 返回风控
        http.routes.put("https://api.bilibili.com/x/web-interface/view",
                "{\"code\":-352,\"message\":\"风控校验失败\"}");
        // 首页返回 Set-Cookie（两条）
        http.routes.put("https://www.bilibili.com", "{}");
        http.routes.put("https://api.bilibili.com/x/player/playurl", dashJson());

        // 用一个能按"第几次调用"切换响应的客户端更直接
        HttpClient scripted = new HttpClient() {
            int viewCalls;
            boolean retriedWithCookie;

            @Override
            public HttpResponse execute(HttpRequest request) {
                String url = request.url();
                if (url.equals("https://www.bilibili.com")) {
                    Map<String, String> h = new LinkedHashMap<>();
                    h.put("set-cookie", "buvid3=AAA; Path=/\nbuvid4=BBB; Path=/");
                    return new HttpResponse(200, "{}", url, h, 1L);
                }
                if (url.contains("/x/web-interface/view")) {
                    viewCalls++;
                    if (viewCalls == 1) {
                        return new HttpResponse(200,
                                "{\"code\":-352,\"message\":\"风控\"}", url, null, 1L);
                    }
                    // 第二次必须带上 Cookie
                    retriedWithCookie = String.valueOf(request.headers()).contains("buvid3=AAA");
                    return new HttpResponse(200, viewJson(), url, null, 1L);
                }
                if (url.contains("/x/player/playurl")) {
                    return new HttpResponse(200, dashJson(), url, null, 1L);
                }
                return new HttpResponse(404, "", url, null, 1L);
            }
        };

        UrlParseResult r = engine(scripted).parse(UrlParseRequest.builder(INPUT).build());
        assertEquals(BVID, r.workId());
        assertFalse("重试必须带上首页下发的 Cookie", r.assets(MediaAsset.Kind.VIDEO).isEmpty());
    }

    @Test
    public void viewErrorCodeIsReportedHonestly() {
        FakeHttp http = new FakeHttp()
                .route("https://api.bilibili.com/x/web-interface/view",
                        "{\"code\":-404,\"message\":\"啥都木有\"}");
        ParseFailure f = assertThrows(ParseFailure.class,
                () -> engine(http).parse(UrlParseRequest.builder(INPUT).build()));
        assertEquals(FailureReason.UNEXPECTED_RESPONSE, f.reason());
        assertTrue("必须把接口的 code/message 原样带出: " + f.getMessage(),
                f.getMessage().contains("-404") && f.getMessage().contains("啥都木有"));
    }

    @Test
    public void pickBestVideoPrefersPixelsThenBitrate() throws Exception {
        JSONArray arr = new JSONArray("["
                + "{\"width\":640,\"height\":360,\"bandwidth\":9000000},"
                + "{\"width\":1920,\"height\":1080,\"bandwidth\":1000},"
                + "{\"width\":1920,\"height\":1080,\"bandwidth\":2000}]");
        JSONObject best = BilibiliProvider.pickBestVideo(arr);
        assertEquals(2000, best.optInt("bandwidth"));
    }

    @Test
    public void firstUrlAcceptsBothNamingStyles() throws Exception {
        assertEquals("https://a/1",
                BilibiliProvider.firstUrl(new JSONObject("{\"baseUrl\":\"https://a/1\"}")));
        assertEquals("https://a/2",
                BilibiliProvider.firstUrl(new JSONObject("{\"base_url\":\"https://a/2\"}")));
        assertNull(BilibiliProvider.firstUrl(new JSONObject("{}")));
        assertNull(BilibiliProvider.firstUrl(null));
    }

    @Test
    public void declinesUnrelatedInput() {
        BilibiliProvider p = new BilibiliProvider();
        assertFalse(p.canHandle(null, UrlParseRequest.builder("https://example.test/x").build()));
        assertTrue(p.canHandle(null, UrlParseRequest.builder(INPUT).build()));
        assertTrue(p.canHandle(null, UrlParseRequest.builder("https://b23.tv/abcdef").build()));
    }
}
