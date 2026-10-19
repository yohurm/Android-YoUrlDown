package com.yohu.lib_urlparse.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 一次 HTTP 响应。 */
public final class HttpResponse {

    private final int status;
    private final String body;
    private final String finalUrl;
    private final Map<String, String> headers;
    private final long elapsedMs;

    public HttpResponse(int status, String body, String finalUrl,
                        Map<String, String> headers, long elapsedMs) {
        this.status = status;
        this.body = body == null ? "" : body;
        this.finalUrl = finalUrl;
        this.headers = headers == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.elapsedMs = elapsedMs;
    }

    public int status() {
        return status;
    }

    public String body() {
        return body;
    }

    /** 跟随重定向后的最终 URL。 */
    public String finalUrl() {
        return finalUrl;
    }

    /** 响应头；键为小写，便于不区分大小写查找。 */
    public Map<String, String> headers() {
        return headers;
    }

    public long elapsedMs() {
        return elapsedMs;
    }

    /** 取响应头（不区分大小写）。 */
    public String header(String name) {
        if (name == null) {
            return null;
        }
        String direct = headers.get(name);
        if (direct != null) {
            return direct;
        }
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    public String contentType() {
        return header("content-type");
    }

    /** 2xx 视为成功。 */
    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    /** 正文长度（字符数，不是字节数）。 */
    public int length() {
        return body.length();
    }

    @Override
    public String toString() {
        return "HTTP " + status + " len=" + body.length() + " " + finalUrl;
    }
}
