package com.yohu.lib_urlparse.provider.xiaohongshu;

import com.yohu.lib_urlparse.api.Author;
import com.yohu.lib_urlparse.api.FailureReason;
import com.yohu.lib_urlparse.api.MediaAsset;
import com.yohu.lib_urlparse.api.ParseFailure;
import com.yohu.lib_urlparse.api.Platform;
import com.yohu.lib_urlparse.api.UrlParseRequest;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.lib_urlparse.fetch.DefaultHttpClient;
import com.yohu.lib_urlparse.rules.XiaohongshuRules;
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
 * 小红书：短链展开 → 服务端渲染的 {@code __INITIAL_STATE__} → 笔记对象。
 *
 * <p>两个必须保留的细节（都来自真实失败）：
 * <ol>
 *   <li><b>先访问首页"热身"</b>：不带会话 Cookie 时，笔记页会 302 到
 *       {@code /404/sec_*} 的安全页，而 HTTP 状态仍是 200——只看状态码会误以为正常。</li>
 *   <li><b>区分"安全页/笔记不存在"与"页面结构变化"</b>：前者是链接本身的问题，
 *       后者是解析器的问题，混成一句笼统的"没有媒体"会误导人。</li>
 * </ol>
 *
 * <p>纯 JVM 可测：只依赖 {@link com.yohu.lib_urlparse.spi.HttpClient}。
 */
public final class XiaohongshuProvider implements ParseProvider {

    private static final String TAG = "XhsProvider";

    @Override
    public String channel() {
        return "xiaohongshu.webpage";
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
        if (request.platformHint() == Platform.XIAOHONGSHU) {
            return true;
        }
        String url = context != null && context.resolvedUrl() != null
                ? context.resolvedUrl() : request.rawInput();
        if (XiaohongshuRules.extractNoteId(url) != null) {
            return true;
        }
        return XiaohongshuRules.isXhsHost(UrlUtils.host(url));
    }

    @Override
    public UrlParseResult parse(ParseContext context, UrlParseRequest request) throws ParseFailure {
        String input = context.resolvedUrl() != null && !context.resolvedUrl().isEmpty()
                ? context.resolvedUrl() : request.rawInput();

        String pageUrl = input;
        String html;
        try {
            // 预热：让服务端下发会话 Cookie（否则笔记页会被导向安全页）
            try {
                context.http().get(XiaohongshuRules.REFERER, headers());
            } catch (Exception warm) {
                context.logger().warn(TAG, "首页热身失败（继续尝试）: " + warm);
            }
            if (context.http() instanceof DefaultHttpClient) {
                int n = ((DefaultHttpClient) context.http()).cookieJar().size();
                context.logger().info(TAG, "热身完成，Cookie 数=" + n);
            }
            HttpResponse resp = context.http().get(input, headers());
            html = resp.body() == null ? "" : resp.body();
            if (resp.finalUrl() != null && !resp.finalUrl().isEmpty()) {
                pageUrl = resp.finalUrl();
            }
            context.logger().info(TAG, "GET " + input + " -> HTTP " + resp.status()
                    + " final=" + pageUrl + " 长度=" + html.length());
        } catch (Exception e) {
            throw new ParseFailure(FailureReason.NETWORK, channel(), "笔记页请求失败: " + e, e);
        }

        String noteId = XiaohongshuRules.extractNoteId(pageUrl);
        if (noteId == null) {
            noteId = XiaohongshuRules.extractNoteId(input);
        }
        context.logger().info(TAG, "noteId=" + noteId);

        UrlParseResult result = extract(html, pageUrl, noteId, context);
        if (result.assets().isEmpty()) {
            if (pageUrl != null && (pageUrl.contains("/404/")
                    || pageUrl.contains("xhs_sec_server") || pageUrl.contains("/sec_"))) {
                throw new ParseFailure(FailureReason.NO_MEDIA_FOUND, channel(),
                        "小红书返回了安全/404 页（笔记可能不存在、已删除，或链接缺少有效的 xsec_token）。"
                                + "final=" + pageUrl);
            }
            throw new ParseFailure(FailureReason.NO_MEDIA_FOUND, channel(),
                    "笔记页里没有解析出媒体。诊断: " + result.extras().get("xhs.layers"));
        }
        return result;
    }

    /**
     * 分层抽取：{@code __INITIAL_STATE__} → 其它候选变量名 → 深递归找笔记对象。
     *
     * <p>每层结果都记进 {@code extras.xhs.layers}，失败时能区分"页面没有数据"与"字段改名"。
     * 静态方法便于单测（不联网）。
     */
    public static UrlParseResult extract(String html, String pageUrl, String noteId,
                                         ParseContext context) {
        UrlParseResult.Builder out = UrlParseResult.builder(Platform.XIAOHONGSHU)
                .channel("xiaohongshu.webpage")
                .sourceUrl(pageUrl)
                .workId(noteId);

        JSONObject note = null;
        String usedVar = null;
        for (String var : XiaohongshuRules.DATA_VARS) {
            String objText = XiaohongshuRules.extractObjectText(html, var);
            if (objText == null) {
                continue;
            }
            JSONObject root = tryParse(XiaohongshuRules.normalizeJsObject(objText));
            if (root == null) {
                if (context != null) {
                    context.logger().info(TAG, "变量 " + var + " 存在但 JSON 解析失败（长度 "
                            + objText.length() + "）");
                }
                continue;
            }
            usedVar = var;
            note = XiaohongshuRules.findNote(root, noteId);
            if (context != null) {
                context.logger().info(TAG, "变量 " + var + " 解析成功，笔记对象=" + (note != null));
            }
            if (note != null) {
                break;
            }
        }

        int media = 0;
        if (note != null) {
            String id = XiaohongshuRules.noteId(note);
            if (id != null) {
                out.workId(id);
            }
            out.title(XiaohongshuRules.title(note));
            String author = XiaohongshuRules.authorName(note);
            String authorId = XiaohongshuRules.authorId(note);
            if (author != null || authorId != null) {
                out.author(Author.builder().nickname(author).uid(authorId).build());
            }
            out.extra("xhs.type", XiaohongshuRules.noteType(note));

            String video = XiaohongshuRules.videoUrl(note);
            if (video != null) {
                out.asset(MediaAsset.video(video).mimeType("video/mp4").build());
                media++;
            }
            List<String> images = XiaohongshuRules.imageUrls(note);
            for (String img : images) {
                out.asset(MediaAsset.image(img).build());
                media++;
            }
            out.extra("xhs.images", String.valueOf(images.size()));
        }

        out.extra("xhs.layers", XiaohongshuRules.layerSummary(html, usedVar, media));
        if (context != null) {
            context.logger().info(TAG, "抽取结果: " + XiaohongshuRules.layerSummary(html, usedVar, media));
        }
        return out.build();
    }

    private static JSONObject tryParse(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(s);
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    private static Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", XiaohongshuRules.USER_AGENT);
        h.put("Referer", XiaohongshuRules.REFERER);
        h.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        h.put("Accept-Language", "zh-CN,zh;q=0.9");
        return h;
    }
}
