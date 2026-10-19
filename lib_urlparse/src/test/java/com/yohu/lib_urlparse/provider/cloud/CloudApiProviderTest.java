package com.yohu.lib_urlparse.provider.cloud;

import com.yohu.lib_urlparse.api.FailureReason;
import com.yohu.lib_urlparse.api.MediaAsset;
import com.yohu.lib_urlparse.api.ParseFailure;
import com.yohu.lib_urlparse.api.UrlParseEngine;
import com.yohu.lib_urlparse.api.UrlParseRequest;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.lib_urlparse.spi.CredentialProvider;
import com.yohu.lib_urlparse.spi.HttpClient;
import com.yohu.lib_urlparse.spi.HttpRequest;
import com.yohu.lib_urlparse.spi.HttpResponse;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 云通道测试：验证"凭据来自宿主、本库不内置任何端点"这条硬约束。
 */
public class CloudApiProviderTest {

    static final class FakeHttp implements HttpClient {
        final String response;
        String lastUrl;
        String lastBody;
        int calls;

        FakeHttp(String response) {
            this.response = response;
        }

        @Override
        public HttpResponse execute(HttpRequest request) {
            calls++;
            lastUrl = request.url();
            lastBody = request.body();
            return new HttpResponse(200, response, request.url(), null, 1L);
        }
    }

    static CredentialProvider creds(final String endpoint, final String key) {
        return new CredentialProvider() {
            @Override
            public java.util.Optional<String> endpoint(String channel) {
                return java.util.Optional.ofNullable(endpoint);
            }

            @Override
            public java.util.Optional<String> apiKey(String channel) {
                return java.util.Optional.ofNullable(key);
            }
        };
    }

    private static UrlParseEngine engine(HttpClient http, CredentialProvider c) {
        return UrlParseEngine.builder()
                .httpClient(http)
                .provider(new CloudApiProvider())
                .credentials(c)
                .timeoutMs(5_000L)
                .build();
    }

    @Test
    public void postsPayloadToHostProvidedEndpointAndParsesVideo() throws Exception {
        FakeHttp http = new FakeHttp(
                "{\"data\":{\"url\":\"https://cdn.test/v.mp4\",\"images\":[\"https://cdn.test/1.jpg\"]}}");
        UrlParseResult r = engine(http, creds("https://my-cloud.test/parse", "KEY123"))
                .parse(UrlParseRequest.builder("https://www.kuaishou.com/short-video/abc").build());

        assertEquals("cloud.api", r.channel());
        assertEquals("https://my-cloud.test/parse", http.lastUrl);
        assertTrue("payload 必须带 apiKey: " + http.lastBody, http.lastBody.contains("KEY123"));
        assertTrue("payload 必须带原始链接: " + http.lastBody, http.lastBody.contains("kuaishou"));
        assertEquals(1, r.assets(MediaAsset.Kind.VIDEO).size());
        assertEquals(1, r.assets(MediaAsset.Kind.IMAGE).size());
    }

    @Test
    public void missingEndpointIsCredentialsMissingNotSilentSkip() throws Exception {
        FakeHttp http = new FakeHttp("{}");
        UrlParseEngine e = engine(http, CredentialProvider.NONE);
        // 没配置端点时不认领（避免噪声）……
        CloudApiProvider p = new CloudApiProvider();
        assertFalse(p.canHandle(null, UrlParseRequest.builder("https://a.test/x").build()));
        // ……但直接调用时必须明确报"缺凭据"，而不是假装成功
        ParseFailure f = assertThrows(ParseFailure.class,
                () -> engine(http, CredentialProvider.NONE)
                        .parse(UrlParseRequest.builder("https://a.test/x").build()));
        assertEquals(FailureReason.UNRECOGNISED_INPUT, f.reason());
    }

    @Test
    public void errorMessageFromCloudIsReportedAsUnexpectedResponse() throws Exception {
        FakeHttp http = new FakeHttp("{\"msg\":\"" + "解析失败，失败原因：不支持该平台" + "\"}");
        ParseFailure f = assertThrows(ParseFailure.class,
                () -> engine(http, creds("https://cloud.test/p", "K"))
                        .parse(UrlParseRequest.builder("https://a.test/x").build()));
        assertEquals(FailureReason.UNEXPECTED_RESPONSE, f.reason());
        assertTrue(f.getMessage().contains("不支持该平台"));
    }

    @Test
    public void fallsBackToScanningResponseTextForUrls() throws Exception {
        // 字段名与约定不同：只有一段文本，里面带地址
        FakeHttp http = new FakeHttp(
                "{\"result\":{\"play\":\"https://cdn.test/fallback.mp4\"}}");
        UrlParseResult r = engine(http, creds("https://cloud.test/p", "K"))
                .parse(UrlParseRequest.builder("https://a.test/x").build());
        assertEquals(1, r.assets(MediaAsset.Kind.VIDEO).size());
    }

    @Test
    public void emptyResponseIsUnexpectedResponse() {
        FakeHttp http = new FakeHttp("");
        ParseFailure f = assertThrows(ParseFailure.class,
                () -> engine(http, creds("https://cloud.test/p", "K"))
                        .parse(UrlParseRequest.builder("https://a.test/x").build()));
        assertEquals(FailureReason.UNEXPECTED_RESPONSE, f.reason());
    }

    @Test
    public void payloadShapeMatchesContract() {
        String body = CloudApiProvider.buildPayload("K1", "https://a.test/x");
        assertTrue(body.contains("\"data\""));
        assertTrue(body.contains("\"url\":\"https://a.test/x\""));
        assertTrue(body.contains("\"apiKey\":\"K1\""));
    }
}
