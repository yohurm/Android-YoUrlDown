package com.yohu.lib_urlparse.provider.douyin;

import com.yohu.lib_urlparse.rules.DouyinRules;

/**
 * 构造注入抖音页面的解析脚本。
 *
 * <p><b>出处</b>：方法 {@code DouyinWebParser.buildParseScript(String):String}
 * （VMP 代码块 {@code 0x1f12ea}，33 个码元，exact_fit=true）。反汇编显示它做的事是：
 * <pre>
 *   JSONObject.quote(输入)                       // 转义为 JS 字面量
 *   new StringBuilder()
 *     .append("\"\"")                            // T2[5]
 *     .append(SCRIPT_BOOTSTRAP_PREFIX)           // T2[91]
 *     .append(").then(...)...")                  // T2[40]
 *     .toString()
 * </pre>
 * 也就是说：脚本是 {@code <前缀> + JSON 字符串字面量 + <后缀>} 的拼接，
 * 前缀以 {@code DouyinParser.parse(} 结尾，后缀以 {@code ).then(report)...} 开头。
 *
 * <p>证据链：{@code 09_reports/18_VMP字节码反汇编.md} §18.8.3。
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
     * 生成"探测页面解析脚本是否就绪"的脚本（证据：常量池 T2[1] 的就绪探针）。
     */
    public static String buildReadyProbe() {
        return DouyinRules.READY_PROBE;
    }

    /**
     * 把任意文本转义成合法的 JSON 字符串字面量（含两端引号）。
     *
     * <p>对应样本里调用的 {@code org/json/JSONObject.quote(String)}：
     * 反汇编显示 {@code buildParseScript} 的第一个 invoke 就是它
     * （{@code org/json/JSONObject.quote}）。这里给出等价的最小实现，
     * 以便在非 Android 环境（如单元测试）也能复用。
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
