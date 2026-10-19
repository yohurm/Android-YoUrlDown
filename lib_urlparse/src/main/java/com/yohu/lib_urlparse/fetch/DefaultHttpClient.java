package com.yohu.lib_urlparse.fetch;

import com.yohu.lib_urlparse.spi.CancelToken;
import com.yohu.lib_urlparse.spi.HttpClient;
import com.yohu.lib_urlparse.spi.HttpException;
import com.yohu.lib_urlparse.spi.HttpRequest;
import com.yohu.lib_urlparse.spi.HttpResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 基于 {@link HttpURLConnection} 的默认实现：**零第三方依赖**，自动处理 gzip 与会话 Cookie。
 *
 * <p>几个刻意的行为：
 * <ul>
 *   <li><b>不自动跟随重定向</b>：由本类手动跟随，这样才能在每一跳都保持 Cookie、
 *       并拿到"真实最终 URL"（短链展开依赖它）。</li>
 *   <li><b>保存 Set-Cookie</b>：不少站点的第二次请求必须带第一次下发的会话 Cookie，
 *       旧实现曾经完全丢弃 Set-Cookie，导致"首页能取、详情页 403"。</li>
 *   <li><b>调用方显式 Cookie 优先</b>：调用方自己设了 {@code Cookie} 头时不去覆盖
 *       （那是明确的意图，比如用户已登录的会话）。</li>
 *   <li><b>非 2xx 不抛异常</b>：把状态码如实交给上层判断（很多接口用 4xx 表达"要验证码"）。</li>
 * </ul>
 *
 * <p>线程安全：实例持有一个 Cookie 罐且无其它可变状态，可并发使用；
 * Cookie 罐自身是同步的。
 */
public final class DefaultHttpClient implements HttpClient {

    /** 最多跟随多少跳重定向。超过则返回最后一次响应（由上层判断）。 */
    private static final int MAX_REDIRECTS = 5;

    /** 默认 UA：移动端 Safari，多数分享页会返回简化页面。 */
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/139.0.0.0 Mobile Safari/537.36";

    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final String defaultUserAgent;
    private final CancelToken cancelToken;
    private final CookieJar cookies = new CookieJar();

    public DefaultHttpClient() {
        this(15_000, 20_000, DEFAULT_UA, null);
    }

    public DefaultHttpClient(int connectTimeoutMs, int readTimeoutMs, String userAgent,
                             CancelToken cancelToken) {
        this.connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : 15_000;
        this.readTimeoutMs = readTimeoutMs > 0 ? readTimeoutMs : 20_000;
        this.defaultUserAgent = userAgent == null || userAgent.isEmpty() ? DEFAULT_UA : userAgent;
        this.cancelToken = cancelToken;
    }

    /** 暴露 Cookie 罐，便于宿主诊断与测试。 */
    public CookieJar cookieJar() {
        return cookies;
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws HttpException {
        String currentUrl = request.url();
        HttpRequest.Method method = request.method();
        String body = request.body();
        String contentType = request.contentType();

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkCancelled();
            Connection conn = open(currentUrl, method, body, contentType, request.headers());
            int status = conn.status;
            String location = conn.header("location");

            if (isRedirect(status) && location != null && !location.isEmpty()) {
                String next = resolve(currentUrl, location);
                // 303 一律转 GET；301/302/307/308 保留原方法（307/308 语义要求保留）。
                if (status == 303) {
                    method = HttpRequest.Method.GET;
                    body = null;
                    contentType = null;
                }
                currentUrl = next;
                conn.close();
                continue;
            }

            return new HttpResponse(status, conn.body, conn.finalUrl, conn.headers, conn.elapsedMs);
        }
        // 超过跳数上限：返回最后一跳的响应，让上层决定怎么处理。
        throw new HttpException(HttpException.Kind.PROTOCOL, 0,
                "too many redirects (> " + MAX_REDIRECTS + "): " + request.url());
    }

    private void checkCancelled() throws HttpException {
        if (cancelToken != null && cancelToken.isCancelled()) {
            throw new HttpException(HttpException.Kind.CANCELLED, 0, "cancelled by caller");
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private Connection open(String url, HttpRequest.Method method, String body, String contentType,
                            Map<String, String> headers) throws HttpException {
        long start = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            // 手动跟随，才能逐跳保留 Cookie 并拿到真实最终 URL。
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod(method == HttpRequest.Method.POST ? "POST" : "GET");

            conn.setRequestProperty("User-Agent", defaultUserAgent);
            conn.setRequestProperty("Accept-Encoding", "gzip");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");

            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) {
                        continue;
                    }
                    if ("host".equalsIgnoreCase(e.getKey())) {
                        continue; // 不允许调用方改 Host
                    }
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }

            // 带上该域已存下的 Cookie；调用方显式设置的 Cookie 优先。
            if (!containsKeyIgnoreCase(headers, "Cookie")) {
                String cookie = cookies.header(url);
                if (cookie != null && !cookie.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookie);
                }
            }

