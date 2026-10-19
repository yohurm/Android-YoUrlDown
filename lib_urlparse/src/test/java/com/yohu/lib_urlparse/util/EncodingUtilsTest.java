package com.yohu.lib_urlparse.util;

import org.junit.Test;

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

    @Test
    public void base64EncodesKnownVectors() {
        assertEquals("", EncodingUtils.encodeBase64(new byte[0]));
        assertEquals("YQ==", EncodingUtils.encodeBase64(EncodingUtils.utf8("a")));
        assertEquals("YWI=", EncodingUtils.encodeBase64(EncodingUtils.utf8("ab")));
        assertEquals("YWJj", EncodingUtils.encodeBase64(EncodingUtils.utf8("abc")));
        assertEquals("YWJjZA==", EncodingUtils.encodeBase64(EncodingUtils.utf8("abcd")));
    }

    @Test
    public void base64UrlUsesDifferentTableWithoutPadding() {
        byte[] raw = EncodingUtils.fromHex("fafeff");
        assertEquals("+v7/", EncodingUtils.encodeBase64(raw));
        assertEquals("-v7_", EncodingUtils.encodeBase64Url(raw));
    }

    @Test
    public void base64DecodeIsLenient() {
        assertEquals("abc", EncodingUtils.decodeBase64ToString("YWJj"));
        // 缺填充
        assertEquals("a", EncodingUtils.decodeBase64ToString("YQ"));
        // 含换行/空白（页面 JSON 里常被折行）
        assertEquals("abc", EncodingUtils.decodeBase64ToString("YW\nJj "));
        // URL 安全字符
        assertArrayEquals(EncodingUtils.fromHex("fafeff"), EncodingUtils.decodeBase64("-v7_"));
        // 非法字符返回 null 而不是抛异常
        assertNull(EncodingUtils.decodeBase64("YW*j"));
    }

    @Test
    public void digestsMatchKnownVectors() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", EncodingUtils.md5Hex("abc"));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                EncodingUtils.sha256Hex("abc"));
        assertNull(EncodingUtils.md5Hex(null));
    }

    @Test
    public void hexRoundTrips() {
        byte[] raw = EncodingUtils.fromHex("00ff10ab");
        assertArrayEquals(new byte[]{0x00, (byte) 0xFF, 0x10, (byte) 0xAB}, raw);
        assertEquals("00ff10ab", EncodingUtils.toHexLower(raw));
        assertEquals("00FF10AB", EncodingUtils.toHexUpper(raw));
        // 奇数长度 / 非十六进制字符 → null
        assertNull(EncodingUtils.fromHex("abc"));
        assertNull(EncodingUtils.fromHex("zz"));
    }

    @Test
    public void urlCodecHandlesUtf8AndBadSequences() {
        assertEquals("%E4%B8%AD", EncodingUtils.urlEncode("中"));
        assertEquals("中", EncodingUtils.urlDecode("%E4%B8%AD"));
        // 非法百分号序列：返回原串，不抛异常
        assertEquals("%E4%", EncodingUtils.urlDecode("%E4%"));
        assertEquals("", EncodingUtils.urlEncode(null));
        assertEquals("", EncodingUtils.urlDecode(null));
    }

    @Test
    public void utf8HelpersAreNullSafe() {
        assertEquals(0, EncodingUtils.utf8(null).length);
        assertEquals("", EncodingUtils.fromUtf8(null));
        assertEquals("中文", EncodingUtils.fromUtf8(EncodingUtils.utf8("中文")));
    }
}
