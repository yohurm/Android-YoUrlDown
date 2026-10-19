package com.yohu.lib_urlparse.provider.sniffer;

import com.yohu.lib_urlparse.api.FailureReason;
import com.yohu.lib_urlparse.api.MediaAsset;
import com.yohu.lib_urlparse.api.ParseFailure;
import com.yohu.lib_urlparse.api.Platform;
import com.yohu.lib_urlparse.api.UrlParseRequest;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.lib_urlparse.sniff.BlockPageDetector;
import com.yohu.lib_urlparse.sniff.MediaExtensions;
import com.yohu.lib_urlparse.spi.PageSniffer;
import com.yohu.lib_urlparse.spi.ParseContext;
import com.yohu.lib_urlparse.spi.ParseProvider;
import com.yohu.lib_urlparse.spi.ProviderPriority;
import com.yohu.lib_urlparse.spi.SniffOptions;
import com.yohu.lib_urlparse.spi.SniffResult;
import com.yohu.lib_urlparse.util.UrlUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用嗅探通道：加载页面、收集媒体请求，作为所有平台的兜底。
 *
 * <p>这条通道覆盖"没有专用解析器"的站点（旧报告里微信公众号就是这么通的）。核心只认
 * {@link PageSniffer} 接口，真实实现（WebView 拦请求）由 `app` 注入，因此编排逻辑可以在
 * 纯 JVM 上测。
 *
 * <p>两个必须保留的判据：
 * <ol>
 *   <li><b>拦截页/错误页不能当作品</b>：实测抖音拿到过 ByteDance 拦截页（标题「網頁無法使用」），
 *       通用嗅探仍从该页收了 7 条图片，矩阵被记成"解析通过(7 条)"——那 7 条是错误页的静态资源。
 *       <b>假通过比失败危害大</b>：失败会暴露，假通过会污染结论。</li>
 *   <li><b>图集类平台显式打开 gallery 模式</b>：抖音/小红书/公众号的作品主体可能就是图片，
 *       普通模式会把这些站点几乎筛空。</li>
 * </ol>
 */
public final class SnifferProvider implements ParseProvider {

    private static final String TAG = "SnifferProvider";

    @Override
    public String channel() {
        return "sniffer.webview";
    }

    @Override
    public ProviderPriority priority() {
        // 兜底：永远最后尝试。
        return ProviderPriority.SNIFFER;
    }

    @Override
    public boolean canHandle(ParseContext context, UrlParseRequest request) {
        if (request == null) {
            return false;
        }
        String url = context != null && context.resolvedUrl() != null
                ? context.resolvedUrl() : request.rawInput();
        return UrlUtils.extractFirstUrl(url) != null;
    }

