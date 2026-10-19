package com.yohu.lib_urlparse.fetch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 极简 Cookie 罐：把 {@code Set-Cookie} 存下来，后续请求按域带上。
 *
 * <p><b>为什么必须有</b>：此前 {@link DefaultHttpClient} **完全不处理 Cookie**——
 * 每个请求都是"第一次来访"，服务端下发的会话 Cookie 全部丢弃。实测影响：
 * <ul>
 *   <li>小红书：不带 Cookie 时笔记页一律 302 到 {@code /404/sec_*} 安全页；</li>
 *   <li>微博：必须先用访客接口拿 Cookie 再重试（此前靠手工提取+回填，容易漏）；</li>
 *   <li>B 站等站点也会下发 {@code buvid} 一类标识，缺少时行为不稳定。</li>
 * </ul>
 *
 * <p><b>范围</b>：只做解析引擎需要的最小集合——按域存取、按域发送、识别常见属性。
 * 不实现过期/安全策略/同源策略等浏览器语义（本引擎不是浏览器）。
 * <b>不做持久化</b>：Cookie 只活在当前引擎实例内，进程退出即失，避免把登录态落盘。
 */
public final class CookieJar {

    /** domain（小写，可能带前导点）→ (name → value)。 */
    private final Map<String, Map<String, String>> byDomain = new LinkedHashMap<>();

    /** 记录 {@code Set-Cookie} 头（一个头里可能含多个 Cookie）。 */
    public synchronized void absorb(String url, List<String> setCookieHeaders) {
        if (setCookieHeaders == null || setCookieHeaders.isEmpty()) {
            return;
        }
        String host = hostOf(url);
        if (host == null) {
            return;
        }
        for (String header : setCookieHeaders) {
            if (header == null || header.isEmpty()) {
                continue;
            }
            for (String one : splitCookies(header)) {
                parseOne(one, host);
            }
        }
    }

    /**
     * 拆开一个 Set-Cookie 头里的多个 Cookie。
     *
     * <p>难点：{@code Expires=Wed, 21 Oct 2026 07:28:00 GMT} 里也有逗号。
     * 因此只在"逗号后面紧跟 {@code name=}"时才切分——属性值里不会出现这种形态。
     */
    public static List<String> splitCookies(String header) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c == ',') {
                int j = i + 1;
                while (j < header.length() && header.charAt(j) == ' ') {
                    j++;
                }
                if (looksLikeCookieStart(header, j)) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    continue;
                }
            }
            cur.append(c);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /** 从 from 处是否是 {@code token=} 形态（且 token 不含分隔符）。 */
    private static boolean looksLikeCookieStart(String s, int from) {
        int eq = -1;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=') {
                eq = i;
                break;
            }
            if (c == ';' || c == ',' || c == ' ') {
                return false;
            }
        }
        return eq > from;
    }

    private void parseOne(String one, String host) {
        String[] parts = one.split(";");
        if (parts.length == 0) {
            return;
        }
        String pair = parts[0].trim();
        int eq = pair.indexOf('=');
        if (eq <= 0) {
            return;
        }
        String name = pair.substring(0, eq).trim();
        String value = pair.substring(eq + 1).trim();
        if (name.isEmpty()) {
            return;
        }
        String domain = host;
        for (int i = 1; i < parts.length; i++) {
            String attr = parts[i].trim();
            int aeq = attr.indexOf('=');
            if (aeq <= 0) {
                continue;
            }
            String k = attr.substring(0, aeq).trim().toLowerCase(Locale.ROOT);
            if ("domain".equals(k)) {
                String d = attr.substring(aeq + 1).trim().toLowerCase(Locale.ROOT);
                if (d.startsWith(".")) {
                    d = d.substring(1);
                }
                if (!d.isEmpty()) {
                    domain = d;
                }
            }
        }
        Map<String, String> jar = byDomain.get(domain);
        if (jar == null) {
            jar = new LinkedHashMap<>();
            byDomain.put(domain, jar);
        }
        // 空值表示删除该 Cookie（浏览器语义的简化版）
        if (value.isEmpty()) {
            jar.remove(name);
        } else {
            jar.put(name, value);
        }
    }

    /** 组装该 URL 应带的 {@code Cookie} 头；没有则返回 null。 */
    public synchronized String header(String url) {
        String host = hostOf(url);
        if (host == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Map<String, String>> e : byDomain.entrySet()) {
            if (!domainMatches(e.getKey(), host)) {
                continue;
            }
            for (Map.Entry<String, String> c : e.getValue().entrySet()) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(c.getKey()).append('=').append(c.getValue());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 域匹配：精确相等，或作为后缀（{@code xiaohongshu.com} 匹配 {@code www.xiaohongshu.com}）。 */
    public static boolean domainMatches(String cookieDomain, String host) {
        if (cookieDomain == null || host == null) {
            return false;
        }
        if (cookieDomain.equals(host)) {
            return true;
        }
        return host.endsWith("." + cookieDomain);
    }

    /** 取主机（小写，去端口）。 */
    public static String hostOf(String url) {
        if (url == null) {
            return null;
        }
        int s = url.indexOf("://");
        if (s < 0) {
            return null;
        }
        int e = url.indexOf('/', s + 3);
        String h = e < 0 ? url.substring(s + 3) : url.substring(s + 3, e);
        int colon = h.indexOf(':');
        if (colon >= 0) {
            h = h.substring(0, colon);
        }
        h = h.toLowerCase(Locale.ROOT).trim();
        return h.isEmpty() ? null : h;
    }

    public synchronized int size() {
        int n = 0;
        for (Map<String, String> m : byDomain.values()) {
            n += m.size();
        }
        return n;
    }

    /** 供测试/诊断：返回某域下所有 Cookie（副本）。 */
    public synchronized Map<String, String> cookiesFor(String domain) {
        Map<String, String> m = byDomain.get(domain.toLowerCase(Locale.ROOT));
        return m == null ? new LinkedHashMap<String, String>() : new LinkedHashMap<>(m);
    }

    public synchronized void clear() {
        byDomain.clear();
    }
}

