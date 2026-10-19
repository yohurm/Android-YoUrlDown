package com.yohu.lib_urlparse.provider.douyin;

import com.yohu.lib_urlparse.api.FailureReason;
import com.yohu.lib_urlparse.api.MediaAsset;
import com.yohu.lib_urlparse.api.ParseFailure;
import com.yohu.lib_urlparse.api.Platform;
import com.yohu.lib_urlparse.api.UrlParseRequest;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.lib_urlparse.rules.AwemeIdExtractor;
import com.yohu.lib_urlparse.rules.DouyinRules;
import com.yohu.lib_urlparse.rules.DouyinWebRules;
import com.yohu.lib_urlparse.spi.HttpResponse;
import com.yohu.lib_urlparse.spi.JsEvaluator;
import com.yohu.lib_urlparse.spi.ParseContext;
import com.yohu.lib_urlparse.spi.ParseProvider;
import com.yohu.lib_urlparse.spi.ProviderPriority;
import com.yohu.lib_urlparse.util.UrlUtils;

import org.json.JSONObject;

/**
 * 抖音 webview 通道：在页面里**注入解析脚本**并取回结果。
 *
 * <p>这条通道是 12 个通道里唯一真正依赖 JS 求值的，因此它同时是
 * {@link JsEvaluator} 契约的第一个真实使用方：核心只认接口，真实实现（WebView）
 * 由 `app` 注入；单测里用假实现即可验证编排逻辑。
 *
 * <p>两条硬约束（都来自真实失败）：
 * <ul>
 *   <li><b>没有 evaluator 时抛 {@code CHANNEL_DISABLED}</b>，而不是返回空结果——
 *       返回空结果会让调用链误以为"这条路走通了"，从而不再尝试网页直解与通用嗅探。</li>
 *   <li><b>脚本回传为空时抛 {@code NO_MEDIA_FOUND}</b>，同理：必须让链条继续往下走。</li>
 * </ul>
 *
 * <p>优先级与 {@code douyin.webpage} 同为 {@code DEDICATED_WEB}：网页直解更快、更稳，
 * 应在它之前尝试（同优先级按注册顺序，注册时先 web 后 webview）。
 */
public final class DouyinWebviewProvider implements ParseProvider {

    private static final String TAG = "DouyinWebviewProvider";

    @Override
    public String channel() {
        return "douyin.webview.js";
    }

    @Override
    public ProviderPriority priority() {
        return ProviderPriority.DEDICATED_WEB;
    }

    @Override
    public boolean canHandle(ParseContext context, UrlParseRequest request) {
        if (request == null) {
            return false;
        }
        if (request.platformHint() == Platform.DOUYIN) {
            return true;
        }
        String url = context != null && context.resolvedUrl() != null
                ? context.resolvedUrl() : request.rawInput();
        if (AwemeIdExtractor.extractAwemeId(url) != null) {
            return true;
        }
        return DouyinRules.isDouyinHost(UrlUtils.host(url))
                || DouyinRules.containsShortLinkMarker(url);
    }

