package com.yohu.lib_urlparse.sniff;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Locale;

/**
 * 轻量 Content-Type 探测：一次 HEAD（必要时退化为极小 Range GET）拿到响应头。
 *
 * <p><b>为什么需要它（这是还原行为，不是自创）</b>：被分析样本的嗅探器用
 * {@code Util.getContent} 主动探测 Content-Type，只有拿到 {@code video/x-flv} 之类的证据
 * 才正式接受该地址（见 {@code 09_reports/08_嗅探_播放_下载.md} §「sniff.SniffingVideo」附近：
 * {@code Util.getContent} → {@code contentType} → {@code SniffingVideo(url, contentType, size, format)}）。
 * 原因很直接：WebView 的 {@code shouldInterceptRequest} **只给得到请求 URL，拿不到响应头**，
 * 因此对"URL 里看不出类型"的地址（尤其是图集里的图片）必须自己探一次。
 *
 * <p>成本控制：只对**形态上像媒体但缺证据**的少数候选调用；带超时；不下载正文
 * （HEAD 失败时只用 {@code Range: bytes=0-0} 取 1 字节）。纯 JVM 实现，可单测。
 */
public final class ContentTypeProbe {

    /** 探测结果。 */
    public static final class Result {
        public final String url;
        public final String contentType;
        public final long contentLength;
        public final boolean ok;
        public final String note;

        Result(String url, String contentType, long contentLength, boolean ok, String note) {
            this.url = url;
            this.contentType = contentType;
            this.contentLength = contentLength;
            this.ok = ok;
            this.note = note;
        }

        /** 去掉参数部分的 MIME 类型（小写）；未知时返回 null。 */
        public String mime() {
            if (contentType == null) {
                return null;
            }
            String ct = contentType.toLowerCase(Locale.ROOT);
            int semi = ct.indexOf(';');
            return (semi >= 0 ? ct.substring(0, semi) : ct).trim();
        }

        @Override
        public String toString() {
            return (ok ? "OK " : "FAIL ") + (mime() == null ? "(no type)" : mime())
                    + (contentLength > 0 ? " " + contentLength + "B" : "")
                    + (note.isEmpty() ? "" : " (" + note + ")");
        }
    }

    private final String userAgent;
    private final long connectTimeoutMs;
    private final long readTimeoutMs;
    /** 来源页（用于发送 Referer）；许多图床/视频 CDN 不带 Referer 会 403。 */
    private volatile String referer;

    public ContentTypeProbe() {
        this(null, 3_000L, 3_000L);
    }

    public ContentTypeProbe(String userAgent, long connectTimeoutMs, long readTimeoutMs) {
        this(userAgent, connectTimeoutMs, readTimeoutMs, null);
    }

    public ContentTypeProbe(String userAgent, long connectTimeoutMs, long readTimeoutMs,
                            String referer) {
        this.userAgent = userAgent;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.referer = referer;
    }

    /**
     * 探测一个 URL 的响应头。
     *
     * <p><b>会尝试把 {@code http://} 换成 {@code https://}</b>：Android 9（API 28）起
     * 默认禁止明文 HTTP，用 {@code http} 探测会直接抛异常、拿不到 Content-Type，
     * 于是"明明有图却因为没有 Content-Type 证据而被丢掉"。
     * 实测症状：公众号文章正文图片是 {@code http://mmbiz.qpic.cn/…}，
     * 13 个待探测里它们全部以 {@code [no content-type evidence]} 被拒。
     *
     * @return 永不返回 null；失败时 {@link Result#ok} 为 false 并带原因
     */
    public Result probe(String url) {
        if (url == null || url.trim().isEmpty()) {
            return new Result(url, null, -1L, false, "empty url");
        }
        Result first = probeOne(url);
        if (first.ok && first.contentType != null) {
            return first;
        }
        // 明文 HTTP 在 API 28+ 被系统拦截，且 http 与 https 常为同一资源：
        // 同 URL 再试一次 https 变体（仅当原先是 http）。
        String httpsUrl = toHttps(url);
        if (httpsUrl != null) {
            Result second = probeOne(httpsUrl);
            if (second.ok && second.contentType != null) {
                return second;
            }
            return moreInformative(first, second);
        }
        return first;
    }

