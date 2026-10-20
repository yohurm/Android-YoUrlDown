package com.yohu.lib_urlparse.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
}
