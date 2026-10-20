package com.yohu.lib_urlparse.util;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.Charset;

/**
 * 编码工具（Base64 与 URL 解码）。
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

    private static final char[] B64_STD =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private EncodingUtils() {
    }

    // ------------------------------------------------------------------ Base64

    /** 标准 Base64 编码。 */
    public static String encodeBase64(byte[] data) {
        return encodeBase64(data, B64_STD, '=');
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

    /**
     * 标准 Base64 解码（与 {@link #decodeBase64(String)} 同义）。
     *
     * <p>保留这个名字是因为移植自旧工程的规则代码按此名调用，且"标准 vs URL 安全"在
     * 阅读时是有意义的区分。
     */
    public static byte[] decodeBase64Standard(String text) {
        return decodeBase64(text);
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

    // ------------------------------------------------------------------ URL 编解码

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
}
