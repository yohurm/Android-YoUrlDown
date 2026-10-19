package com.yohu.lib_urlparse.rules;

import java.util.regex.Matcher;

/**
 * 作品 ID / 链接抽取。
 *
 * <p><b>出处</b>：{@code DouyinWebParser}
 * <ul>
 *   <li>{@code extractAwemeId(String):String}（VMP 块 {@code 0x1f132c}，93 码元）：
 *       先用 {@code /(?:video|note|share/video|share/slides)/(\d+)/} 取第 1 组，
 *       失败再回退 {@code [?&]aweme_id=(\d+)}；</li>
 *   <li>{@code extractUrl(String):String}（块 {@code 0x1f13e6}，78 码元）：
 *       trim → 判 {@code ^\d{15,25}$} → 否则取首个链接并 trimEnd。</li>
 * </ul>
 * 证据链：{@code 09_reports/18_VMP字节码反汇编.md} §18.8.3。
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

    /**
     * {@code resolveAwemeId} 的严格抽取（证据：块 0x1f18e2）。
     *
     * <p>与 {@link #extractAwemeId(String)} 的区别：本规则要求 ID 为 **15–25 位**
     * （{@code /(?:video|note|share/video|share/slides)/(\d{15,25})/}），
     * 而后者只要求任意长度数字。样本在短链展开之后走的是这条更严格的规则。
     *
     * @return 作品 ID；未命中返回 null
     */
    public static String extractAwemeIdStrict(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher m = DouyinRules.PATTERN_AWEME_ID_PATH_STRICT.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 抽取"待处理的链接或 ID"。
     *
     * <p>行为对齐 {@code extractUrl}：输入若本身是 15–25 位纯数字，则原样返回；
     * 否则取首个 http(s) 链接（已剔除尾随标点）。注意样本里这条判定用于
     * 区分"用户直接给了 ID"与"给了链接"两种入口。
     */
    public static String extractTarget(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (DouyinRules.PATTERN_BARE_ID.matcher(trimmed).matches()) {
            return trimmed;
        }
        return com.yohu.lib_urlparse.util.UrlUtils.extractFirstUrl(trimmed);
    }
}
