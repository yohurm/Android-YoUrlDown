package com.yohu.lib_urlparse.rules;

import java.util.regex.Matcher;

/**
 * 抖音作品 ID 抽取：先按路径规则（{@code /video/<id>} 等）取，取不到再看查询参数 {@code aweme_id}。
 */
public final class AwemeIdExtractor {

    private AwemeIdExtractor() {
    }

    /**
     * 从链接或分享文本中抽取作品 ID。
     *
     * @return 作品 ID；两条规则都未命中时返回 null
     */
    public static String extractAwemeId(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher m = DouyinRules.PATTERN_AWEME_ID_PATH.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        m = DouyinRules.PATTERN_AWEME_ID_QUERY.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
}