    @Override
    public UrlParseResult parse(ParseContext context, UrlParseRequest request) throws ParseFailure {
        String pageUrl = context.resolvedUrl();
        if (pageUrl == null || pageUrl.trim().isEmpty()) {
            pageUrl = UrlUtils.extractFirstUrl(request.rawInput());
        }
        if (pageUrl == null || pageUrl.trim().isEmpty()) {
            throw new ParseFailure(FailureReason.UNRECOGNISED_INPUT, channel(),
                    "输入里没有 URL，嗅探需要可加载的页面");
        }

        PageSniffer sniffer = context.service(PageSniffer.class);
        if (sniffer == null) {
            throw new ParseFailure(FailureReason.CHANNEL_DISABLED, channel(),
                    "嗅探通道需要 PageSniffer（WebView 实现），当前未注入");
        }

        SniffResult sniffed;
        try {
            SniffOptions options = galleryProne(pageUrl)
                    ? SniffOptions.gallery() : SniffOptions.defaults();
            sniffed = sniffer.sniff(pageUrl, request.timeoutMs(), options);
        } catch (Exception e) {
            throw new ParseFailure(FailureReason.NETWORK, channel(), "页面嗅探失败: " + e, e);
        } finally {
            try {
                sniffer.release();
            } catch (Exception ignored) {
                // 释放失败不影响本次结果
            }
        }
        if (sniffed == null) {
            throw new ParseFailure(FailureReason.UNEXPECTED_RESPONSE, channel(),
                    "嗅探器返回 null");
        }

        String finalUrl = sniffed.finalUrl() != null ? sniffed.finalUrl() : pageUrl;
        Platform platform = inferPlatform(finalUrl);
        List<MediaAsset> items = toMediaItems(sniffed);

        // 拦截页/错误页不能当作品内容
        boolean hasVideoSignal = false;
        for (MediaAsset m : items) {
            if (m.kind() == MediaAsset.Kind.VIDEO) {
                hasVideoSignal = true;
                break;
            }
        }
        if (BlockPageDetector.isBlockPage(sniffed.title(), hasVideoSignal)) {
            throw new ParseFailure(FailureReason.UNEXPECTED_RESPONSE, channel(),
                    "页面是站点拦截/错误页（标题：" + sniffed.title() + "），不是作品内容；已丢弃嗅探到的 "
                            + items.size() + " 条（它们是该页的静态资源）。这通常意味着站点对本客户端做了"
                            + "风控判定，而不是页面改版。");
        }

        UrlParseResult.Builder out = UrlParseResult.builder(platform)
                .channel(channel())
                .sourceUrl(finalUrl)
                .title(sniffed.title())
                .extra("sniff.candidates", String.valueOf(sniffed.candidates().size()))
                .extra("sniff.requests", String.valueOf(sniffed.requestCount()))
                .extra("sniff.timedOut", String.valueOf(sniffed.timedOut()));
        for (MediaAsset m : items) {
            out.asset(m);
        }
        for (Map.Entry<String, String> e : sniffed.extras().entrySet()) {
            out.extra("sniff." + e.getKey(), e.getValue());
        }

        UrlParseResult result = out.build();
        // 假通过防线：只有图片、没有任何视频/音频，且该平台的作品主体通常不是图片时，
        // 这些小图多半是头像/图标/验证页装饰，而不是作品内容。
        // 实测：快手验证页被嗅探成"12 张图片"报成功，就是这一类。
        boolean onlyImages = result.assets(MediaAsset.Kind.VIDEO).isEmpty()
                && result.assets(MediaAsset.Kind.AUDIO).isEmpty()
                && !result.assets(MediaAsset.Kind.IMAGE).isEmpty();
        if (onlyImages && !galleryProne(finalUrl)) {
            throw new ParseFailure(FailureReason.NO_MEDIA_FOUND, channel(),
                    "只嗅到 " + result.assets(MediaAsset.Kind.IMAGE).size()
                            + " 张图片、没有视频/音频，且该平台作品主体不是图片："
                            + "疑似验证页/错误页上的头像与图标，不当作解析成功。");
        }
        if (result.assets().isEmpty()) {
            throw new ParseFailure(FailureReason.NO_MEDIA_FOUND, channel(),
                    "页面里没有可下载媒体（请求数=" + sniffed.requestCount()
                            + "，候选=" + sniffed.candidates().size()
                            + "，超时=" + sniffed.timedOut() + "）");
        }
        context.logger().info(TAG, "嗅探得到 " + result.assets().size() + " 条媒体，平台=" + platform);
        return result;
    }

    /**
     * 该页面所属平台是否"以图集为主"（需要打开图集模式）。
     *
     * <p>只列实测确认过"作品主体是图片"的站点；宁可少开也不要乱开——打开后结果里会混入
     * 图片候选，对以视频为主的平台是噪声。
     */
    public static boolean galleryProne(String url) {
        Platform p = inferPlatform(url);
        return p == Platform.DOUYIN || p == Platform.XIAOHONGSHU || p == Platform.WECHAT_MP;
    }

    /**
     * 候选 → 媒体项：去重 → 过滤 → 排序 → 归类。
     *
     * <p>排序意图（越大越前）：可直接播放的流（m3u8）&gt; 容器化视频（mp4/webm）&gt;
     * 有 Content-Type 证据的 &gt; 只有 URL 猜测的 &gt; 体积大的。
     * m3u8 放最前是因为它通常含完整视频，而同页 mp4 常是低清预览片段。
     */
    public static List<MediaAsset> toMediaItems(SniffResult sniffed) {
        List<MediaAsset> out = new ArrayList<>();
        if (sniffed == null || sniffed.candidates() == null) {
            return out;
        }
        Map<String, SniffResult.Candidate> best = new LinkedHashMap<>();
        for (SniffResult.Candidate c : sniffed.candidates()) {
            if (c == null || c.url() == null || !c.url().startsWith("http")) {
                continue;
            }
            SniffResult.Candidate prev = best.get(c.url());
            if (prev == null || score(c) > score(prev)) {
                best.put(c.url(), c);
            }
        }
        List<SniffResult.Candidate> ordered = new ArrayList<>(best.values());
        ordered.sort((a, b) -> Integer.compare(score(b), score(a)));
        for (SniffResult.Candidate c : ordered) {
            MediaAsset.Kind kind = kindOf(c);
            if (kind == null) {
                continue;
            }
            MediaAsset.Builder b;
            switch (kind) {
                case VIDEO:
                    b = MediaAsset.video(c.url());
                    break;
                case AUDIO:
                    b = MediaAsset.audio(c.url());
                    break;
                default:
                    b = MediaAsset.image(c.url());
                    break;
            }
            if (c.mimeType() != null && !c.mimeType().isEmpty()) {
                b.mimeType(c.mimeType());
            }
            if (c.contentLength() > 0) {
                b.sizeBytes(c.contentLength());
            }
            out.add(b.build());
        }
        return out;
    }

