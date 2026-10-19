package com.yohu.lib_urlparse.provider.sniffer;

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
import com.yohu.lib_urlparse.spi.PageSniffer;
import com.yohu.lib_urlparse.spi.SniffOptions;
import com.yohu.lib_urlparse.spi.SniffResult;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 嗅探通道测试：用假 {@link PageSniffer} 验证编排，不需要真 WebView。
 *
 * <p>最关键的用例是"拦截页不能当作品"——那是旧工程的一次**假通过**：
 * 抖音拿到拦截页标题「網頁無法使用」，嗅探仍收了 7 条图片，矩阵被记成"解析通过"。
 */
public class SnifferProviderTest {

    /** 不做网络请求的客户端（本通道不需要它）。 */
    static final class NoopHttp implements HttpClient {
        @Override
        public HttpResponse execute(HttpRequest request) {
            return new HttpResponse(200, "", request.url(), null, 1L);
        }
    }

    /** 返回预置结果的假嗅探器。 */
    static final class FakeSniffer implements PageSniffer {
        private final SniffResult result;
        SniffOptions lastOptions;
        String lastUrl;
        int releaseCount;

        FakeSniffer(SniffResult result) {
            this.result = result;
        }

        @Override
        public SniffResult sniff(String pageUrl, long timeoutMs, SniffOptions options) {
            this.lastUrl = pageUrl;
            this.lastOptions = options;
            return result;
        }

        @Override
        public void release() {
            releaseCount++;
        }
    }

    private static SniffResult result(String title, String finalUrl,
                                      SniffResult.Candidate... candidates) {
        SniffResult.Builder b = SniffResult.builder().title(title).finalUrl(finalUrl)
                .requestCount(candidates.length * 3).timedOut(false);
        for (SniffResult.Candidate c : candidates) {
            b.candidate(c);
        }
        return b.build();
    }

    private static UrlParseEngine engine(PageSniffer sniffer) {
        UrlParseEngine.Builder b = UrlParseEngine.builder()
                .httpClient(new NoopHttp())
                .provider(new SnifferProvider())
                .timeoutMs(5_000L);
        if (sniffer != null) {
            b.service(PageSniffer.class, sniffer);
        }
        return b.build();
    }

    @Test
    public void mapsCandidatesToAssetsAndSortsByUsability() throws Exception {
        SniffResult r = result("作品标题", "https://www.douyin.com/video/7014363562642623777",
                new SniffResult.Candidate("https://cdn.test/low.mp4", "video/mp4", "video/mp4", 1024L),
                new SniffResult.Candidate("https://cdn.test/master.m3u8",
                        "application/vnd.apple.mpegurl", null, -1L),
                new SniffResult.Candidate("https://cdn.test/cover.jpeg", "image/jpeg", null, 4096L));
        FakeSniffer sniffer = new FakeSniffer(r);

        UrlParseResult out = engine(sniffer)
                .parse(UrlParseRequest.builder("https://www.douyin.com/video/7014363562642623777").build());

        assertEquals(Platform.DOUYIN, out.platform());
        assertEquals("sniffer.webview", out.channel());
        assertEquals("作品标题", out.title());
        List<MediaAsset> videos = out.assets(MediaAsset.Kind.VIDEO);
        assertEquals(2, videos.size());
        assertTrue("m3u8 应排最前（通常含完整视频）: " + videos.get(0).url(),
                videos.get(0).url().endsWith(".m3u8"));
        assertEquals(1, out.assets(MediaAsset.Kind.IMAGE).size());
        assertEquals("嗅探器必须被释放", 1, sniffer.releaseCount);
    }

    @Test
    public void galleryModeOpenedForGalleryPronePlatforms() throws Exception {
        FakeSniffer sniffer = new FakeSniffer(result("x", "https://www.xiaohongshu.com/explore/abc",
                new SniffResult.Candidate("https://cdn.test/1.jpg", "image/jpeg", null, 1024L)));
        engine(sniffer).parse(UrlParseRequest.builder("https://www.xiaohongshu.com/explore/abc").build());
        assertTrue("图集类平台必须打开 gallery 模式", sniffer.lastOptions.galleryMode());

        FakeSniffer bili = new FakeSniffer(result("y", "https://www.bilibili.com/video/BV1HU4y1K7TT",
                new SniffResult.Candidate("https://cdn.test/1.mp4", "video/mp4", null, 1024L)));
        engine(bili).parse(UrlParseRequest.builder("https://www.bilibili.com/video/BV1HU4y1K7TT").build());
        assertFalse("视频平台不应打开 gallery 模式", bili.lastOptions.galleryMode());
    }

