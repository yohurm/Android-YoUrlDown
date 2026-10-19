package com.yohu.lib_urlparse.provider.douyin;

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

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 抖音 webpage 通道测试：纯 JVM（假 HTTP + 合成分享页）。
 *
 * <p>分享页是**服务端渲染**的，所以这条通道完全不需要 WebView——这是它排在
 * {@code douyin.webview} 之前的原因。
 */
public class DouyinWebProviderTest {

    private static final String AWEME_ID = "7014363562642623777";
    private static final String INPUT = "https://www.douyin.com/video/" + AWEME_ID;

    /** 返回固定分享页的假客户端。 */
    static final class FakeHttp implements HttpClient {
        private final String html;
        private final String finalUrl;
        int calls;

        FakeHttp(String html, String finalUrl) {
            this.html = html;
            this.finalUrl = finalUrl;
        }

        @Override
        public HttpResponse execute(HttpRequest request) {
            calls++;
            String url = finalUrl != null ? finalUrl : request.url();
            return new HttpResponse(200, html, url, null, 1L);
        }
    }

    /** 合成分享页：结构对齐真实页面（loaderData.video_(id)/page.videoInfoRes.item_list[0]）。 */
    private static String shareHtml() {
        String item = "{\"aweme_id\":\"" + AWEME_ID + "\","
                + "\"desc\":\"测试作品标题 #话题\","
                + "\"author\":{\"uid\":\"987\",\"nickname\":\"某作者\","
                + "\"avatar_thumb\":{\"url_list\":[\"https://p3.test/avatar.jpeg\"]}},"
                + "\"video\":{\"duration\":14500,"
                + "\"play_addr\":{\"url_list\":[\"https://aweme.test/playwm/?video_id=v0300\"]},"
                + "\"bit_rate\":[{\"gear_name\":\"1080p\",\"bit_rate\":2000000,"
                + "\"play_addr\":{\"url_list\":[\"https://aweme.test/playwm/?video_id=hi\"],"
                + "\"width\":1080,\"height\":1920}}],"
                + "\"cover\":{\"url_list\":[\"https://p3.test/cover.jpeg\"]}},"
                + "\"music\":{\"play_url\":{\"url_list\":[\"https://music.test/m.mp3\"]}}}";
        String router = "{\"loaderData\":{\"video_(" + AWEME_ID + ")/page\":{\"videoInfoRes\":"
                + "{\"item_list\":[" + item + "]}}}}";
        return "<html><head></head><body><script>window._ROUTER_DATA = "
                + router + ";</script></body></html>";
    }

    private static UrlParseEngine engine(HttpClient http) {
        return UrlParseEngine.builder()
                .httpClient(http)
                .provider(new DouyinWebProvider())
                .timeoutMs(5_000L)
                .build();
    }

    @Test
    public void parsesSharePageIntoNoWatermarkVideo() throws Exception {
        FakeHttp http = new FakeHttp(shareHtml(), null);
        UrlParseResult r = engine(http).parse(UrlParseRequest.builder(INPUT).build());

        assertEquals(Platform.DOUYIN, r.platform());
        assertEquals("douyin.webpage", r.channel());
        assertEquals(AWEME_ID, r.workId());
        assertEquals("测试作品标题 #话题", r.title());
        assertEquals("某作者", r.author().nickname());
        assertEquals("987", r.author().uid());

        List<MediaAsset> videos = r.assets(MediaAsset.Kind.VIDEO);
        assertFalse("必须解析出视频", videos.isEmpty());
        // 去水印：playwm -> play
        assertTrue("地址应去掉 playwm: " + videos.get(0).url(),
                !videos.get(0).url().contains("playwm"));
        assertTrue("应有封面", !r.assets(MediaAsset.Kind.COVER).isEmpty());
        assertTrue("应有音频（背景音乐）", !r.assets(MediaAsset.Kind.AUDIO).isEmpty());
        assertEquals("14500", r.extras().get("douyin.durationMs"));
    }

    @Test
    public void expandsShortLinkBeforeExtractingId() throws Exception {
        String expanded = "https://www.iesdouyin.com/share/video/" + AWEME_ID + "/?region=CN";
        FakeHttp http = new FakeHttp(shareHtml(), expanded);
        UrlParseResult r = engine(http).parse(
                UrlParseRequest.builder("https://v.douyin.com/8hedewA/").build());
        assertEquals("必须从展开后的 URL 取到 awemeId", AWEME_ID, r.workId());
    }

    @Test
    public void regexFallbackWhenRouterDataMissing() throws Exception {
        String html = "<html><script>var x=\"https://aweme.test/playwm/?video_id=fallback.mp4\";"
                + "</script></html>";
        FakeHttp http = new FakeHttp(html, null);
        UrlParseResult r = engine(http).parse(UrlParseRequest.builder(INPUT).build());
        assertFalse("正则兜底应至少拿到一条地址",
                r.assets(MediaAsset.Kind.VIDEO).isEmpty());
        assertTrue(r.extras().get("douyin.layers").length() > 0);
    }

    @Test
    public void emptyPageIsUnexpectedResponse() {
        FakeHttp http = new FakeHttp("", null);
        ParseFailure f = assertThrows(ParseFailure.class,
                () -> engine(http).parse(UrlParseRequest.builder(INPUT).build()));
        assertEquals(FailureReason.UNEXPECTED_RESPONSE, f.reason());
    }

    @Test
    public void noMediaGivesDiagnostics() {
        FakeHttp http = new FakeHttp("<html>nothing</html>", null);
        ParseFailure f = assertThrows(ParseFailure.class,
                () -> engine(http).parse(UrlParseRequest.builder(INPUT).build()));
        assertEquals(FailureReason.NO_MEDIA_FOUND, f.reason());
        assertTrue("应带层次诊断: " + f.getMessage(), f.getMessage().contains("诊断"));
    }

    @Test
    public void extractsJsonObjectByBraceMatching() {
        String html = "<script>window._ROUTER_DATA = {\"a\":{\"b\":\"}\"},\"c\":[1,2]};</script>";
        String json = DouyinWebProvider.extractJsonObject(html, "_ROUTER_DATA");
        assertNotNull(json);
        assertTrue("字符串里的 } 不应提前结束: " + json, json.contains("\"}\""));
        assertTrue(json.endsWith("}"));
    }

    @Test
    public void declinesUnrelatedInput() {
        DouyinWebProvider p = new DouyinWebProvider();
        assertFalse(p.canHandle(null, UrlParseRequest.builder("https://example.test/x").build()));
        assertTrue(p.canHandle(null, UrlParseRequest.builder(INPUT).build()));
        assertTrue(p.canHandle(null, UrlParseRequest.builder("https://v.douyin.com/abc/").build()));
    }
}
