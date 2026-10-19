package com.yohu.lib_urlparse.provider.toutiao;

import com.yohu.lib_urlparse.api.FailureReason;
import com.yohu.lib_urlparse.api.MediaAsset;
import com.yohu.lib_urlparse.api.ParseFailure;
import com.yohu.lib_urlparse.api.Platform;
import com.yohu.lib_urlparse.api.UrlParseRequest;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.lib_urlparse.rules.ToutiaoWebRules;
import com.yohu.lib_urlparse.spi.HttpResponse;
import com.yohu.lib_urlparse.spi.ParseContext;
import com.yohu.lib_urlparse.spi.ParseProvider;
import com.yohu.lib_urlparse.spi.ProviderPriority;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 今日头条 / 西瓜视频：走**移动页的服务端渲染数据 + 页面自带的已签名 VOD 请求**。
 *
 * <p>不需要任何凭据，也不需要复现签名算法——页面自己替我们签好了。这一点很关键：
 * 早期我误判"头条整站不可用"，其实只是桌面页有 JS-VM 壳；换成
 * {@code m.toutiao.com} 的移动页就拿到了完整数据。
 *
 * <p>流程：
 * <ol>
 *   <li>{@code GET https://m.toutiao.com/i<id>/}（移动 UA，绕开桌面页的 JS 壳）</li>
 *   <li>取 {@code RENDER_DATA.articleInfo}：标题、时长、封面、{@code playAuthTokenV2}</li>
 *   <li>解 token 得 {@code GetPlayInfoToken}，请求 {@code https://vod.bytedanceapi.com/?<token>}</li>
 *   <li>取 {@code Result.Data.PlayInfoList[].MainPlayUrl} 作为可下载地址</li>
 * </ol>
 *
 * <p>纯 JVM 可测：只依赖 {@link com.yohu.lib_urlparse.spi.HttpClient}，不碰 WebView。
 */
public final class ToutiaoWebProvider implements ParseProvider {

    private static final String TAG = "ToutiaoWebProvider";

    private static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/130.0.0.0 Mobile Safari/537.36";

    @Override
    public String channel() {
        return "toutiao.webpage";
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
        if (request.platformHint() == Platform.TOUTIAO || request.platformHint() == Platform.XIGUA) {
            return true;
        }
        String url = context != null && context.resolvedUrl() != null
                ? context.resolvedUrl() : request.rawInput();
        return ToutiaoWebRules.isToutiaoUrl(url) && ToutiaoWebRules.extractItemId(url) != null;
    }

    @Override
    public UrlParseResult parse(ParseContext context, UrlParseRequest request) throws ParseFailure {
        String input = context.resolvedUrl() != null ? context.resolvedUrl() : request.rawInput();
        String itemId = ToutiaoWebRules.extractItemId(input);
        if (itemId == null) {
            throw new ParseFailure(FailureReason.UNRECOGNISED_INPUT, channel(),
                    "无法从链接取出作品 ID: " + input);
        }
        String pageUrl = ToutiaoWebRules.mobilePageUrl(itemId);
        context.logger().info(TAG, "itemId=" + itemId + " 移动页=" + pageUrl);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", MOBILE_UA);
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Referer", "https://m.toutiao.com/");

        // 1) 取移动页（服务端渲染 RENDER_DATA）
        HttpResponse pageResp;
        try {
            pageResp = context.http().get(pageUrl, headers);
        } catch (Exception e) {
            throw new ParseFailure(FailureReason.NETWORK, channel(), "移动页请求失败: " + e, e);
        }
        String html = pageResp.body() == null ? "" : pageResp.body();
        context.logger().info(TAG, "GET " + pageUrl + " -> HTTP " + pageResp.status()
                + " 长度=" + html.length());
        if (html.isEmpty()) {
            throw new ParseFailure(FailureReason.UNEXPECTED_RESPONSE, channel(),
                    "移动页返回空（HTTP " + pageResp.status() + "）");
        }

        JSONObject renderData = ToutiaoWebRules.extractRenderData(html);
        if (renderData == null) {
            throw new ParseFailure(FailureReason.UNEXPECTED_RESPONSE, channel(),
                    "移动页里没有 RENDER_DATA（可能是图文而非视频，或页面结构变化）");
        }
        JSONObject info = ToutiaoWebRules.articleInfo(renderData);
        if (info == null) {
            throw new ParseFailure(FailureReason.UNEXPECTED_RESPONSE, channel(),
                    "RENDER_DATA 里没有 articleInfo");
        }

        String title = info.optString("title", null);
        String videoId = info.optString("videoId", null);
        long durationMs = ToutiaoWebRules.durationMs(info);
        context.logger().info(TAG, "articleInfo: title=" + title + " videoId=" + videoId
                + " 时长ms=" + durationMs);

        // 平台按来源域名区分：ixigua.com 归西瓜视频，其余归今日头条
        Platform platform = isXigua(input) ? Platform.XIGUA : Platform.TOUTIAO;

        UrlParseResult.Builder out = UrlParseResult.builder(platform)
                .channel(channel())
                .sourceUrl(pageUrl)
                .workId(itemId)
                .title(title == null ? null : title.trim())
                .extra("toutiao.videoId", videoId == null ? "" : videoId);

        String source = info.optString("source", null);
        if (source != null && !source.isEmpty()) {
            out.author(com.yohu.lib_urlparse.api.Author.builder().nickname(source).build());
        }

        // 2) 解 playAuthTokenV2 → 已签名的 GetPlayInfo 请求
        String tokenB64 = info.optString("playAuthTokenV2", null);
        String playInfoToken = ToutiaoWebRules.decodePlayAuthToken(tokenB64);
        if (playInfoToken == null) {
            throw new ParseFailure(FailureReason.NO_MEDIA_FOUND, channel(),
                    "没有 playAuthTokenV2（该作品可能不是视频，或是直播/图文）");
        }
        String vodUrl = ToutiaoWebRules.vodRequestUrl(playInfoToken);
        JSONObject vod;
        try {
            HttpResponse vr = context.http().get(vodUrl, headers);
            String body = vr.body() == null ? "" : vr.body();
            context.logger().info(TAG, "GET vod -> HTTP " + vr.status() + " 长度=" + body.length());
            vod = new JSONObject(body);
        } catch (Exception e) {
            throw new ParseFailure(FailureReason.NETWORK, channel(), "VOD 取流失败: " + e, e);
        }

        List<ToutiaoWebRules.PlayUrl> plays = ToutiaoWebRules.playUrlsFromVod(vod);
        if (plays.isEmpty()) {
            throw new ParseFailure(FailureReason.NO_MEDIA_FOUND, channel(),
                    "VOD 响应里没有 PlayInfoList/MainPlayUrl");
        }
        for (ToutiaoWebRules.PlayUrl p : plays) {
            MediaAsset.Builder b = MediaAsset.video(p.url)
                    .mimeType("video/mp4")
                    .durationMs(durationMs)
                    .headers(headers);
            if (p.width > 0 && p.height > 0) {
                b.dimension(p.width, p.height);
            }
            out.asset(b.build());
        }
        String cover = ToutiaoWebRules.coverUrlFromVod(vod);
        if (cover == null || cover.isEmpty()) {
            cover = info.optString("posterUrl", null);
        }
        if (cover != null && cover.startsWith("http")) {
            out.asset(MediaAsset.cover(cover).headers(headers).build());
        }

        UrlParseResult built = out.build();
        context.logger().info(TAG, "媒体=" + built.assets().size() + " 首个="
                + (built.assets().isEmpty() ? "-" : head(built.assets().get(0).url())));
        return built;
    }

    private static boolean isXigua(String url) {
        String host = com.yohu.lib_urlparse.util.UrlUtils.host(url);
        return host != null && host.contains("ixigua.com");
    }

    private static String head(String url) {
        if (url == null) {
            return "-";
        }
        return url.length() > 90 ? url.substring(0, 90) + "…" : url;
    }
}
