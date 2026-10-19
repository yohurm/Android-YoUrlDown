package com.yohu.lib_urlparse.provider.douyin;

import com.yohu.lib_urlparse.api.Author;
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
import com.yohu.lib_urlparse.spi.ParseContext;
import com.yohu.lib_urlparse.spi.ParseProvider;
import com.yohu.lib_urlparse.spi.ProviderPriority;
import com.yohu.lib_urlparse.util.UrlUtils;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 抖音：移动分享页（{@code iesdouyin.com/share/...}）+ 服务端渲染的 {@code _ROUTER_DATA}。
 *
 * <p>为什么先走分享页而不是主站：主站页面是客户端渲染的，HTML 里没有作品数据；分享页是
 * **服务端渲染**的，{@code _ROUTER_DATA} 里直接带 {@code item_list[0]}。这也是旧工程
 * 实测出来的路径。
 *
 * <p>去水印：{@code play_addr} 给的是带水印地址（路径含 {@code playwm}），把它换成
 * {@code play} 通常就能拿到无水印版本；{@link DouyinWebRules#toNoWatermark} 负责这个替换，
 * 但它**只在确定是水印形态时才改**，避免把正常地址改坏。
 *
 * <p>纯 JVM 可测：只依赖 {@link com.yohu.lib_urlparse.spi.HttpClient}。
 */
public final class DouyinWebProvider implements ParseProvider {

    private static final String TAG = "DouyinWebProvider";

    private static final String MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

    /** 分享页前缀：服务端渲染，无需 JS。 */
    private static final String SHARE_PAGE = "https://www.iesdouyin.com/share/video/";

    @Override
    public String channel() {
        return "douyin.webpage";
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
        String input = context.resolvedUrl() != null && !context.resolvedUrl().isEmpty()
                ? context.resolvedUrl() : request.rawInput();

        // 1) 短链展开：v.douyin.com 的短链里没有 awemeId
        String pageUrl = input;
        if (DouyinRules.containsShortLinkMarker(input)) {
            String expanded = expand(input, context);
            if (expanded != null) {
                pageUrl = expanded;
            }
        }

        String awemeId = AwemeIdExtractor.extractAwemeId(pageUrl);
        if (awemeId == null) {
            awemeId = AwemeIdExtractor.extractAwemeId(input);
        }
        if (awemeId == null) {
            throw new ParseFailure(FailureReason.UNRECOGNISED_INPUT, channel(),
                    "链接里没有作品 ID（短链展开后仍无）: " + pageUrl);
        }
        context.logger().info(TAG, "awemeId=" + awemeId);

        // 2) 取分享页
        String shareUrl = SHARE_PAGE + awemeId + "/";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", MOBILE_UA);
        headers.put("Referer", "https://www.douyin.com/");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");

        String html;
        try {
            HttpResponse resp = context.http().get(shareUrl, headers);
            html = resp.body() == null ? "" : resp.body();
            context.logger().info(TAG, "GET " + shareUrl + " -> HTTP " + resp.status()
                    + " 长度=" + html.length());
        } catch (Exception e) {
            throw new ParseFailure(FailureReason.NETWORK, channel(), "分享页请求失败: " + e, e);
        }
        if (html.isEmpty()) {
            throw new ParseFailure(FailureReason.UNEXPECTED_RESPONSE, channel(),
                    "分享页返回空");
        }

        // 3) 抽 _ROUTER_DATA
        JSONObject routerData = null;
        for (String var : DouyinWebRules.DATA_VARS) {
            String json = extractJsonObject(html, var);
            if (json == null) {
                continue;
            }
            try {
                routerData = new JSONObject(json);
                context.logger().info(TAG, "变量 " + var + " 解析成功，长度=" + json.length());
                break;
            } catch (org.json.JSONException e) {
                context.logger().warn(TAG, "变量 " + var + " 不是合法 JSON: " + e.getMessage());
            }
        }

        UrlParseResult.Builder out = UrlParseResult.builder(Platform.DOUYIN)
                .channel(channel())
                .sourceUrl(shareUrl)
                .workId(awemeId);

        int media = 0;
        boolean structured = routerData != null;
        if (structured) {
            JSONObject item = DouyinWebRules.findItemFromRouterData(routerData);
            if (item != null) {
                media += fillFromItem(out, item, awemeId);
            }
        }

        // 4) 兜底：结构化数据不可用时，从 HTML 里正则捞 mp4 地址（旧工程实测能救回一部分）
        if (media == 0) {
            List<String> mp4 = DouyinWebRules.regexMp4Urls(html, 6);
            for (String u : mp4) {
                out.asset(MediaAsset.video(DouyinWebRules.toNoWatermark(u))
                        .mimeType("video/mp4")
                        .headers(headers)
                        .build());
                media++;
            }
            if (media > 0) {
                context.logger().warn(TAG, "结构化数据不可用，正则兜底取到 " + media + " 条地址");
            }
        }

        out.extra("douyin.layers",
                DouyinWebRules.layerSummary(html, structured, media > 0, media));

        UrlParseResult result = out.build();
        if (result.assets().isEmpty()) {
            String idInHtml = DouyinWebRules.extractAwemeIdFromHtml(html);
            throw new ParseFailure(FailureReason.NO_MEDIA_FOUND, channel(),
                    "分享页里没有解析出媒体（诊断: " + result.extras().get("douyin.layers")
                            + "；页面内作品 ID=" + idInHtml + "）");
        }
        return result;
    }

    /** 把 {@code item_list[0]} 映射成结果。返回新增媒体数。 */
    private static int fillFromItem(UrlParseResult.Builder out, JSONObject item, String awemeId) {
        String title = DouyinWebRules.title(item);
        if (title != null) {
            out.title(title);
        }
        String nickname = DouyinWebRules.authorName(item);
        String uid = DouyinWebRules.authorId(item);
        String avatar = DouyinWebRules.authorAvatar(item);
        if (nickname != null || uid != null) {
            out.author(Author.builder().nickname(nickname).uid(uid).avatarUrl(avatar).build());
        }
        String realId = DouyinWebRules.itemId(item);
        if (realId != null && !realId.isEmpty()) {
            out.workId(realId);
        }
        long durationMs = DouyinWebRules.durationMs(item);
        if (durationMs > 0) {
            out.extra("douyin.durationMs", String.valueOf(durationMs));
        }

        int added = 0;
        for (DouyinWebRules.VideoUrl v : DouyinWebRules.videoUrls(item)) {
            MediaAsset.Builder b = MediaAsset.video(DouyinWebRules.toNoWatermark(v.url))
                    .mimeType("video/mp4")
                    .durationMs(durationMs);
            if (v.width > 0 && v.height > 0) {
                b.dimension(v.width, v.height);
            }
            if (v.bitrate > 0) {
                b.sizeBytes(-1L); // 显式表示"未知大小"，码率放 extras
                out.extra("douyin.bitrate", String.valueOf(v.bitrate));
            }
            out.asset(b.build());
            added++;
        }
        for (String img : DouyinWebRules.imageUrls(item)) {
            out.asset(MediaAsset.image(DouyinWebRules.toNoWatermarkImage(img)).build());
            added++;
        }
        String cover = DouyinWebRules.coverUrl(item);
        if (cover != null) {
            out.asset(MediaAsset.cover(DouyinWebRules.toNoWatermarkImage(cover)).build());
        }
        String music = DouyinWebRules.musicUrl(item);
        if (music != null) {
            out.asset(MediaAsset.audio(music).build());
        }
        return added;
    }

    private String expand(String url, ParseContext context) {
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

    /**
     * 从 HTML 里抽出 {@code varName=...} 后面那段 JSON 对象（按花括号配对，忽略字符串里的括号）。
     *
     * <p>不用 {@code JSONObject} 直接扫全页：页面里还有别的脚本，先定位变量名再配对更稳。
     */
    static String extractJsonObject(String html, String varName) {
        if (html == null || varName == null || html.isEmpty()) {
            return null;
        }
        int idx = html.indexOf(varName);
        while (idx >= 0) {
            int brace = html.indexOf('{', idx);
            if (brace < 0) {
                return null;
            }
            // 变量名与 '{' 之间不能隔太远（否则说明命中的是别处的文本）
            if (brace - idx > 120) {
                idx = html.indexOf(varName, idx + varName.length());
                continue;
            }
            String json = matchedObject(html, brace);
            if (json != null && json.length() > 2) {
                return json;
            }
            idx = html.indexOf(varName, idx + varName.length());
        }
        return null;
    }

    /** 从 {@code start}（必须是 '{'）开始，返回配对的整段对象文本。 */
    private static String matchedObject(String s, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}
