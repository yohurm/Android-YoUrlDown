package com.yohu.lib_urlparse.provider.weibo;

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

import org.json.JSONObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 微博通道测试：纯 JVM，用脚本化的假客户端回放真实响应形状。
 *
 * <p>重点覆盖三件真实踩过的事：
 * <ul>
 *   <li>风控响应是**合法 JSON**（{@code {"ok":-100}}），判据必须是业务码而不是"是不是 JSON"</li>
 *   <li>访客 Cookie 是两步流程（genvisitor → visitor），且之后重取要真的带上 Cookie</li>
 *   <li>一段微博可能同时有多媒体条目、单视频、图集三种形态</li>
 * </ul>
 */
public class WeiboProviderTest {

    private static final String STATUS_ID = "FBqgOmDxO";
    private static final String INPUT = "https://m.weibo.cn/status/" + STATUS_ID;

    /** 脚本化客户端：按 URL 片段路由，并记录调用顺序。 */
    static final class ScriptedHttp implements HttpClient {
        final Map<String, String> bodies = new LinkedHashMap<>();
        final Map<String, Map<String, String>> headers = new LinkedHashMap<>();
        final java.util.List<String> calls = new java.util.ArrayList<>();

        ScriptedHttp route(String fragment, String body) {
            bodies.put(fragment, body);
            return this;
        }

        ScriptedHttp routeWithSetCookie(String fragment, String body, String setCookie) {
            bodies.put(fragment, body);
            Map<String, String> h = new LinkedHashMap<>();
            h.put("set-cookie", setCookie);
            headers.put(fragment, h);
            return this;
        }

        @Override
        public HttpResponse execute(HttpRequest request) {
            String url = request.url();
            calls.add(url);
            for (Map.Entry<String, String> e : bodies.entrySet()) {
                if (url.contains(e.getKey())) {
                    return new HttpResponse(200, e.getValue(), url,
                            headers.get(e.getKey()), 1L);
                }
            }
            return new HttpResponse(404, "", url, null, 1L);
        }

        boolean sawRequestWithCookie(String cookieFragment) {
            return lastHeadersSeen != null && lastHeadersSeen.contains(cookieFragment);
        }

        String lastHeadersSeen;
    }

    private static String statusJson() {
        return "{\"ok\":1,\"id\":4912345678901234,\"id_str\":\"4912345678901234\","
                + "\"mblogid\":\"" + STATUS_ID + "\","
                + "\"text_raw\":\"看这个视频<a href=\\\"https://t.cn/abc\\\">很棒</a> 快转\","
                + "\"attitudes_count\":42,\"reposts_count\":7,"
                + "\"user\":{\"id\":123,\"idstr\":\"123\",\"screen_name\":\"某博主\","
                + "\"avatar_hd\":\"https://tvax.test/avatar.jpg\"},"
                + "\"page_info\":{\"page_pic\":\"https://wx.test/pic.jpg\","
                + "\"media_info\":{\"duration\":53,"
                + "\"playback_list\":[{\"play_info\":{\"url\":\"https://f.video.weibocdn.com/a.mp4\","
                + "\"mime\":\"video/mp4\",\"width\":1280,\"height\":720,\"size\":25000000}}]}}}";
    }

    private static UrlParseEngine engine(HttpClient http) {
        return UrlParseEngine.builder()
                .httpClient(http)
                .provider(new WeiboProvider())
                .timeoutMs(5_000L)
                .build();
    }

    @Test
    public void parsesVideoStatus() throws Exception {
        ScriptedHttp http = new ScriptedHttp().route("ajax/statuses/show", statusJson());
        UrlParseResult r = engine(http).parse(UrlParseRequest.builder(INPUT).build());

        assertEquals(Platform.WEIBO, r.platform());
        assertEquals("weibo.ajax", r.channel());
        assertEquals(STATUS_ID, r.workId());
        assertEquals("某博主", r.author().nickname());
        // 正文必须去掉 HTML 标签与实体
        assertTrue("正文应去掉真实 HTML 标签: " + r.title(), !r.title().contains("<a"));
        assertTrue("标签内文字应保留: " + r.title(), r.title().contains("很棒"));

        List<MediaAsset> videos = r.assets(MediaAsset.Kind.VIDEO);
        assertEquals(1, videos.size());
        assertEquals("https://f.video.weibocdn.com/a.mp4", videos.get(0).url());
        assertEquals(1280, videos.get(0).width());
        assertEquals(720, videos.get(0).height());
        assertEquals(53_000L, videos.get(0).durationMs());
        assertEquals(25_000_000L, videos.get(0).sizeBytes());
        assertFalse("封面不应缺失", r.assets(MediaAsset.Kind.COVER).isEmpty());
    }

