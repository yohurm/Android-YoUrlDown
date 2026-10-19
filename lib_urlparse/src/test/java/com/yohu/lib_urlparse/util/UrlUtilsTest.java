package com.yohu.lib_urlparse.util;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link UrlUtils} 的测试。
 *
 * <p>用例都来自真实踩坑：分享文本里的链接常被中文、全角标点、成对括号包着，
 * 直接交给 {@code java.net.URI} 会抛异常，所以这里必须验证"脏文本里抠链接"的行为。
 */
public class UrlUtilsTest {

    private static final String MESSY = "看这个 https://v.douyin.com/iABCdef/ 复制打开抖音";

    @Test
    public void extractsUrlFromShareText() {
        assertEquals("https://v.douyin.com/iABCdef/", UrlUtils.extractFirstUrl(MESSY));
    }

    @Test
    public void trimsTrailingCjkPunctuationAndBrackets() {
        assertEquals("https://a.test/x", UrlUtils.extractFirstUrl("见（https://a.test/x）。"));
        assertEquals("https://a.test/y", UrlUtils.extractFirstUrl("链接:https://a.test/y, 然后"));
    }

    @Test
    public void strictVariantStopsAtCjk() {
        // 紧贴中文时必须截断，否则会把中文吞进 URL
        assertEquals("https://a.test/x", UrlUtils.findUrlStrict("链接https://a.test/x我"));
        assertEquals("https://a.test/x", UrlUtils.findUrlStrict("https://a.test/x"));
        assertNull(UrlUtils.findUrlStrict("没有链接"));
        assertNull(UrlUtils.findUrlStrict(null));
    }

    @Test
    public void extractsAllUrlsDeduplicated() {
        java.util.List<String> urls =
                UrlUtils.extractAllUrls("a https://a.test/1 b https://a.test/2 c https://a.test/1");
        assertEquals(2, urls.size());
        assertEquals("https://a.test/1", urls.get(0));
        assertEquals("https://a.test/2", urls.get(1));
    }

    @Test
    public void parsesAuthorityWithUserInfoAndPort() {
        String url = "https://user:pass@M.Example.COM:8080/p?q=1#frag";
        assertEquals("m.example.com", UrlUtils.host(url));
        assertEquals("https", UrlUtils.scheme(url));
        assertEquals("/p", UrlUtils.path(url));
        assertEquals("1", UrlUtils.queryParam(url, "q"));
        assertTrue(UrlUtils.isHttpUrl(url));
        assertEquals("https://user:pass@M.Example.COM:8080/p?q=1", UrlUtils.stripFragment(url));
    }

    @Test
    public void parsesQueryParamsDecoded() {
        Map<String, String> params = UrlUtils.queryParams("https://a.test/p?k=%E4%B8%AD&empty&x=1");
        assertEquals("中", params.get("k"));
        assertEquals("", params.get("empty"));
        assertEquals("1", params.get("x"));
    }

    @Test
    public void buildsQueryString() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("k", "中");
        params.put("n", "2");
        assertEquals("https://a.test/p?k=%E4%B8%AD&n=2",
                UrlUtils.withQuery("https://a.test/p", params));
        // 已有查询串时用 &
        assertEquals("https://a.test/p?a=1&k=%E4%B8%AD&n=2",
                UrlUtils.withQuery("https://a.test/p?a=1", params));
    }

    @Test
    public void recognizesBareIds() {
        assertTrue(UrlUtils.looksLikeBareId("7035542323689685535"));
        assertFalse(UrlUtils.looksLikeBareId("12345"));
        assertFalse(UrlUtils.looksLikeBareId("abc123456"));
        assertFalse(UrlUtils.looksLikeBareId(null));
    }

    @Test
    public void hostMatchesSuffixOnly() {
        assertTrue(UrlUtils.hostMatches("m.example.com", "example.com"));
        assertTrue(UrlUtils.hostMatches("example.com", "example.com"));
        // 不能误伤：notexample.com 不是 example.com 的子域
        assertFalse(UrlUtils.hostMatches("notexample.com", "example.com"));
        assertFalse(UrlUtils.hostMatches(null, "example.com"));
    }

    @Test
    public void handlesMalformedInputWithoutThrowing() {
        assertNull(UrlUtils.host(""));
        assertNull(UrlUtils.host("not a url"));
        assertEquals("", UrlUtils.path(""));
        assertFalse(UrlUtils.isHttpUrl("ftp://a.test/x"));
    }
}
