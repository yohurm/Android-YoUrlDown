package com.yohu.lib_urlparse.provider.wechat;

import com.yohu.lib_urlparse.api.Author;
import com.yohu.lib_urlparse.api.FailureReason;
import com.yohu.lib_urlparse.api.MediaAsset;
import com.yohu.lib_urlparse.api.ParseFailure;
import com.yohu.lib_urlparse.api.Platform;
import com.yohu.lib_urlparse.api.UrlParseRequest;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.lib_urlparse.spi.HttpResponse;
import com.yohu.lib_urlparse.spi.ParseContext;
import com.yohu.lib_urlparse.spi.ParseProvider;
import com.yohu.lib_urlparse.spi.ProviderPriority;
import com.yohu.lib_urlparse.util.UrlUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信公众号文章（{@code mp.weixin.qq.com/s/...}）：**文章页直解**，不需要登录态。
 *
 * <p>公众号文章的媒体地址直接写在 HTML 与服务端内联数据里：
 * <ul>
 *   <li>视频：{@code mpvideo.qpic.cn} 的 mp4（常在 {@code var msg} 内联对象或 {@code <mpvideo>} 里）；</li>
 *   <li>图片：{@code mmbiz.qpic.cn} 的 jpg/png（正文 {@code <img data-src>}）；</li>
 *   <li>标题/作者：{@code og:title} / {@code <meta property="og:title">} 与 {@code var nickname}。</li>
 * </ul>
 *
 * <p>因此这条通道是纯 HTTP、纯 JVM 可测的——旧工程里公众号是靠通用嗅探通的，
 * 直解更快也更稳（不需要 WebView）。
 *
 * <p>如实说明：本实现按已知页面形态分层抽取（内联对象 → 标签属性 → 正则兜底），
 * 缺一份当前真实文章固件；真机验证需一条有效的公众号文章链接。
 */
public final class WechatMpProvider implements ParseProvider {

    private static final String TAG = "WechatMpProvider";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/139.0.0.0 Mobile Safari/537.36";

    /** 公众号视频常见域名。 */
    private static final Pattern VIDEO_URL = Pattern.compile(
            "https?://[^\\s\"'<>]*mpvideo\\.qpic\\.cn[^\\s\"'<>]*",
            Pattern.CASE_INSENSITIVE);
    /** 公众号图片常见域名。 */
    private static final Pattern IMAGE_URL = Pattern.compile(
            "https?://[^\\s\"'<>]*mmbiz\\.qpic\\.cn[^\\s\"'<>]*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_TITLE = Pattern.compile(
            "<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MSG_TITLE = Pattern.compile(
            "var\\s+msg_title\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern NICKNAME = Pattern.compile(
            "var\\s+nickname\\s*=\\s*[\"']([^\"']+)[\"']|nick_name[^A-Za-z0-9_]{1,6}[\"']([^\"']{1,60})[\"']");

    @Override
    public String channel() {
        return "wechat.mp";
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
        if (request.platformHint() == Platform.WECHAT_MP) {
            return true;
        }
        String url = context != null && context.resolvedUrl() != null
                ? context.resolvedUrl() : request.rawInput();
        String host = UrlUtils.host(url);
        // 只认文章页；channels.weixin.qq.com（视频号）由另一条通道处理
        return host != null && (host.equals("mp.weixin.qq.com")
                || host.endsWith(".mp.weixin.qq.com"));
    }

    @Override
    public UrlParseResult parse(ParseContext context, UrlParseRequest request) throws ParseFailure {
        String url = context.resolvedUrl() != null && !context.resolvedUrl().isEmpty()
                ? context.resolvedUrl() : request.rawInput();
        String pageUrl = UrlUtils.extractFirstUrl(url);
        if (pageUrl == null) {
            throw new ParseFailure(FailureReason.UNRECOGNISED_INPUT, channel(),
                    "输入里没有文章链接");
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Accept", "text/html,application/xhtml+xml");

        String html;
        try {
            HttpResponse resp = context.http().get(pageUrl, headers);
            html = resp.body() == null ? "" : resp.body();
            context.logger().info(TAG, "GET " + pageUrl + " -> HTTP " + resp.status()
                    + " 长度=" + html.length());
        } catch (Exception e) {
            throw new ParseFailure(FailureReason.NETWORK, channel(), "文章页请求失败: " + e, e);
        }
        if (html.isEmpty()) {
            throw new ParseFailure(FailureReason.UNEXPECTED_RESPONSE, channel(), "文章页返回空");
        }

        UrlParseResult.Builder out = UrlParseResult.builder(Platform.WECHAT_MP)
                .channel(channel())
                .sourceUrl(pageUrl);

        String title = firstGroup(OG_TITLE, html);
        if (title == null || title.isEmpty()) {
            title = firstGroup(MSG_TITLE, html);
        }
        if (title != null) {
            out.title(unescapeHtml(title).trim());
        }
        Matcher nick = NICKNAME.matcher(html);
        if (nick.find()) {
            String name = nick.group(1) != null ? nick.group(1) : nick.group(2);
            if (name != null && !name.trim().isEmpty()) {
                out.author(Author.builder().nickname(unescapeHtml(name).trim()).build());
            }
        }

        Set<String> videos = new LinkedHashSet<>();
        Matcher vm = VIDEO_URL.matcher(html);
        while (vm.find() && videos.size() < 6) {
            videos.add(unescapeHtml(unescapeSlashes(vm.group())));
        }
        Set<String> images = new LinkedHashSet<>();
        Matcher im = IMAGE_URL.matcher(html);
        while (im.find() && images.size() < 30) {
            String u = unescapeHtml(unescapeSlashes(im.group()));
            // 微信图片常带裁剪参数；去掉只影响显示的 wx_fmt 之外的部分会更稳，这里保留原样
            images.add(u);
        }

        for (String v : videos) {
            out.asset(MediaAsset.video(v).mimeType("video/mp4").headers(headers).build());
        }
        for (String img : images) {
            out.asset(MediaAsset.image(img).headers(headers).build());
        }
        out.extra("wechat.videos", String.valueOf(videos.size()));
        out.extra("wechat.images", String.valueOf(images.size()));

        UrlParseResult result = out.build();
        if (result.assets().isEmpty()) {
            // 微信公众号文章可能确实只是图文消息（无视频），此时图片即内容
            throw new ParseFailure(FailureReason.NO_MEDIA_FOUND, channel(),
                    "文章页里没有解析出媒体（可能是不含音视频的纯文字消息，或页面需要验证）");
        }
        context.logger().info(TAG, "视频 " + videos.size() + " 条 / 图片 " + images.size() + " 条");
        return result;
    }

    private static String firstGroup(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String unescapeHtml(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
    }

    private static String unescapeSlashes(String s) {
        return s.replace("\\/", "/").replace("\\u002F", "/").replace("\\u0026", "&");
    }
}