    @Test
    public void parsesImageStatus() throws Exception {
        String json = "{\"ok\":1,\"id_str\":\"1\",\"mblogid\":\"abc\",\"text_raw\":\"图集\","
                + "\"pics\":[{\"large_url\":\"https://wx1.test/1.jpg\"},"
                + "{\"url\":\"https://wx2.test/2.jpg\"}]}";
        ScriptedHttp http = new ScriptedHttp().route("ajax/statuses/show", json);
        UrlParseResult r = engine(http).parse(UrlParseRequest.builder(INPUT).build());
        assertEquals(2, r.assets(MediaAsset.Kind.IMAGE).size());
        assertEquals("图集", r.title());
    }

    @Test
    public void riskControlJsonTriggersGuestCookieFlowThenSucceeds() throws Exception {
        // 第一次返回风控 JSON（合法 JSON！），拿到访客 Cookie 后第二次返回真数据
        final int[] statusCalls = new int[1];
        HttpClient http = new HttpClient() {
            @Override
            public HttpResponse execute(HttpRequest request) {
                String url = request.url();
                if (url.contains("/visitor/genvisitor")) {
                    return new HttpResponse(200,
                            "gen_callback({\"retcode\":20000000,\"data\":{\"tid\":\"TID123\","
                                    + "\"new_tid\":true,\"confidence\":98}})", url, null, 1L);
                }
                if (url.contains("/visitor/visitor")) {
                    Map<String, String> h = new LinkedHashMap<>();
                    h.put("set-cookie", "SUB=SUBVAL; Path=/\nSUBP=SUBPVAL; Path=/");
                    return new HttpResponse(200, "{\"retcode\":20000000}", url, h, 1L);
                }
                if (url.contains("ajax/statuses/show")) {
                    statusCalls[0]++;
                    boolean hasCookie = String.valueOf(request.headers()).contains("SUB=SUBVAL");
                    if (statusCalls[0] == 1 || !hasCookie) {
                        return new HttpResponse(200, "{\"ok\":-100,\"url\":\"https://passport\"}",
                                url, null, 1L);
                    }
                    return new HttpResponse(200, statusJson(), url, null, 1L);
                }
                return new HttpResponse(404, "", url, null, 1L);
            }
        };

        UrlParseResult r = engine(http).parse(UrlParseRequest.builder(INPUT).build());
        assertEquals("必须最终拿到真实状态", STATUS_ID, r.workId());
        assertFalse(r.assets(MediaAsset.Kind.VIDEO).isEmpty());
    }

    @Test
    public void textOnlyStatusIsNoMediaFound() {
        String json = "{\"ok\":1,\"id_str\":\"9\",\"mblogid\":\"zzz\",\"text_raw\":\"纯文字微博\"}";
        ScriptedHttp http = new ScriptedHttp().route("ajax/statuses/show", json);
        ParseFailure f = assertThrows(ParseFailure.class,
                () -> engine(http).parse(UrlParseRequest.builder(INPUT).build()));
        assertEquals(FailureReason.NO_MEDIA_FOUND, f.reason());
        assertTrue(f.getMessage().contains("纯文字"));
    }

    @Test
    public void needsVisitorCookiesUsesBusinessCodeNotJsonShape() throws Exception {
        assertTrue("ok<0 是风控", WeiboProvider.needsVisitorCookies(
                new JSONObject("{\"ok\":-100,\"url\":\"x\"}")));
        assertTrue("没有状态标识也算需要访客身份", WeiboProvider.needsVisitorCookies(
                new JSONObject("{\"ok\":1}")));
        assertFalse("有 id_str 且 ok=1 是真数据", WeiboProvider.needsVisitorCookies(
                new JSONObject("{\"ok\":1,\"id_str\":\"123\"}")));
        assertTrue(WeiboProvider.needsVisitorCookies(null));
    }

    @Test
    public void cookieHeaderJoinsMultipleSetCookies() {
        assertEquals("A=1; B=2", WeiboProvider.toCookieHeader("A=1; Path=/\nB=2; Path=/"));
        assertEquals("", WeiboProvider.toCookieHeader(null));
        assertEquals("", WeiboProvider.toCookieHeader(""));
    }

    @Test
    public void declinesUnrelatedInput() {
        WeiboProvider p = new WeiboProvider();
        assertFalse(p.canHandle(null, UrlParseRequest.builder("https://example.test/x").build()));
        assertTrue(p.canHandle(null, UrlParseRequest.builder(INPUT).build()));
        assertTrue(p.canHandle(null,
                UrlParseRequest.builder("https://weibo.com/1234567890/FBqgOmDxO").build()));
    }
}
