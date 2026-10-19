package com.yohu.lib_urlparse.provider.xiaohongshu;

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
 * 小红书通道测试：纯 JVM（不联网、不启 Android）。
 *
 * <p>重点验证两个真实踩过的坑：
 * <ul>
 *   <li>短链（xhslink.com）必须靠**重定向后的最终 URL** 才能拿到 noteId</li>
 *   <li>安全页/404 页与"页面结构变化"必须给出不同原因，否则用户无法判断该换链接还是该报 bug</li>
 * </ul>
 */
public class XiaohongshuProviderTest {

    private static final String NOTE_ID = "6a8d6df50000000021026ecc";
    private static final String NOTE_URL =
            "https://www.xiaohongshu.com/explore/" + NOTE_ID + "?xsec_token=ABCDEFGH";
    private static final String SHORT_URL = "http://xhslink.com/a/AbCdEf";

    /** 返回固定 HTML 的假客户端；可指定 note 请求的最终 URL（模拟短链展开）。 */
    static final class FakeHttp implements HttpClient {
        private final String noteHtml;
        private final String finalUrl;
        private final String refererBody;
        int refererCalls;
        int noteCalls;

        FakeHttp(String noteHtml, String finalUrl) {
            this(noteHtml, finalUrl, "<html>home</html>");
        }

        FakeHttp(String noteHtml, String finalUrl, String refererBody) {
            this.noteHtml = noteHtml;
            this.finalUrl = finalUrl;
            this.refererBody = refererBody;
        }

        @Override
        public HttpResponse execute(HttpRequest request) {
            String url = request.url();
            if (url.contains("xiaohongshu.com/") && (url.endsWith("/") || url.equals(
                    "https://www.xiaohongshu.com/"))) {
                refererCalls++;
                return new HttpResponse(200, refererBody, url, null, 1L);
            }
            noteCalls++;
            String effective = finalUrl != null ? finalUrl : url;
            return new HttpResponse(200, noteHtml, effective, null, 1L);
        }
    }

    private static String stateHtml(String noteId, String title) {
        String json = "{\"note\":{\"noteDetailMap\":{\"" + noteId + "\":{\"note\":{"
                + "\"noteId\":\"" + noteId + "\","
                + "\"title\":\"" + title + "\","
                + "\"desc\":\"正文\","
                + "\"type\":\"normal\","
                + "\"user\":{\"nickname\":\"某作者\",\"userId\":\"u123\"},"
                + "\"imageList\":[{\"urlDefault\":\"http://sns-webpic-qc.xhscdn.com/20240101/abc123/1040g2t.jpg\"}],"
                + "\"video\":{\"consumer\":{\"originVideoKey\":\"pre/abc123\"}}"
                + "}}}}}}";
        return "<html><head></head><body><script>window.__INITIAL_STATE__="
                + json + ";</script></body></html>";
    }

    private static UrlParseEngine engine(HttpClient http) {
        return UrlParseEngine.builder()
                .httpClient(http)
                .provider(new XiaohongshuProvider())
                .timeoutMs(5_000L)
                .build();
    }

    @Test
    public void parsesNotePageIntoVideoAndImages() throws Exception {
        FakeHttp http = new FakeHttp(stateHtml(NOTE_ID, "一篇测试笔记"), NOTE_URL);
        UrlParseResult r = engine(http).parse(UrlParseRequest.builder(NOTE_URL).build());

        assertEquals(Platform.XIAOHONGSHU, r.platform());
        assertEquals("xiaohongshu.webpage", r.channel());
        assertEquals(NOTE_ID, r.workId());
        assertEquals("一篇测试笔记", r.title());
        assertNotNull("作者昵称应被抽出", r.author().nickname());
        assertEquals("某作者", r.author().nickname());
        assertEquals("u123", r.author().uid());

        assertEquals("应有一个视频", 1, r.assets(MediaAsset.Kind.VIDEO).size());
        List<MediaAsset> images = r.assets(MediaAsset.Kind.IMAGE);
        assertFalse("应有图片", images.isEmpty());
        assertTrue("图片地址应为 http(s)", images.get(0).url().startsWith("http"));
        assertEquals("normal", r.extras().get("xhs.type"));
        // 至少要有一次预热 + 一次笔记请求
        assertEquals(1, http.refererCalls);
        assertEquals(1, http.noteCalls);
    }

    @Test
    public void expandsShortLinkViaFinalUrl() throws Exception {
        // 短链本身没有 noteId；只有重定向后的 finalUrl 才有
        FakeHttp http = new FakeHttp(stateHtml(NOTE_ID, "短链笔记"), NOTE_URL);
        UrlParseResult r = engine(http).parse(UrlParseRequest.builder(SHORT_URL).build());
        assertEquals("必须从最终 URL 取出 noteId", NOTE_ID, r.workId());
        assertEquals(NOTE_URL, r.sourceUrl());
    }

    @Test
    public void securityPageGetsDistinctReason() {
        // 安全页：HTTP 200，__INITIAL_STATE__ 存在但没有笔记对象
        String secHtml = "<html><script>window.__INITIAL_STATE__={\"note\":{}};</script></html>";
        FakeHttp http = new FakeHttp(secHtml,
                "https://www.xiaohongshu.com/404/sec_abc123?source=xhs_sec_server");
        ParseFailure f = assertThrows(ParseFailure.class,
                () -> engine(http).parse(UrlParseRequest.builder(SHORT_URL).build()));
        assertEquals(FailureReason.NO_MEDIA_FOUND, f.reason());
        assertTrue("必须点明是安全页/不存在，而不是笼统的没媒体: " + f.getMessage(),
                f.getMessage().contains("安全"));
    }

    @Test
    public void structureChangeGetsDiagnosticReason() {
        String noState = "<html><body>nothing here</body></html>";
        FakeHttp http = new FakeHttp(noState, NOTE_URL);
        ParseFailure f = assertThrows(ParseFailure.class,
                () -> engine(http).parse(UrlParseRequest.builder(NOTE_URL).build()));
        assertEquals(FailureReason.NO_MEDIA_FOUND, f.reason());
        assertTrue("应带层次诊断信息: " + f.getMessage(), f.getMessage().contains("诊断"));
    }

    @Test
    public void declinesUnrelatedInput() {
        XiaohongshuProvider p = new XiaohongshuProvider();
        assertFalse(p.canHandle(null, UrlParseRequest.builder("https://example.test/x").build()));
        assertTrue(p.canHandle(null, UrlParseRequest.builder(SHORT_URL).build()));
        assertTrue(p.canHandle(null, UrlParseRequest.builder(NOTE_URL).build()));
        assertTrue(p.canHandle(null, UrlParseRequest.builder("https://example.test/x")
                .platformHint(Platform.XIAOHONGSHU).build()));
    }
}
