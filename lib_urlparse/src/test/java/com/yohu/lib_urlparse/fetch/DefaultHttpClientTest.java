package com.yohu.lib_urlparse.fetch;

import com.yohu.lib_urlparse.spi.HttpException;
import com.yohu.lib_urlparse.spi.HttpRequest;
import com.yohu.lib_urlparse.spi.HttpResponse;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link DefaultHttpClient} 的测试（用 MockWebServer 构造各种响应）。
 *
 * <p>这些用例覆盖的都是"曾经真的出错"的点：
 * <ul>
 *   <li>短链展开依赖**拿到重定向后的最终 URL**，所以要手动跟随重定向</li>
 *   <li>第二次请求必须带第一次下发的会话 Cookie，否则详情页 403</li>
 *   <li>4xx 不能抛异常——很多接口用 4xx 表达"要验证码"，上层要看到状态码</li>
 *   <li>超时必须是 {@code TIMEOUT} 而不是笼统的 IO 错误</li>
 * </ul>
 */
public class DefaultHttpClientTest {

    private MockWebServer server;
    private DefaultHttpClient client;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new DefaultHttpClient(2_000, 2_000, null, null);
    }

    @After
    public void tearDown() throws Exception {
        client.close();
        server.shutdown();
    }

    @Test
    public void followsRedirectAndReportsFinalUrl() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", "/final"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("done"));

        HttpResponse resp = client.get(server.url("/short").toString());
        assertEquals(200, resp.status());
        assertEquals("done", resp.body());
        assertTrue("最终 URL 必须是重定向后的地址", resp.finalUrl().endsWith("/final"));
    }

    @Test
    public void keepsSessionCookieAcrossRequests() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Set-Cookie", "sid=abc123; Path=/")
                .setBody("first"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("second"));

        client.get(server.url("/a").toString());
        client.get(server.url("/b").toString());

        RecordedRequest first = server.takeRequest();
        RecordedRequest second = server.takeRequest();
        assertEquals("/a", first.getPath());
        assertEquals("/b", second.getPath());
        assertNotNull("第二次请求必须带上第一次下发的 Cookie",
                second.getHeader("Cookie"));
        assertTrue(second.getHeader("Cookie").contains("sid=abc123"));
    }

    @Test
    public void explicitCookieHeaderWins() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        client.execute(HttpRequest.get(server.url("/x").toString())
                .header("Cookie", "manual=1")
                .build());
        RecordedRequest req = server.takeRequest();
        assertEquals("manual=1", req.getHeader("Cookie"));
    }

    @Test
    public void fourXxReturnsStatusInsteadOfThrowing() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(403).setBody("forbidden"));
        HttpResponse resp = client.get(server.url("/denied").toString());
        assertEquals(403, resp.status());
        assertTrue("4xx 的正文也要能拿到", resp.body().contains("forbidden"));
        assertEquals(false, resp.isSuccess());
    }

    @Test
    public void timeoutBecomesTimeoutKind() {
        // 服务器 2 秒不响应；客户端读超时 500ms
        server.enqueue(new MockResponse().setBodyDelay(2, TimeUnit.SECONDS).setBody("late"));
        DefaultHttpClient quick = new DefaultHttpClient(500, 500, null, null);
        try {
            quick.get(server.url("/slow").toString());
            fail("expected HttpException");
        } catch (HttpException e) {
            assertEquals(HttpException.Kind.TIMEOUT, e.kind());
        } finally {
            quick.close();
        }
    }

    @Test
    public void postSendsFormBodyAndContentType() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        client.postForm(server.url("/api").toString(), "a=1&b=2", null);

        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertEquals("a=1&b=2", req.getBody().readUtf8());
        assertTrue(String.valueOf(req.getHeader("Content-Type"))
                .contains("application/x-www-form-urlencoded"));
    }

    @Test
    public void handlesGzipResponse() throws Exception {
        // MockWebServer 的 setBody 不自动 gzip，这里显式用 gzip 字节 + 头
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bos);
        gz.write("hello-gzip".getBytes("UTF-8"));
        gz.close();
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Encoding", "gzip")
                .setBody(new okio.Buffer().write(bos.toByteArray())));

        HttpResponse resp = client.get(server.url("/gz").toString());
        assertEquals("hello-gzip", resp.body());
    }

    @Test
    public void tooManyRedirectsIsProtocolError() throws Exception {
        for (int i = 0; i < 8; i++) {
            server.enqueue(new MockResponse().setResponseCode(302)
                    .setHeader("Location", "/loop" + (i + 1)));
        }
        try {
            client.get(server.url("/loop0").toString());
            fail("expected HttpException");
        } catch (HttpException e) {
            assertEquals(HttpException.Kind.PROTOCOL, e.kind());
        }
    }
}
