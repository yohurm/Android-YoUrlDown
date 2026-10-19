package com.yohu.lib_urlparse.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * URL 解析与提取。**不依赖 {@code java.net.URI}**。
 *
 * <p>为什么不用 {@code URI}：分享文本里的链接经常带未转义的中文、空格或成对括号，
 * {@code new URI(...)} 会抛 {@code URISyntaxException}，而我们在"从一段脏文本里把链接抠出来"
 * 这个场景下恰恰必须容忍这些字符。所以这里用手写的宽容解析。
 *
 * <p>所有方法对非法/空输入返回 {@code null} 或空集合，不抛异常。
 */
public final class UrlUtils {

    /** 从文本里抓 URL：遇到空白、引号、尖括号、反斜杠即止。 */
    private static final Pattern URL_IN_TEXT =
            Pattern.compile("https?://[^\\s\"'<>\\\\`]+", Pattern.CASE_INSENSITIVE);

    /** 更严格的抓取：额外把中文与全角标点当作结束符（分享文案里常见「…链接 我…」）。 */
    private static final Pattern URL_IN_TEXT_STRICT =
            Pattern.compile("https?://[^\\s\"'<>\\\\`\\u4e00-\\u9fff\\u3000-\\u303f\\uff00-\\uffef]+",
                    Pattern.CASE_INSENSITIVE);

    /** 结尾常被误吞的标点。 */
    private static final String TRAILING_PUNCT = ".,;:!?)]}，。；：！？）】」』>";

    private UrlUtils() {
    }

    // ------------------------------------------------------------------ 组成

    /** 协议（小写），如 {@code https}；无法识别返回 {@code null}。 */
    public static String scheme(String url) {
        if (Strings.isBlank(url)) {
            return null;
        }
        int i = url.indexOf("://");
        return i <= 0 ? null : url.substring(0, i).toLowerCase(Locale.ROOT);
    }

    /** 主机（小写，去端口）；无法识别返回 {@code null}。 */
    public static String host(String url) {
        if (Strings.isBlank(url)) {
            return null;
        }
        int schemeEnd = url.indexOf("://");
        int from = schemeEnd >= 0 ? schemeEnd + 3 : 0;
        int to = from;
        while (to < url.length()) {
            char c = url.charAt(to);
            if (c == '/' || c == '?' || c == '#') {
                break;
            }
            to++;
        }
        if (to <= from) {
            return null;
        }
        String authority = url.substring(from, to);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        int colon = authority.lastIndexOf(':');
        if (colon > 0) {
            authority = authority.substring(0, colon);
        }
        if (authority.isEmpty()) {
            return null;
        }
        // 含空白或明显非法的字符就不是主机（例如把 "not a url" 整串当成了 authority）。
        for (int i = 0; i < authority.length(); i++) {
            char c = authority.charAt(i);
            if (Character.isWhitespace(c) || c == '<' || c == '>' || c == '"' || c == '`') {
                return null;
            }
        }
        return authority.toLowerCase(Locale.ROOT);
    }

    /** 路径部分（以 {@code /} 开头；没有则返回空串）。 */
    public static String path(String url) {
        if (Strings.isBlank(url)) {
            return "";
        }
        int from = url.indexOf("://");
        from = from >= 0 ? from + 3 : 0;
        int slash = url.indexOf('/', from);
        if (slash < 0) {
            return "";
        }
        int end = url.length();
        int q = url.indexOf('?', slash);
        int h = url.indexOf('#', slash);
        if (q >= 0 && q < end) {
            end = q;
        }
        if (h >= 0 && h < end) {
            end = h;
        }
        return url.substring(slash, end);
    }

    /** 去掉 {@code #fragment}。 */
    public static String stripFragment(String url) {
        if (url == null) {
            return null;
        }
        int i = url.indexOf('#');
        return i < 0 ? url : url.substring(0, i);
    }

    /** 是否 http/https。 */
    public static boolean isHttpUrl(String s) {
        if (Strings.isBlank(s)) {
            return false;
        }
        String scheme = scheme(s);
        return "http".equals(scheme) || "https".equals(scheme);
    }

    // ------------------------------------------------------------------ 提取

    /**
     * 从一段文本里取第一个 URL（宽容版）。
     *
     * @return 去掉了尾部标点的 URL；没有则 {@code null}
     */
    public static String extractFirstUrl(String text) {
        return firstMatch(URL_IN_TEXT, text);
    }

    /** 从一段文本里取第一个 URL（把中文/全角标点视作结束符）。 */
    public static String findUrlStrict(String text) {
        return firstMatch(URL_IN_TEXT_STRICT, text);
    }

    /** 取出文本里全部 URL（去重、保序）。 */
    public static List<String> extractAllUrls(String text) {
        List<String> out = new ArrayList<>();
        if (Strings.isBlank(text)) {
            return out;
        }
        Matcher m = URL_IN_TEXT.matcher(text);
        while (m.find()) {
            String url = trimTrailingPunct(m.group());
            if (!url.isEmpty() && !out.contains(url)) {
                out.add(url);
            }
        }
        return out;
    }

    private static String firstMatch(Pattern pattern, String text) {
        if (Strings.isBlank(text)) {
            return null;
        }
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return null;
        }
        String url = trimTrailingPunct(m.group());
        return url.isEmpty() ? null : url;
    }

    private static String trimTrailingPunct(String url) {
        int end = url.length();
        while (end > 0 && TRAILING_PUNCT.indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return url.substring(0, end);
    }

    /** 纯数字 ID（分享文本里常见直接粘一串数字）。 */
    public static boolean looksLikeBareId(String text) {
        String t = Strings.trim(text);
        if (t.length() < 6) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            if (!Character.isDigit(t.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ 查询串

    /** 取单个查询参数（URL 解码后）；不存在返回 {@code null}。 */
    public static String queryParam(String url, String name) {
        return queryParams(url).get(name);
    }

    /** 解析查询串为有序 Map（值已 URL 解码；无值参数映射为空串）。 */
    public static Map<String, String> queryParams(String url) {
        Map<String, String> out = new LinkedHashMap<>();
        if (Strings.isBlank(url)) {
            return out;
        }
        int q = url.indexOf('?');
        if (q < 0 || q + 1 >= url.length()) {
            return out;
        }
        int end = url.indexOf('#', q);
        String query = end < 0 ? url.substring(q + 1) : url.substring(q + 1, end);
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                out.put(EncodingUtils.urlDecode(pair), "");
            } else {
                out.put(EncodingUtils.urlDecode(pair.substring(0, eq)),
                        EncodingUtils.urlDecode(pair.substring(eq + 1)));
            }
        }
        return out;
    }

    /** 把参数拼到 URL 上（已解码的值会被重新编码）。 */
    public static String withQuery(String url, Map<String, String> params) {
        if (url == null || params == null || params.isEmpty()) {
            return url;
        }
        StringBuilder sb = new StringBuilder(url);
        boolean hasQuery = url.indexOf('?') >= 0;
        for (Map.Entry<String, String> e : params.entrySet()) {
            sb.append(hasQuery ? '&' : '?');
            hasQuery = true;
            sb.append(EncodingUtils.urlEncode(e.getKey()));
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                sb.append('=').append(EncodingUtils.urlEncode(e.getValue()));
            }
        }
        return sb.toString();
    }

    /** 主机是否等于给定域名或其后缀（{@code m.example.com} 匹配 {@code example.com}）。 */
    public static boolean hostMatches(String host, String domain) {
        if (host == null || domain == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        String d = domain.toLowerCase(Locale.ROOT);
        return h.equals(d) || h.endsWith("." + d);
    }
}