    @Override
    public UrlParseResult parse(ParseContext context, UrlParseRequest request) throws ParseFailure {
        String target = context.resolvedUrl() != null && !context.resolvedUrl().isEmpty()
                ? context.resolvedUrl() : request.rawInput();

        // 1) 短链展开：跟随重定向拿真实作品页
        String pageUrl = target;
        if (DouyinRules.containsShortLinkMarker(target)) {
            String expanded = expandShortLink(target, context);
            if (expanded != null) {
                pageUrl = expanded;
            }
        }
        String awemeId = AwemeIdExtractor.extractAwemeId(pageUrl);
        if (awemeId == null) {
            awemeId = AwemeIdExtractor.extractAwemeId(target);
        }

        // 2) 求值器：没有就明确报告"通道不可用"，而不是假装成功
        JsEvaluator evaluator = context.service(JsEvaluator.class);
        if (evaluator == null) {
            throw new ParseFailure(FailureReason.CHANNEL_DISABLED, channel(),
                    "该通道需要 JsEvaluator（WebView 实现），当前未注入。"
                            + "若不需要 JS，请让 douyin.webpage 或通用嗅探接手。");
        }

        String script = DouyinScriptBuilder.build(DouyinScriptBuilder.quote(pageUrl));
        String raw;
        try {
            raw = evaluator.loadAndEvaluate(pageUrl, script, DouyinRules.JS_RESULT_TIMEOUT_MS);
        } catch (Exception e) {
            throw new ParseFailure(FailureReason.NETWORK, channel(),
                    "页面脚本求值失败: " + e, e);
        } finally {
            // 求值器是有状态的（WebView 实例），用完必须释放，否则会泄漏页面。
            try {
                evaluator.release();
            } catch (Exception ignored) {
                // 释放失败不影响本次结果
            }
        }

        UrlParseResult.Builder out = UrlParseResult.builder(Platform.DOUYIN)
                .channel(channel())
                .sourceUrl(pageUrl)
                .workId(awemeId)
                .extra("js.raw.length", raw == null ? "0" : String.valueOf(raw.length()));

        if (raw == null || raw.trim().isEmpty()) {
            throw new ParseFailure(FailureReason.NO_MEDIA_FOUND, channel(),
                    "解析脚本没有回传任何内容（可能未注入，或页面未就绪）");
        }

        JSONObject root = parseJsonLoosely(raw);
        if (root == null) {
            throw new ParseFailure(FailureReason.UNEXPECTED_RESPONSE, channel(),
                    "脚本回传的内容不是 JSON（长度=" + raw.length() + "）");
        }

        // 3) 回传结构可能是 _ROUTER_DATA 形态，也可能是直接的作品对象
        JSONObject item = DouyinWebRules.findItemFromRouterData(root);
        if (item == null) {
            item = root.optJSONObject("data");
            if (item != null) {
                JSONObject inner = DouyinWebRules.findItemFromRouterData(item);
                item = inner != null ? inner : item;
            } else {
                item = root.optJSONObject("aweme_detail");
            }
        }

        int media = 0;
        if (item != null) {
            media = DouyinWebProvider.fillFromItem(out, item, awemeId);
        }
        String message = root.optString("message", null);
        if (message != null && !message.isEmpty()) {
            out.extra("js.message", message);
        }

        UrlParseResult result = out.build();
        if (result.assets(MediaAsset.Kind.VIDEO).isEmpty()
                && result.assets(MediaAsset.Kind.IMAGE).isEmpty()) {
            throw new ParseFailure(FailureReason.NO_MEDIA_FOUND, channel(),
                    "脚本回传了内容但没有媒体（js.message=" + message + "）");
        }
        context.logger().info(TAG, "webview 通道拿到 " + media + " 条媒体");
        return result;
    }

    /** 回传可能被包在非 JSON 文本里：截取首个 '{' 到末个 '}' 再试。 */
    private static JSONObject parseJsonLoosely(String raw) {
        String s = raw.trim();
        try {
            return new JSONObject(s);
        } catch (org.json.JSONException ignored) {
            int lb = s.indexOf('{');
            int rb = s.lastIndexOf('}');
            if (lb >= 0 && rb > lb) {
                try {
                    return new JSONObject(s.substring(lb, rb + 1));
                } catch (org.json.JSONException e) {
                    return null;
                }
            }
            return null;
        }
    }

    private String expandShortLink(String url, ParseContext context) {
        try {
            HttpResponse resp = context.http().get(url, null);
            String finalUrl = resp.finalUrl();
            context.logger().info(TAG, "短链 " + url + " -> " + finalUrl);
            return finalUrl;
        } catch (Exception e) {
            context.logger().warn(TAG, "短链展开失败: " + e);
            return null;
        }
    }
}