    /**
     * 两次都失败时，选**更能用于排障**的那个。
     *
     * <p>优先保留带真实 HTTP 状态的那个：{@code HTTP 404} 能直接告诉人"地址不对"，
     * 而 https 重试失败往往只留下 TLS/连接异常。此前无条件返回重试结果，
     * 把有用的 404 信息盖掉了——这是被既有单测抓到的一处**诊断质量回退**。
     */
    public static Result moreInformative(Result first, Result second) {
        boolean firstHttp = first != null && first.note != null && first.note.startsWith("HTTP");
        boolean secondHttp = second != null && second.note != null && second.note.startsWith("HTTP");
        if (firstHttp && !secondHttp) {
            return first;
        }
        if (secondHttp && !firstHttp) {
            return second;
        }
        return first != null ? first : second;
    }

    /**
     * 设置 Referer（来源页）。嗅探时页面 URL 要在运行时才知道，所以这里可后设。
     * 传 null 表示不发 Referer。
     */
    public void setReferer(String referer) {
        this.referer = referer;
    }

    /** 把 http:// 变成 https://；本来就是 https 或无法识别则返回 null（表示无需重试）。 */
    public static String toHttps(String url) {
        if (url == null) {
            return null;
        }
        if (url.regionMatches(true, 0, "http://", 0, 7)) {
            return "https://" + url.substring(7);
        }
        return null;
    }

    private Result probeOne(String url) {
        Result head = attempt(url, true);
        if (head.ok && head.contentType != null) {
            return head;
        }
        // 有些服务器不支持 HEAD（405/501）或 HEAD 不给 Content-Type：用 1 字节 GET 兜底
        Result get = attempt(url, false);
        if (get.ok && get.contentType != null) {
            return get;
        }
        // 两个都不行：返回信息更全的那个（例如 HEAD 成功但无 Content-Type）
        return head.ok ? head : (get.ok ? get : (head.note.startsWith("HTTP") ? head : get));
    }

    private Result attempt(String url, boolean headOnly) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod(headOnly ? "HEAD" : "GET");
            conn.setConnectTimeout((int) connectTimeoutMs);
            conn.setReadTimeout((int) readTimeoutMs);
            conn.setInstanceFollowRedirects(true);
            if (userAgent != null) {
                conn.setRequestProperty("User-Agent", userAgent);
            }
            // 许多图床/视频 CDN 有防盗链：不带 Referer 直接 403。
            if (referer != null && !referer.isEmpty()) {
                conn.setRequestProperty("Referer", referer);
            }
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (!headOnly) {
                // 只要 1 字节：足够拿响应头，且不浪费流量
                conn.setRequestProperty("Range", "bytes=0-0");
            }
            int status = conn.getResponseCode();
            String ct = conn.getContentType();
            long len = conn.getContentLength();
            String range = conn.getHeaderField("Content-Range");
            if (range != null) {
                int slash = range.lastIndexOf('/');
                if (slash > 0 && slash < range.length() - 1) {
                    try {
                        len = Long.parseLong(range.substring(slash + 1).trim());
                    } catch (NumberFormatException ignored) {
                        // 保持 Content-Length
                    }
                }
            }
            if (status >= 200 && status < 400) {
                // 及时断开，不读正文
                if (!headOnly) {
                    drainQuietly(conn);
                }
                return new Result(url, ct, len, true,
                        "HTTP " + status + (headOnly ? " HEAD" : " GET(range)"));
            }
            return new Result(url, ct, len, false, "HTTP " + status);
        } catch (IOException | IllegalArgumentException e) {
            return new Result(url, null, -1L, false, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void drainQuietly(HttpURLConnection conn) {
        try {
            InputStream in = conn.getInputStream();
            if (in != null) {
                byte[] buf = new byte[64];
                in.read(buf);
                in.close();
            }
        } catch (IOException ignored) {
            // 只关心响应头，正文读不到也无所谓
        }
    }
}



