package com.yohu.lib_urlparse.core;

import com.yohu.lib_urlparse.api.FailureReason;
import com.yohu.lib_urlparse.api.MediaAsset;
import com.yohu.lib_urlparse.api.ParseFailure;
import com.yohu.lib_urlparse.api.ParseTrace;
import com.yohu.lib_urlparse.api.Platform;
import com.yohu.lib_urlparse.api.UrlParseEngine;
import com.yohu.lib_urlparse.api.UrlParseRequest;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.lib_urlparse.spi.CancelToken;
import com.yohu.lib_urlparse.spi.EngineLogger;
import com.yohu.lib_urlparse.spi.HttpClient;
import com.yohu.lib_urlparse.spi.HttpRequest;
import com.yohu.lib_urlparse.spi.HttpResponse;
import com.yohu.lib_urlparse.spi.ParseContext;
import com.yohu.lib_urlparse.spi.ParseProvider;
import com.yohu.lib_urlparse.spi.ProviderPriority;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 调度语义测试。
 *
 * <p>这些用例保护的是"为什么这么设计"——每一条都对应一类真实缺陷：
 * <ul>
 *   <li>优先级：兜底通道抢在专用通道前面，把快路径拖成慢路径</li>
 *   <li>继续尝试：一个通道失败（缺凭据/要验证码）并不代表别的通道也不行</li>
 *   <li>抛第一个失败：否则真实原因会被最后的兜底通道掩盖</li>
 *   <li>provider 返回 null：属于实现缺陷，必须当成失败而不是让调用方拿到 null</li>
 * </ul>
 */
public class ParseDispatchTest {

    private static final String INPUT = "https://a.test/work/1";

    /** 不做任何 IO 的 HTTP 客户端。 */
    static final class NoopHttp implements HttpClient {
        @Override
        public HttpResponse execute(HttpRequest request) {
            return new HttpResponse(200, "{}", request.url(), null, 0L);
        }
    }

    /** 可编排行为的假通道。 */
    static final class FakeProvider implements ParseProvider {
        private final String channel;
        private final ProviderPriority priority;
        private final Behavior behavior;

        enum Behavior {
            SUCCEED, FAIL_RETRYABLE, FAIL_FATAL, RETURN_NULL, THROW_RUNTIME
        }

        FakeProvider(String channel, ProviderPriority priority, Behavior behavior) {
            this.channel = channel;
            this.priority = priority;
            this.behavior = behavior;
        }

        @Override
        public String channel() {
            return channel;
        }

        @Override
        public ProviderPriority priority() {
            return priority;
        }

        @Override
        public boolean canHandle(ParseContext context, UrlParseRequest request) {
            return true;
        }

        @Override
        public UrlParseResult parse(ParseContext context, UrlParseRequest request)
                throws ParseFailure {
            switch (behavior) {
                case SUCCEED:
                    return UrlParseResult.builder(Platform.BILIBILI)
                            .channel(channel)
                            .workId("1")
                            .asset(MediaAsset.video("https://cdn.test/v.mp4").build())
                            .build();
                case FAIL_RETRYABLE:
                    throw new ParseFailure(FailureReason.NETWORK, channel, "timeout");
                case FAIL_FATAL:
                    throw new ParseFailure(FailureReason.CREDENTIALS_MISSING, channel, "no endpoint");
                case RETURN_NULL:
                    return null;
                default:
                    throw new IllegalStateException("boom");
            }
        }
    }

    private static UrlParseEngine engineWith(EngineLogger logger, CancelToken token,
                                             ParseProvider... providers) {
        UrlParseEngine.Builder b = UrlParseEngine.builder()
                .httpClient(new NoopHttp())
                .logger(logger)
                .timeoutMs(1_000L);
        if (token != null) {
            b.cancelToken(token);
        }
        for (ParseProvider p : providers) {
            b.provider(p);
        }
        return b.build();
    }

    @Test
    public void noProviderMeansUnrecognisedInput() {
        UrlParseEngine engine = engineWith(null, null);
        try {
            engine.parse(UrlParseRequest.builder(INPUT).build());
            fail("expected ParseFailure");
        } catch (ParseFailure f) {
            assertEquals(FailureReason.UNRECOGNISED_INPUT, f.reason());
            assertTrue("没有通道时 channel 应为空", f.channel().isEmpty());
            assertTrue("必须给出人话建议", f.userHint().length() > 0);
        }
    }

    @Test
    public void triesChannelsByPriorityNotRegistrationOrder() throws Exception {
        // 故意先注册兜底、后注册专用；结果必须仍是专用通道先被尝试（并且成功）
        UrlParseEngine engine = engineWith(null, null,
                new FakeProvider("sniffer.webview", ProviderPriority.SNIFFER,
                        FakeProvider.Behavior.FAIL_RETRYABLE),
                new FakeProvider("bilibili.api", ProviderPriority.DEDICATED_API,
                        FakeProvider.Behavior.SUCCEED));

        UrlParseResult r = engine.parse(UrlParseRequest.builder(INPUT).build());
        assertEquals("bilibili.api", r.channel());
        // trace 的第一条必须是专用通道
        assertEquals("bilibili.api", r.trace().attempts().get(0).channel());
        assertEquals(ParseTrace.Verdict.OK, r.trace().attempts().get(0).verdict());
        assertEquals("应该只尝试了一个通道", 1, r.trace().attempts().size());
    }

