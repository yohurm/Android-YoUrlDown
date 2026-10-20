package com.yohu.lib_urlparse.util;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * {@link EncodingUtils} 的测试。
 *
 * <p>重点在 Base64：因为不用平台的 {@code java.util.Base64}（API 26 才有，而 minSdk 24），
 * 手写实现必须用**已知答案**钉住，否则错一位都很难发现（解析出来的 token 会看起来"能用但报错"）。
 */
public class EncodingUtilsTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void base64EncodesKnownVectors() {
        assertEquals("", EncodingUtils.encodeBase64(new byte[0]));
        assertEquals("YQ==", EncodingUtils.encodeBase64(utf8("a")));
        assertEquals("YWI=", EncodingUtils.encodeBase64(utf8("ab")));
        assertEquals("YWJj", EncodingUtils.encodeBase64(utf8("abc")));
        assertEquals("YWJjZA==", EncodingUtils.encodeBase64(utf8("abcd")));
        assertEquals("+v7/", EncodingUtils.encodeBase64(new byte[]{(byte) 0xFA, (byte) 0xFE, (byte) 0xFF}));
    }

    @Test
    public void base64DecodeIsLenient() {
        assertArrayEquals(utf8("abc"), EncodingUtils.decodeBase64("YWJj"));
        // 缺填充
        assertArrayEquals(utf8("a"), EncodingUtils.decodeBase64("YQ"));
        // 含换行/空白（页面 JSON 里常被折行）
        assertArrayEquals(utf8("abc"), EncodingUtils.decodeBase64("YW\nJj "));
        // URL 安全字符
        assertArrayEquals(new byte[]{(byte) 0xFA, (byte) 0xFE, (byte) 0xFF},
                EncodingUtils.decodeBase64("-v7_"));
        // 非法字符返回 null 而不是抛异常
        assertNull(EncodingUtils.decodeBase64("YW*j"));
    }

    @Test
    public void urlDecodeHandlesUtf8AndBadSequences() {
        assertEquals("中", EncodingUtils.urlDecode("%E4%B8%AD"));
        // 非法百分号序列：返回原串，不抛异常
        assertEquals("%E4%", EncodingUtils.urlDecode("%E4%"));
        assertEquals("", EncodingUtils.urlDecode(null));
    }
}