            if (method == HttpRequest.Method.POST) {
                conn.setDoOutput(true);
                if (contentType != null) {
                    conn.setRequestProperty("Content-Type", contentType);
                }
                byte[] payload = body == null ? new byte[0] : body.getBytes("UTF-8");
                conn.setFixedLengthStreamingMode(payload.length);
                OutputStream out = conn.getOutputStream();
                try {
                    out.write(payload);
                } finally {
                    out.close();
                }
            }

            int status = conn.getResponseCode();
            cookies.absorb(url, headerValuesIgnoreCase(conn, "Set-Cookie"));

            InputStream rawStream;
            try {
                rawStream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            } catch (IOException e) {
                // 4xx 且没有 errorStream 时，把状态码带出去
                throw new HttpException(HttpException.Kind.STATUS, status,
                        "no response body for HTTP " + status + " " + url, e);
            }
            String text = readBody(rawStream, conn.getContentEncoding());

            Map<String, String> responseHeaders = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) {
                    continue;
                }
                String name = e.getKey().toLowerCase(Locale.ROOT);
                // Set-Cookie 必须保留**全部**取值：同名 Cookie 的不同属性/不同 Cookie 会分多条下发，
                // 只留第一条会丢掉会话状态（B 站风控要求 buvid3 与 buvid4 同时带上）。
                // 用换行分隔，避免与 Cookie 值里合法的逗号（Expires 日期）混淆。
                String join = "set-cookie".equals(name) ? "\n" : ", ";
                responseHeaders.put(name, String.join(join, e.getValue()));
            }

            String finalUrl = conn.getURL() == null ? url : conn.getURL().toString();
            return new Connection(status, text, finalUrl, responseHeaders,
                    System.currentTimeMillis() - start);
        } catch (HttpException e) {
            if (conn != null) {
                conn.disconnect();
            }
            throw e;
        } catch (java.net.SocketTimeoutException e) {
            if (conn != null) {
                conn.disconnect();
            }
            throw new HttpException(HttpException.Kind.TIMEOUT, 0,
                    "timeout after " + readTimeoutMs + "ms: " + url, e);
        } catch (IOException e) {
            if (conn != null) {
                conn.disconnect();
            }
            throw new HttpException(HttpException.Kind.CONNECT, 0,
                    "connect failed: " + url + " (" + e + ")", e);
        }
    }

    private static String readBody(InputStream raw, String encoding) throws IOException {
        if (raw == null) {
            return "";
        }
        InputStream in = "gzip".equalsIgnoreCase(encoding) ? new GZIPInputStream(raw) : raw;
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return out.toString("UTF-8");
    }

    private static List<String> headerValuesIgnoreCase(HttpURLConnection conn, String name) {
        Map<String, List<String>> fields = conn.getHeaderFields();
        for (Map.Entry<String, List<String>> e : fields.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return java.util.Collections.emptyList();
    }

    private static boolean containsKeyIgnoreCase(Map<String, String> map, String key) {
        if (map == null) {
            return false;
        }
        for (String k : map.keySet()) {
            if (k != null && k.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    /** 相对 Location → 绝对 URL。 */
    private static String resolve(String base, String location) {
        try {
            return new URL(new URL(base), location).toString();
        } catch (IOException e) {
            return location;
        }
    }

    @Override
    public void close() {
        cookies.clear();
    }

    /** 一次请求的结果载体（内部用）。 */
    private static final class Connection {
        final int status;
        final String body;
        final String finalUrl;
        final Map<String, String> headers;
        final long elapsedMs;

        Connection(int status, String body, String finalUrl, Map<String, String> headers,
                   long elapsedMs) {
            this.status = status;
            this.body = body;
            this.finalUrl = finalUrl;
            this.headers = headers;
            this.elapsedMs = elapsedMs;
        }

        String header(String name) {
            if (headers == null) {
                return null;
            }
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        void close() {
            // 连接由 HttpURLConnection 自身管理；这里只保留语义占位。
        }
    }
}
