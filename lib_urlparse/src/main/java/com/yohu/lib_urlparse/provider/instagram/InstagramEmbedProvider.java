package com.yohu.lib_urlparse.provider.instagram;

import com.yohu.lib_urlparse.api.Author;
import com.yohu.lib_urlparse.api.FailureReason;
import com.yohu.lib_urlparse.api.MediaAsset;
import com.yohu.lib_urlparse.api.ParseFailure;
import com.yohu.lib_urlparse.api.Platform;
import com.yohu.lib_urlparse.api.UrlParseRequest;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.lib_urlparse.rules.InstagramRules;
import com.yohu.lib_urlparse.spi.HttpResponse;
import com.yohu.lib_urlparse.spi.ParseContext;
import com.yohu.lib_urlparse.spi.ParseProvider;
import com.yohu.lib_urlparse.spi.ProviderPriority;
import com.yohu.lib_urlparse.util.UrlUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Instagram：走**公开嵌入页**（{@code /p/<shortcode>/embed/captioned/}）。
 *
 * <p>为什么不走主站：主站需要登录态与 GraphQL 签名；嵌入页是给"站外引用"用的公开页面，
 * 服务端直接渲染视频/图片地址，无需账号。
 *
 * <p>如实说明：旧报告里 Instagram 记为**未通过，原因是主机不可达**（网络层），
 * 不是解析逻辑失败。本通道实现完整；真机复验时若仍不可达，会照实标注网络原因，
 * 不会写成"解析失败"。
 *
 * <p>纯 JVM 可测：只依赖 {@link com.yohu.lib_urlparse.spi.HttpClient}。
 */
public final class InstagramEmbedProvider implements ParseProvider {

    private static final String TAG = "InstagramEmbedProvider";

    @Override
    public String channel() {
        return "instagram.embed";
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
        if (request.platformHint() == Platform.INSTAGRAM) {
            return true;
        }
        String url = context != null && context.resolvedUrl() != null
                ? context.resolvedUrl() : request.rawInput();
        return InstagramRules.isInstagramHost(UrlUtils.host(url))
                || InstagramRules.extractShortcode(url) != null;
    }

    @Override
    public UrlParseResult parse(ParseContext context, UrlParseRequest request) throws ParseFailure {
        String input = context.resolvedUrl() != null && !context.resolvedUrl().isEmpty()
                ? context.resolvedUrl() : request.rawInput();
        String shortcode = InstagramRules.extractShortcode(input);
        if (shortcode == null) {
            throw new ParseFailure(FailureReason.UNRECOGNISED_INPUT, channel(),
                    "链接里没有 shortcode: " + input);
        }

        String embedUrl = InstagramRules.embedUrl(shortcode);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", InstagramRules.USER_AGENT);
        headers.put("Accept-Language", "en-US,en;q=0.9");
        headers.put("Accept", "text/html,application/xhtml+xml");

        String html;
        try {
            HttpResponse resp = context.http().get(embedUrl, headers);
            html = resp.body() == null ? "" : resp.body();
            context.logger().info(TAG, "GET " + embedUrl + " -> HTTP " + resp.status()
                    + " 长度=" + html.length());
        } catch (Exception e) {
            // 网络不可达要如实说：这是网络层问题，不是解析逻辑问题
            throw new ParseFailure(FailureReason.NETWORK, channel(),
                    "Instagram 主机不可达或请求失败（旧报告里该平台也是网络层原因未通过）: "
                            + e, e);
        }
        if (html.isEmpty()) {
            throw new ParseFailure(FailureReason.UNEXPECTED_RESPONSE, channel(),
                    "嵌入页返回空（HTTP 无正文）");
        }

        InstagramRules.ParseOutcome parsed = InstagramRules.extract(html);

        UrlParseResult.Builder out = UrlParseResult.builder(Platform.INSTAGRAM)
                .channel(channel())
                .sourceUrl(embedUrl)
                .workId(shortcode)
                .title(parsed.title)
                .extra("instagram.shortcode", shortcode);
        if (parsed.ownerName != null) {
            out.author(Author.builder().nickname(parsed.ownerName).build());
        }
        out.extra("instagram.layers", parsed.layers);

        for (String v : parsed.videos) {
            out.asset(MediaAsset.video(v).mimeType("video/mp4").headers(headers).build());
        }
        for (String img : parsed.images) {
            out.asset(MediaAsset.image(img).headers(headers).build());
        }

        UrlParseResult result = out.build();
        if (result.assets().isEmpty()) {
            throw new ParseFailure(FailureReason.NO_MEDIA_FOUND, channel(),
                    "嵌入页里没有解析出媒体（层次诊断: " + parsed.layers + "）");
        }
        return result;
    }
}