    @Test
    public void blockPageIsRejectedNotReportedAsSuccess() {
        // 这就是旧工程那次假通过：拦截页上的 7 张静态图片
        SniffResult r = result("網頁無法使用", "https://www.douyin.com/video/1",
                new SniffResult.Candidate("https://cdn.test/a.jpg", "image/jpeg", null, 1024L),
                new SniffResult.Candidate("https://cdn.test/b.jpg", "image/jpeg", null, 1024L));
        ParseFailure f = assertThrows(ParseFailure.class,
                () -> engine(new FakeSniffer(r))
                        .parse(UrlParseRequest.builder("https://www.douyin.com/video/1").build()));
        assertEquals(FailureReason.UNEXPECTED_RESPONSE, f.reason());
        assertTrue("必须说明这是拦截页而不是作品: " + f.getMessage(),
                f.getMessage().contains("拦截"));
    }

    @Test
    public void blockTitleButWithRealVideoIsAccepted() throws Exception {
        // 宁可漏判不可误杀：有视频信号时即使标题可疑也照收
        SniffResult r = result("網頁無法使用", "https://www.douyin.com/video/1",
                new SniffResult.Candidate("https://cdn.test/real.mp4", "video/mp4", null, 9_000_000L));
        UrlParseResult out = engine(new FakeSniffer(r))
                .parse(UrlParseRequest.builder("https://www.douyin.com/video/1").build());
        assertFalse(out.assets(MediaAsset.Kind.VIDEO).isEmpty());
    }

    @Test
    public void missingSnifferIsChannelDisabled() {
        ParseFailure f = assertThrows(ParseFailure.class,
                () -> engine(null).parse(
                        UrlParseRequest.builder("https://example.test/page").build()));
        assertEquals(FailureReason.CHANNEL_DISABLED, f.reason());
    }

    @Test
    public void noMediaGivesCountsForDiagnosis() {
        SniffResult r = result("空页面", "https://example.test/p");
        ParseFailure f = assertThrows(ParseFailure.class,
                () -> engine(new FakeSniffer(r))
                        .parse(UrlParseRequest.builder("https://example.test/p").build()));
        assertEquals(FailureReason.NO_MEDIA_FOUND, f.reason());
        assertTrue("应给出请求数/候选数便于诊断: " + f.getMessage(),
                f.getMessage().contains("请求数") && f.getMessage().contains("候选"));
    }

    @Test
    public void deduplicatesSameUrlKeepingBestEvidence() {
        SniffResult r = result("t", "https://example.test/p",
                new SniffResult.Candidate("https://cdn.test/x.mp4", null, null, -1L),
                new SniffResult.Candidate("https://cdn.test/x.mp4", "video/mp4", "video/mp4", 2048L));
        List<MediaAsset> items = SnifferProvider.toMediaItems(r);
        assertEquals("同 URL 只保留一条", 1, items.size());
        assertEquals("video/mp4", items.get(0).mimeType());
        assertEquals(2048L, items.get(0).sizeBytes());
    }

    @Test
    public void infersPlatformFromHost() {
        assertEquals(Platform.WECHAT_MP,
                SnifferProvider.inferPlatform("https://mp.weixin.qq.com/s/abc"));
        assertEquals(Platform.PIPIXIA, SnifferProvider.inferPlatform("https://h5.pipix.com/item/1"));
        assertEquals(Platform.UNKNOWN, SnifferProvider.inferPlatform("https://example.test/x"));
    }
    @Test
    public void imagesOnlyOnVideoPlatformIsNoMediaFound() {
        // 回归：快手验证页曾被嗅探成"12 张图片"并报成功（那些是头像/图标）。
        // 快手不是图集类平台，因此"只有图片"必须判为可疑页而不是成功。
        SniffResult r = result("快手 - 记录美好生活", "https://v.kuaishou.com/abcdef",
                new SniffResult.Candidate("https://p66.a.kwimgs.com/uhead/AB/xxx.jpg",
                        "image/jpeg", null, 2048L),
                new SniffResult.Candidate("https://p66.a.kwimgs.com/uhead/AB/yyy.jpg",
                        "image/jpeg", null, 2048L));
        ParseFailure f = assertThrows(ParseFailure.class,
                () -> engine(new FakeSniffer(r))
                        .parse(UrlParseRequest.builder("https://v.kuaishou.com/abcdef").build()));
        assertEquals(FailureReason.NO_MEDIA_FOUND, f.reason());
        assertTrue("必须说明是可疑页而不是作品: " + f.getMessage(),
                f.getMessage().contains("头像") || f.getMessage().contains("疑似"));
    }

    @Test
    public void imagesOnlyOnGalleryPlatformIsAccepted() throws Exception {
        // 对照组：小红书作品主体就是图片，不能误杀
        SniffResult r = result("笔记", "https://www.xiaohongshu.com/explore/abc",
                new SniffResult.Candidate("https://sns-img.test/a.jpg", "image/jpeg", null, 2048L));
        UrlParseResult out = engine(new FakeSniffer(r))
                .parse(UrlParseRequest.builder("https://www.xiaohongshu.com/explore/abc").build());
        assertEquals(1, out.assets(MediaAsset.Kind.IMAGE).size());
    }
}