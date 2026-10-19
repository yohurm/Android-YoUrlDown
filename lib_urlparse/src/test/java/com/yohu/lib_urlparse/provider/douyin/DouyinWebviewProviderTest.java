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
import com.yohu.lib_urlparse.spi.JsEvaluator;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 抖音 webview 通道测试：**用假 JsEvaluator**验证编排逻辑，不需要真 WebView。
 *
 * <p>这组测试的价值是把"provider 与 JS 引擎解耦"这件事变成可执行断言：
 * provider 只认 {@link JsEvaluator} 接口，所以能在纯 JVM 上验证
 * 「注入脚本 → 取回 JSON → 映射媒体 → 释放求值器」的完整流程。
 */
public class DouyinWebviewProviderTest {

    private static final String AWEME_ID = "7014363562642623777";
    private static final String INPUT = "https://www.douyin.com/video/" + AWEME_ID;

    /** 记录调用参数的假求值器。 */
    static final class FakeEvaluator implements JsEvaluator {
        final String returnValue;
        final List<String> scripts = new ArrayList<>();
        final List<String> pageUrls = new ArrayList<>();
        int releaseCount;
        boolean throwOnEvaluate;

        FakeEvaluator(String returnValue) {
            this.returnValue = returnValue;
        }

        @Override
        public String loadAndEvaluate(String pageUrl, String script, long timeoutMs) {
            pageUrls.add(pageUrl);
            scripts.add(script);
            if (throwOnEvaluate) {
                throw new IllegalStateException("webview crashed");
            }
            return returnValue;
        }

        @Override
        public String evaluate(String script, long timeoutMs) {
            return returnValue;
        }

        @Override
        public void release() {
            releaseCount++;
        }
    }

    /** 不会真正发请求的 Http 客户端（短链展开时用）。 */
    static final class NoopHttp implements HttpClient {
        @Override
        public HttpResponse execute(HttpRequest request) {
            return new HttpResponse(200, "", request.url(), null, 1L);
        }
    }

    private static String itemJson() {
        return "{\"aweme_id\":\"" + AWEME_ID + "\",\"desc\":\"网页内注入的作品\","
                + "\"author\":{\"uid\":\"555\",\"nickname\":\"注入作者\"},"
                + "\"video\":{\"duration\":8000,"
                + "\"play_addr\":{\"url_list\":[\"https://aweme.test/playwm/?video_id=js\"]},"
                + "\"cover\":{\"url_list\":[\"https://p3.test/cover.jpeg\"]}}}";
    }

    private static UrlParseEngine engine(HttpClient http, JsEvaluator evaluator) {
        UrlParseEngine.Builder b = UrlParseEngine.builder()
                .httpClient(http)
                .provider(new DouyinWebviewProvider())
                .timeoutMs(5_000L);
        if (evaluator != null) {
            b.service(JsEvaluator.class, evaluator);
        }
        return b.build();
    }

    @Test
    public void usesEvaluatorAndParsesReturnedItem() throws Exception {
        // 回传结构：直接的作品对象
        FakeEvaluator ev = new FakeEvaluator(itemJson());
        UrlParseResult r = engine(new NoopHttp(), ev)
                .parse(UrlParseRequest.builder(INPUT).build());

        assertEquals(Platform.DOUYIN, r.platform());
        assertEquals("douyin.webview.js", r.channel());
        assertEquals(AWEME_ID, r.workId());
        assertEquals("注入作者", r.author().nickname());
        assertFalse(r.assets(MediaAsset.Kind.VIDEO).isEmpty());
        assertTrue("必须真的注入了脚本（含页面 URL 字面量转义）",
                ev.scripts.get(0).contains("\"https://www.douyin.com/video/" + AWEME_ID + "\""));
        assertEquals("求值器必须被释放，否则 WebView 泄漏", 1, ev.releaseCount);
    }

    @Test
    public void acceptsRouterDataShapedReturn() throws Exception {
        String router = "{\"loaderData\":{\"video_(" + AWEME_ID + ")/page\":{\"videoInfoRes\":"
                + "{\"item_list\":[" + itemJson() + "]}}}}";
        FakeEvaluator ev = new FakeEvaluator(router);
        UrlParseResult r = engine(new NoopHttp(), ev)
                .parse(UrlParseRequest.builder(INPUT).build());
        assertEquals(AWEME_ID, r.workId());
        assertFalse(r.assets(MediaAsset.Kind.VIDEO).isEmpty());
    }

    @Test
    public void toleratesJsonWrappedInText() throws Exception {
        FakeEvaluator ev = new FakeEvaluator("injected: " + itemJson() + " done");
        UrlParseResult r = engine(new NoopHttp(), ev)
                .parse(UrlParseRequest.builder(INPUT).build());
        assertEquals("注入作者", r.author().nickname());
    }

    @Test
    public void missingEvaluatorIsChannelDisabled() {
        ParseFailure f = assertThrows(ParseFailure.class,
                () -> engine(new NoopHttp(), null).parse(UrlParseRequest.builder(INPUT).build()));
        assertEquals("没有求值器必须是 CHANNEL_DISABLED（让链条继续）",
                FailureReason.CHANNEL_DISABLED, f.reason());
    }

    @Test
    public void emptyReturnIsNoMediaFound() {
        FakeEvaluator ev = new FakeEvaluator("");
        ParseFailure f = assertThrows(ParseFailure.class,
                () -> engine(new NoopHttp(), ev).parse(UrlParseRequest.builder(INPUT).build()));
        assertEquals(FailureReason.NO_MEDIA_FOUND, f.reason());
        assertEquals("异常路径也必须释放求值器", 1, ev.releaseCount);
    }

    @Test
    public void evaluatorCrashIsNetworkFailure() {
        FakeEvaluator ev = new FakeEvaluator("{}");
        ev.throwOnEvaluate = true;
        ParseFailure f = assertThrows(ParseFailure.class,
                () -> engine(new NoopHttp(), ev).parse(UrlParseRequest.builder(INPUT).build()));
        assertEquals(FailureReason.NETWORK, f.reason());
        assertEquals(1, ev.releaseCount);
    }

    @Test
    public void scriptBuilderQuotesInput() {
        String q = DouyinScriptBuilder.quote("a\"b\nc");
        assertEquals("\"a\\\"b\\nc\"", q);
        assertTrue(DouyinScriptBuilder.build(q).contains(q));
        assertTrue("就绪探针应检查页面解析器是否存在",
                DouyinScriptBuilder.buildReadyProbe().contains("DouyinParser"));
    }
}