    @Test
    public void continuesToNextChannelAfterRetryableFailure() throws Exception {
        UrlParseEngine engine = engineWith(null, null,
                new FakeProvider("a.one", ProviderPriority.DEDICATED_WEB,
                        FakeProvider.Behavior.FAIL_RETRYABLE),
                new FakeProvider("b.two", ProviderPriority.DEDICATED_WEB,
                        FakeProvider.Behavior.SUCCEED));

        UrlParseResult r = engine.parse(UrlParseRequest.builder(INPUT).build());
        assertEquals("b.two", r.channel());
        assertEquals(2, r.trace().attempts().size());
        assertEquals(ParseTrace.Verdict.FAILED, r.trace().attempts().get(0).verdict());
        assertEquals(FailureReason.NETWORK, r.trace().attempts().get(0).reason());
        assertEquals(ParseTrace.Verdict.OK, r.trace().attempts().get(1).verdict());
    }

    @Test
    public void continuesAfterCredentialsMissingToo() throws Exception {
        // 缺凭据是"这条路不可用"，不代表别的通道不可用。
        // 注意：失败的那个必须排在前面（优先级更高），否则它根本不会被先尝试。
        UrlParseEngine engine = engineWith(null, null,
                new FakeProvider("cloud.api", ProviderPriority.DEDICATED_WEB,
                        FakeProvider.Behavior.FAIL_FATAL),
                new FakeProvider("bilibili.api", ProviderPriority.DEDICATED_API,
                        FakeProvider.Behavior.SUCCEED));

        UrlParseResult r = engine.parse(UrlParseRequest.builder(INPUT).build());
        assertEquals("bilibili.api", r.channel());
        assertEquals(2, r.trace().attempts().size());
    }

    @Test
    public void reportsFirstFailureNotLastOne() {
        // 专用通道失败（网络），兜底通道也失败（结构不符）→ 必须抛专用通道那个原因
        UrlParseEngine engine = engineWith(null, null,
                new FakeProvider("dedicated", ProviderPriority.DEDICATED_WEB,
                        FakeProvider.Behavior.FAIL_RETRYABLE),
                new FakeProvider("sniffer", ProviderPriority.SNIFFER,
                        FakeProvider.Behavior.FAIL_FATAL));
        try {
            engine.parse(UrlParseRequest.builder(INPUT).build());
            fail("expected ParseFailure");
        } catch (ParseFailure f) {
            assertEquals("dedicated", f.channel());
            assertEquals(FailureReason.NETWORK, f.reason());
        }
    }

    @Test
    public void nullResultFromProviderIsInternalFailure() {
        UrlParseEngine engine = engineWith(null, null,
                new FakeProvider("broken", ProviderPriority.DEDICATED_WEB,
                        FakeProvider.Behavior.RETURN_NULL));
        try {
            engine.parse(UrlParseRequest.builder(INPUT).build());
            fail("expected ParseFailure");
        } catch (ParseFailure f) {
            assertEquals(FailureReason.INTERNAL, f.reason());
            assertEquals("broken", f.channel());
        }
    }

    @Test
    public void runtimeExceptionFromProviderBecomesInternalFailure() {
        UrlParseEngine engine = engineWith(null, null,
                new FakeProvider("crashy", ProviderPriority.DEDICATED_WEB,
                        FakeProvider.Behavior.THROW_RUNTIME));
        try {
            engine.parse(UrlParseRequest.builder(INPUT).build());
            fail("expected ParseFailure");
        } catch (ParseFailure f) {
            assertEquals(FailureReason.INTERNAL, f.reason());
            assertTrue(f.getMessage().contains("provider crashed"));
        }
    }

    @Test
    public void cancellationStopsDispatchImmediately() {
        CancelToken token = CancelToken.fresh();
        token.cancel();
        UrlParseEngine engine = engineWith(null, token,
                new FakeProvider("any", ProviderPriority.DEDICATED_WEB,
                        FakeProvider.Behavior.SUCCEED));
        try {
            engine.parse(UrlParseRequest.builder(INPUT).build());
            fail("expected ParseFailure");
        } catch (ParseFailure f) {
            assertEquals(FailureReason.CANCELLED, f.reason());
        }
    }

    @Test
    public void engineKeepsLastTraceForInspection() {
        UrlParseEngine engine = engineWith(null, null,
                new FakeProvider("only", ProviderPriority.DEDICATED_WEB,
                        FakeProvider.Behavior.FAIL_RETRYABLE));
        try {
            engine.parse(UrlParseRequest.builder(INPUT).build());
        } catch (ParseFailure expected) {
            // 忽略：这里只验证 trace 被保留
        }
        ParseTrace trace = engine.lastTrace();
        assertTrue("失败后也必须留下 trace", trace != null);
        assertEquals(1, trace.attempts().size());
        assertEquals("https://a.test/work/1", trace.resolvedUrl());
    }

    @Test
    public void blankInputRejectedAtBuildTime() {
        try {
            UrlParseRequest.builder("   ").build();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // 空输入是调用方错误，尽早失败
        }
    }

    @Test
    public void engineRequiresHttpClient() {
        try {
            UrlParseEngine.builder().build();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("HttpClient"));
        }
        assertNull(null);
    }
}
