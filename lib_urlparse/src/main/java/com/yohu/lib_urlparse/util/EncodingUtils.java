package com.yohu.lib_urlparse.util;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 编码与摘要工具。
 *
 * <p><b>为什么不用 {@code java.util.Base64}</b>：它直到 Android API 26 才存在，而本库的
 * {@code minSdk} 是 24。core library desugaring 不覆盖它，所以这里手写编解码，
 * 顺带保证行为在各 API 级别完全一致（不依赖平台实现差异）。
 *
 * <p>全部方法对 {@code null} 输入返回 {@code null} 或空串（不抛异常）——
 * 解析链路上大量字段是可缺失的，让调用方每次都判空只会把 {@code null} 检查扩散到全项目。
 */
public final class EncodingUtils {

    /** UTF-8；常量避免每次 {@code Charset.forName} 查找。 */
    public static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final char[] HEX_LOWER = "0123456789abcdef".toCharArray();
    private static final char[] HEX_UPPER = "0123456789ABCDEF".toCharArray();

    private static final char[] B64_STD =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final char[] B64_URL =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    private EncodingUtils() {
    }

    // ------------------------------------------------------------------ Base64

    /** 标准 Base64 编码。 */
    public static String encodeBase64(byte[] data) {
        return encodeBase64(data, B64_STD, '=');
    }

    /** URL 安全 Base64 编码（{@code -_}、无填充）。 */
    public static String encodeBase64Url(byte[] data) {
        return encodeBase64(data, B64_URL, '\0');
    }

    /**
     * 标准 Base64 解码，**宽容**处理：
     * <ul>
     *   <li>忽略空白与换行（页面里的 JSON 字段常被折行）</li>
     *   <li>接受 URL 安全字符 {@code -_} 与标准字符 {@code +/}</li>
     *   <li>允许缺少尾部填充</li>
     * </ul>
     * 非法字符会导致返回 {@code null}（而不是抛异常）。
     */
    public static byte[] decodeBase64(String text) {
        if (text == null) {
            return null;
        }
        return decodeBase64Internal(text);
    }

    /** 解码并按 UTF-8 转字符串；失败返回 {@code null}。 */
    public static String decodeBase64ToString(String text) {
        byte[] raw = decodeBase64(text);
        return raw == null ? null : new String(raw, UTF_8);
    }

    private static String encodeBase64(byte[] data, char[] table, char pad) {
        if (data == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(((data.length + 2) / 3) * 4);
        int i = 0;
        while (i + 2 < data.length) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            out.append(table[(n >>> 18) & 0x3F]);
            out.append(table[(n >>> 12) & 0x3F]);
            out.append(table[(n >>> 6) & 0x3F]);
            out.append(table[n & 0x3F]);
            i += 3;
        }
        int remain = data.length - i;
        if (remain == 1) {
            int n = (data[i] & 0xFF) << 16;
            out.append(table[(n >>> 18) & 0x3F]);
            out.append(table[(n >>> 12) & 0x3F]);
            if (pad != '\0') {
                out.append(pad).append(pad);
            }
        } else if (remain == 2) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            out.append(table[(n >>> 18) & 0x3F]);
            out.append(table[(n >>> 12) & 0x3F]);
            out.append(table[(n >>> 6) & 0x3F]);
            if (pad != '\0') {
                out.append(pad);
            }
        }
        return out.toString();
    }

    private static byte[] decodeBase64Internal(String text) {
        int len = text.length();
        ByteArrayOutputStream out = new ByteArrayOutputStream((len / 4) * 3 + 3);
        int buffer = 0;
        int bits = 0;
        boolean sawPadding = false;
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '=') {
                sawPadding = true;
                continue;
            }
            int v;
            if (c >= 'A' && c <= 'Z') {
                v = c - 'A';
            } else if (c >= 'a' && c <= 'z') {
                v = c - 'a' + 26;
            } else if (c >= '0' && c <= '9') {
                v = c - '0' + 52;
            } else if (c == '+' || c == '-') {
                v = 62;
            } else if (c == '/' || c == '_') {
                v = 63;
            } else if (Character.isWhitespace(c)) {
                continue;
            } else {
                return null; // 非法字符
            }
            if (sawPadding) {
                return null; // 填充之后还有有效字符，视为非法
            }
            buffer = (buffer << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >>> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ 摘要

    /** MD5 小写十六进制；输入 {@code null} 返回 {@code null}。 */
    public static String md5Hex(String text) {
        return digestHex("MD5", text);
    }

    /** SHA-256 小写十六进制。 */
    public static String sha256Hex(String text) {
        return digestHex("SHA-256", text);
    }

    /** 对字节做摘要并转小写十六进制。 */
    public static String digestHex(String algorithm, byte[] data) {
        if (data == null) {
            return null;
        }
        try {
            return toHexLower(MessageDigest.getInstance(algorithm).digest(data));
        } catch (NoSuchAlgorithmException e) {
            // MD5 / SHA-256 是 JLS 要求必须支持的算法，走到这里说明运行环境异常。
            throw new IllegalStateException(algorithm + " unavailable", e);
        }
    }

    private static String digestHex(String algorithm, String text) {
        return text == null ? null : digestHex(algorithm, text.getBytes(UTF_8));
    }

    // ------------------------------------------------------------------ Hex

    public static String toHexLower(byte[] data) {
        return toHex(data, HEX_LOWER);
    }

    public static String toHexUpper(byte[] data) {
        return toHex(data, HEX_UPPER);
    }

    private static String toHex(byte[] data, char[] table) {
        if (data == null) {
            return null;
        }
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            out[i * 2] = table[v >>> 4];
            out[i * 2 + 1] = table[v & 0x0F];
        }
        return new String(out);
    }

    /** 十六进制转字节；长度非法或含非十六进制字符返回 {@code null}。 */
    public static byte[] fromHex(String hex) {
        if (hex == null || (hex.length() & 1) != 0) {
            return null;
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = hexValue(hex.charAt(i * 2));
            int lo = hexValue(hex.charAt(i * 2 + 1));
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    // ------------------------------------------------------------------ URL 编解码

    /** {@code application/x-www-form-urlencoded} 编码；失败返回空串。 */
    public static String urlEncode(String s) {
        if (s == null) {
            return "";
        }
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    /** 解码；非法百分号序列时返回原串（而不是抛异常）。 */
    public static String urlDecode(String s) {
        if (s == null) {
            return "";
        }
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return s;
        }
    }

    /** UTF-8 字节；{@code null} 返回空数组。 */
    public static byte[] utf8(String s) {
        return s == null ? new byte[0] : s.getBytes(UTF_8);
    }

    /** UTF-8 解码；{@code null} 返回空串。 */
    public static String fromUtf8(byte[] data) {
        return data == null ? "" : new String(data, UTF_8);
    }

}