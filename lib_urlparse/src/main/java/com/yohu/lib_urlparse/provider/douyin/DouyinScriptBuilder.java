package com.yohu.lib_urlparse.provider.douyin;

import com.yohu.lib_urlparse.rules.DouyinRules;

/**
 * 构造注入抖音页面的解析脚本：{@code <前缀> + JSON 字符串字面量 + <后缀>}，
 * 前缀以 {@code DouyinParser.parse(} 结尾，后缀以 {@code ).then(report)...} 开头。
 */
public final class DouyinScriptBuilder {

    private DouyinScriptBuilder() {
    }

    /**
     * 生成注入脚本。
     *
     * @param jsonLiteral 已被 JSON 转义的输入文本（调用方负责，见 {@link #quote(String)}）
     */
    public static String build(String jsonLiteral) {
        String arg = jsonLiteral == null || jsonLiteral.isEmpty() ? "\"\"" : jsonLiteral;
        return DouyinRules.SCRIPT_BOOTSTRAP_PREFIX + arg + DouyinRules.SCRIPT_BOOTSTRAP_SUFFIX;
    }

    /**
     * 把任意文本转义成合法的 JSON 字符串字面量（含两端引号）。
     *
     * <p>与 {@code org.json.JSONObject.quote(String)} 等价的最小实现，
     * 不依赖 Android 的 org.json，纯 JVM 单测也能用。
     */
    public static String quote(String raw) {
        if (raw == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