    /** 排序分：流 &gt; 容器 &gt; 有类型证据 &gt; 体积。 */
    static int score(SniffResult.Candidate c) {
        int s = 0;
        String url = c.url() == null ? "" : c.url();
        if (MediaExtensions.looksLikePlaylist(url)) {
            s += 1000;
        } else if (MediaExtensions.looksLikeVideo(url)) {
            s += 600;
        } else if (MediaExtensions.looksLikeAudio(url)) {
            s += 400;
        } else if (MediaExtensions.looksLikeImage(url)) {
            s += 200;
        }
        if (c.mimeType() != null && !c.mimeType().isEmpty()) {
            s += 100;
        }
        if (c.contentTypeHeader() != null && !c.contentTypeHeader().isEmpty()) {
            s += 50;
        }
        if (c.contentLength() > 0) {
            s += (int) Math.min(49, c.contentLength() / (512L * 1024L));
        }
        return s;
    }

    /** 归类：优先看 Content-Type，其次看扩展名。 */
    static MediaAsset.Kind kindOf(SniffResult.Candidate c) {
        String mime = c.mimeType() == null ? "" : c.mimeType().toLowerCase(java.util.Locale.ROOT);
        String url = c.url() == null ? "" : c.url();
        if (mime.startsWith("video/")) {
            return MediaAsset.Kind.VIDEO;
        }
        if (mime.startsWith("audio/")) {
            return MediaAsset.Kind.AUDIO;
        }
        if (mime.startsWith("image/")) {
            return MediaAsset.Kind.IMAGE;
        }
        if (MediaExtensions.looksLikePlaylist(url) || MediaExtensions.looksLikeVideo(url)) {
            return MediaAsset.Kind.VIDEO;
        }
        if (MediaExtensions.looksLikeAudio(url)) {
            return MediaAsset.Kind.AUDIO;
        }
        if (MediaExtensions.looksLikeImage(url)) {
            return MediaAsset.Kind.IMAGE;
        }
        return null;
    }

    /** 按 host 粗判平台（嗅探通道拿不到专用解析器，只能从域名推断）。 */
    static Platform inferPlatform(String url) {
        String host = UrlUtils.host(url);
        if (host == null) {
            return Platform.UNKNOWN;
        }
        if (host.endsWith("douyin.com") || host.endsWith("iesdouyin.com")) {
            return Platform.DOUYIN;
        }
        if (host.endsWith("xiaohongshu.com") || host.endsWith("xhslink.com")) {
            return Platform.XIAOHONGSHU;
        }
        if (host.endsWith("bilibili.com") || host.endsWith("b23.tv")) {
            return Platform.BILIBILI;
        }
        if (host.endsWith("weibo.com") || host.endsWith("weibo.cn")) {
            return Platform.WEIBO;
        }
        if (host.endsWith("toutiao.com") || host.endsWith("ixigua.com")) {
            return Platform.TOUTIAO;
        }
        if (host.endsWith("weixin.qq.com")) {
            return Platform.WECHAT_MP;
        }
        if (host.endsWith("weishi.qq.com")) {
            return Platform.WEISHI;
        }
        if (host.endsWith("pipix.com")) {
            return Platform.PIPIXIA;
        }
        if (host.endsWith("kuaishou.com")) {
            return Platform.KUAISHOU;
        }
        if (host.endsWith("instagram.com")) {
            return Platform.INSTAGRAM;
        }
        return Platform.UNKNOWN;
    }
}
