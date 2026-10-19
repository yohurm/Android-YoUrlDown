package com.yohu.lib_urlparse.provider.weibo;

import com.yohu.lib_urlparse.api.Author;
import com.yohu.lib_urlparse.api.FailureReason;
import com.yohu.lib_urlparse.api.MediaAsset;
import com.yohu.lib_urlparse.api.ParseFailure;
import com.yohu.lib_urlparse.api.Platform;
import com.yohu.lib_urlparse.api.UrlParseRequest;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.lib_urlparse.rules.WeiboRules;
import com.yohu.lib_urlparse.spi.HttpResponse;
import com.yohu.lib_urlparse.spi.ParseContext;
import com.yohu.lib_urlparse.spi.ParseProvider;
import com.yohu.lib_urlparse.spi.ProviderPriority;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * 微博：{@code weibo.com/ajax/statuses/show} 直解。
 *
 * <p>难点不在解析，而在**访客身份**：未登录请求常被回一个业务码为负的合法 JSON
 * （{@code {"ok":-100,"url":...}}），或者被引导到 passport 的 HTML 页。所以判据不能是
 * "响应是不是 JSON"，而必须是业务判据（见 {@link #needsVisitorCookies}）。
 *
 * <p>访客 Cookie 走站点自己的公开流程（对齐 yt-dlp 的 {@code _update_visitor_cookies}）：
 * {@code genvisitor} 拿 {@code tid} → {@code visitor?a=incarnate} 让站点下发 Cookie。
 * 全程不含任何账号凭据。
 *
 * <p>媒体来源三选一（一条微博可能同时有多种）：
 * {@code mix_media_info.items} 多媒体 / {@code page_info.media_info.playback_list} 单视频 /
 * {@code pics} 图集。
 *
 * <p>纯 JVM 可测：只依赖 {@link com.yohu.lib_urlparse.spi.HttpClient}。
 */
public final class WeiboProvider implements ParseProvider {

    private static final String TAG = "WeiboProvider";

    /**
     * 访客 Cookie 缓存。
     *
     * <p>幂等值：多个线程可能同时申请到不同的 Cookie，任选其一都能用，因此不需要加锁。
     */
    private volatile String guestCookies;

    @Override
    public String channel() {
        return "weibo.ajax";
    }

    @Override
    public ProviderPriority priority() {
        return ProviderPriority.DEDICATED_API;
    }

    @Override
    public boolean canHandle(ParseContext context, UrlParseRequest request) {
        if (request == null) {
            return false;
        }
        if (request.platformHint() == Platform.WEIBO) {
            return true;
        }
        String url = context != null && context.resolvedUrl() != null
                ? context.resolvedUrl() : request.rawInput();
        if (WeiboRules.extractStatusId(url) != null || WeiboRules.extractTvOid(url) != null) {
            return true;
        }
        return WeiboRules.isWeiboHost(com.yohu.lib_urlparse.util.UrlUtils.host(url));
    }

    @Override
    public UrlParseResult parse(ParseContext context, UrlParseRequest request) throws ParseFailure {
        String input = context.resolvedUrl() != null && !context.resolvedUrl().isEmpty()
                ? context.resolvedUrl() : request.rawInput();

        String id = WeiboRules.extractStatusId(input);
        if (id == null) {
            String oid = WeiboRules.extractTvOid(input);
            if (oid != null) {
                id = resolveMidFromOid(oid, input, context);
            }
        }
        if (id == null) {
            throw new ParseFailure(FailureReason.UNRECOGNISED_INPUT, channel(),
                    "无法从链接中取到微博状态标识: " + input);
        }
        context.logger().info(TAG, "status id=" + id);

        JSONObject status = fetchStatus(id, context);
        UrlParseResult result = toResult(status, input, context);
        if (result.assets().isEmpty()) {
            // 用 NO_MEDIA_FOUND：调用方据此可以继续试别的通道（例如通用嗅探），而不是判死。
            context.logger().info(TAG, "无媒体。顶层键=[" + keysOf(status) + "] ok="
                    + status.opt("ok") + " msg=" + status.optString("msg", "")
                    + " hasPageInfo=" + (status.optJSONObject("page_info") != null)
                    + " hasMix=" + (status.optJSONObject("mix_media_info") != null)
                    + " pics=" + (status.optJSONArray("pics") == null
                            ? 0 : status.optJSONArray("pics").length()));
            throw new ParseFailure(FailureReason.NO_MEDIA_FOUND, channel(),
                    "微博接口返回成功但没有可下载媒体（可能是纯文字微博）: id=" + id);
        }
        return result;
    }

    // ------------------------------------------------------------------ 解析

    /** 把状态 JSON 映射为解析结果；静态方法便于单测（不联网）。 */
    public static UrlParseResult toResult(JSONObject status, String sourceUrl,
                                          ParseContext context) {
        String id = firstNonEmpty(status.optString("id_str", null),
                status.optString("id", null), status.optString("mid", null));
        String mblogid = status.optString("mblogid", null);
        String text = stripHtml(firstNonEmpty(status.optString("text_raw", null),
                status.optString("text", null)));

        UrlParseResult.Builder out = UrlParseResult.builder(Platform.WEIBO)
                .channel("weibo.ajax")
                .sourceUrl(sourceUrl)
                .workId(mblogid != null && !mblogid.isEmpty() ? mblogid : id)
                .title(text)
                .extra("weibo.id", String.valueOf(id));
        if (mblogid != null) {
            out.extra("weibo.mblogid", mblogid);
        }
        out.extra("weibo.attitudes", String.valueOf(status.optInt("attitudes_count", 0)));
        out.extra("weibo.reposts", String.valueOf(status.optInt("reposts_count", 0)));

        JSONObject user = status.optJSONObject("user");
        if (user != null) {
            out.author(Author.builder()
                    .nickname(user.optString("screen_name", null))
                    .avatarUrl(user.optString("avatar_hd",
                            user.optString("profile_image_url", null)))
                    .uid(firstNonEmpty(user.optString("idstr", null), user.optString("id", null)))
                    .build());
        }

        int videos = 0;
        int images = 0;

        // 1) 多媒体（一条微博含多个视频/图片）
        JSONObject mix = status.optJSONObject("mix_media_info");
        if (mix != null) {
            JSONArray items = mix.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    String type = item.optString("type", "");
                    JSONObject data = item.optJSONObject("data");
                    if ("pic".equals(type)) {
                        String url = data == null ? null
                                : firstNonEmpty(data.optString("large", null),
                                        data.optString("url", null));
                        if (url != null) {
                            out.asset(MediaAsset.image(url).build());
                            images++;
                        }
                        continue;
                    }
                    // 非图片项按"含媒体信息的视频"处理
                    JSONObject fake = new JSONObject();
                    try {
                        fake.put("page_info", new JSONObject().put("media_info",
                                data == null ? new JSONObject() : data.optJSONObject("media_info")));
                        if (data != null) {
                            fake.put("id", data.optString("object_id", ""));
                        }
                    } catch (org.json.JSONException ignored) {
                        // 字段缺失时保持空对象，后续自然取不到地址
                    }
                    videos += addVideos(out, fake, text);
                }
            }
        }

        // 2) 单媒体（最常见）
        if (videos == 0) {
            videos += addVideos(out, status, text);
        }

        // 3) 普通图集
        JSONArray pics = status.optJSONArray("pics");
        if (pics != null) {
            for (int i = 0; i < pics.length(); i++) {
                JSONObject p = pics.optJSONObject(i);
                String url = p == null ? null : p.optString("large_url", null);
                if (url == null && p != null) {
                    url = p.optString("url", null);
                }
                if (url != null) {
                    out.asset(MediaAsset.image(url).build());
                    images++;
                }
            }
        }

        JSONObject pageInfo = status.optJSONObject("page_info");
        if (pageInfo != null) {
            String pic = pageInfo.optString("page_pic", null);
            if (pic != null && pic.startsWith("http")) {
                out.asset(MediaAsset.cover(pic).build());
            }
        }
        out.extra("weibo.videos", String.valueOf(videos));
        out.extra("weibo.images", String.valueOf(images));
        return out.build();
    }

    /**
     * 从 {@code page_info.media_info} 取视频地址。
     *
     * <p>优先 {@code playback_list[].play_info.url}（带清晰度/码率/尺寸）；
     * 缺失时退到"含 {@code label=} 的地址"（少见但实测确有必要）。
     */
    private static int addVideos(UrlParseResult.Builder out, JSONObject status, String title) {
        JSONObject pageInfo = status.optJSONObject("page_info");
        if (pageInfo == null) {
            return 0;
        }
        JSONObject media = pageInfo.optJSONObject("media_info");
        if (media == null) {
            return 0;
        }
        long durationSec = media.optLong("duration", 0L);
        String videoTitle = firstNonEmpty(media.optString("video_title", null),
                media.optString("kol_title", null), media.optString("name", null), title);

        int added = 0;
        JSONArray playback = media.optJSONArray("playback_list");
        if (playback != null) {
            for (int i = 0; i < playback.length(); i++) {
                JSONObject item = playback.optJSONObject(i);
                JSONObject play = item == null ? null : item.optJSONObject("play_info");
                String url = play == null ? null : play.optString("url", null);
                if (url == null || !url.startsWith("http")) {
                    continue;
                }
                MediaAsset.Builder b = MediaAsset.video(url)
                        .mimeType(play.optString("mime", "video/mp4"))
                        .durationMs(durationSec > 0 ? durationSec * 1000L : 0L);
                int w = play.optInt("width", 0);
                int h = play.optInt("height", 0);
                if (w > 0 && h > 0) {
                    b.dimension(w, h);
                }
                long size = play.optLong("size", 0L);
                if (size > 0) {
                    b.sizeBytes(size);
                }
                out.asset(b.build());
                added++;
            }
        }
        if (added == 0) {
            // 兜底：遍历 media_info 里所有字符串，找带 label= 的地址
            for (java.util.Iterator<String> it = media.keys(); it.hasNext(); ) {
                String k = it.next();
                String v = media.optString(k, null);
                if (v == null || !v.startsWith("http") || !v.contains(WeiboRules.LABEL_MARKER)) {
                    continue;
                }
                MediaAsset.Builder b = MediaAsset.video(v)
                        .mimeType("video/mp4")
                        .durationMs(durationSec > 0 ? durationSec * 1000L : 0L);
                Matcher m = WeiboRules.PATTERN_LABEL_TEMPLATE.matcher(v);
                if (m.find()) {
                    String[] wh = m.group(2).split("x");
                    try {
                        b.dimension(Integer.parseInt(wh[0]), Integer.parseInt(wh[1]));
                    } catch (RuntimeException ignored) {
                        // 尺寸解析失败不影响地址本身
                    }
                }
                out.asset(b.build());
                added++;
            }
        }
        if (added > 0 && videoTitle != null) {
            out.title(videoTitle);
        }
        return added;
    }

    // ------------------------------------------------------------------ HTTP

    private JSONObject fetchStatus(String id, ParseContext context) throws ParseFailure {
        String url = WeiboRules.STATUS_API + "?id=" + id;
        try {
            HttpResponse resp = context.http().get(url, headers());
            String body = resp.body() == null ? "" : resp.body().trim();

            if (!body.startsWith("{")) {
                context.logger().warn(TAG, "响应非 JSON（可能被引导到登录/访客页），尝试访客 Cookie 流程");
                body = retryWithGuestCookies(url, context, body);
            }

            JSONObject json = new JSONObject(body);
            if (needsVisitorCookies(json)) {
                // 风控响应**也是合法 JSON**（{"ok":-100,...}），只判断"是不是 JSON"会漏掉它。
                context.logger().warn(TAG, "风控/未登录响应 ok=" + json.opt("ok")
                        + "，申请访客 Cookie 后重试");
                String withCookies = retryWithGuestCookies(url, context, body);
                if (withCookies != null && !withCookies.equals(body)) {
                    JSONObject second = new JSONObject(withCookies);
                    if (!needsVisitorCookies(second)) {
                        return second;
                    }
                    context.logger().warn(TAG, "带访客 Cookie 后仍不可用 ok=" + second.opt("ok"));
                    return second;
                }
            }
            return json;
        } catch (org.json.JSONException e) {
            throw new ParseFailure(FailureReason.UNEXPECTED_RESPONSE, channel(),
                    "微博接口 JSON 解析失败: " + e, e);
        } catch (Exception e) {
            throw new ParseFailure(FailureReason.NETWORK, channel(),
                    "微博接口请求失败: " + e, e);
        }
    }

    /**
     * 这个响应是不是"需要访客身份"而不是真的状态数据。
     *
     * <p>判据：业务码 {@code ok < 0}，或压根没有状态标识（{@code id}/{@code id_str}/{@code mid} 全缺）。
     */
    public static boolean needsVisitorCookies(JSONObject json) {
        if (json == null) {
            return true;
        }
        Object ok = json.opt("ok");
        if (ok instanceof Number && ((Number) ok).intValue() < 0) {
            return true;
        }
        boolean hasId = json.opt("id") != null || json.optString("id_str", "").length() > 0
                || json.optString("mid", "").length() > 0;
        return !hasId;
    }

    /** 拿访客 Cookie 后重取；拿不到就返回原 body。 */
    private String retryWithGuestCookies(String url, ParseContext context, String fallbackBody) {
        String cookies = ensureGuestCookies(context);
        if (cookies == null || cookies.isEmpty()) {
            return fallbackBody;
        }
        try {
            HttpResponse again = context.http().get(url, headersWithCookie(cookies));
            return again.body() == null ? fallbackBody : again.body().trim();
        } catch (Exception e) {
            context.logger().warn(TAG, "带访客 Cookie 重试失败: " + e);
            return fallbackBody;
        }
    }

    /**
     * 申请访客 Cookie（对齐 yt-dlp {@code _update_visitor_cookies}）。
     *
     * <p>先 {@code genvisitor} 拿 tid，再请求 {@code visitor?a=incarnate} 让站点下发 Cookie。
     * 全程不含任何账号凭据。
     */
    private String ensureGuestCookies(ParseContext context) {
        String cached = guestCookies;
        if (cached != null) {
            return cached;
        }
        try {
            String fp = "{\"os\":\"1\",\"browser\":\"Chrome139,0,0,0\","
                    + "\"fonts\":\"undefined\",\"screenInfo\":\"1920*1080*24\","
                    + "\"plugins\":\"\"}";
            String genUrl = WeiboRules.VISITOR_GENVISITOR + "?cb=gen_callback&fp="
                    + java.net.URLEncoder.encode(fp, "UTF-8");
            HttpResponse gen = context.http().get(genUrl, headers());
            String body = gen.body() == null ? "" : gen.body();
            int lb = body.indexOf('(');
            int rb = body.lastIndexOf(')');
            String json = (lb >= 0 && rb > lb) ? body.substring(lb + 1, rb) : body;
            JSONObject genJson = new JSONObject(json);
            JSONObject data = genJson.optJSONObject("data");
            if (data == null) {
                context.logger().warn(TAG, "genvisitor 未返回 data");
                return null;
            }
            String tid = data.optString("tid", null);
            boolean newTid = data.optBoolean("new_tid", false);
            int confidence = data.optInt("confidence", 100);
            if (tid == null) {
                return null;
            }
            String visitUrl = WeiboRules.VISITOR_VISITOR + "?a=incarnate&t=" + tid
                    + "&w=" + (newTid ? 3 : 2)
                    + "&c=" + String.format(java.util.Locale.ROOT, "%03d", confidence)
                    + "&gc=&cb=cross_domain&from=weibo&_rand=" + Math.random();
            HttpResponse visit = context.http().get(visitUrl, headers());
            String setCookie = visit.header("set-cookie");
            String cookies = toCookieHeader(setCookie);
            context.logger().info(TAG, "访客 Cookie 长度=" + cookies.length());
            if (!cookies.isEmpty()) {
                guestCookies = cookies;
                return cookies;
            }
            return null;
        } catch (Exception e) {
            context.logger().warn(TAG, "访客 Cookie 流程失败: " + e);
            return null;
        }
    }

    /** 把响应里的 {@code set-cookie}（可能多条、换行分隔）拼成请求用的 Cookie 头。 */
    static String toCookieHeader(String setCookie) {
        if (setCookie == null || setCookie.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : setCookie.split("\n")) {
            String v = line.trim();
            if (v.isEmpty()) {
                continue;
            }
            int semi = v.indexOf(';');
            if (semi > 0) {
                v = v.substring(0, semi);
            }
            if (v.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(v);
        }
        return sb.toString();
    }

    /** tv/show 形态：先换成 mid，再走状态接口（对齐 yt-dlp {@code WeiboVideoIE}）。 */
    private String resolveMidFromOid(String oid, String referer, ParseContext context) {
        try {
            String url = WeiboRules.TV_COMPONENT_API + "?page=/tv/show/" + oid;
            HttpResponse resp = context.http().postForm(url, WeiboRules.tvComponentBody(oid),
                    headersWithReferer(referer));
            JSONObject json = new JSONObject(resp.body());
            JSONObject data = json.optJSONObject("data");
            JSONObject info = data == null ? null
                    : data.optJSONObject("Component_Play_Playinfo");
            String mid = info == null ? null : info.optString("mid", null);
            context.logger().info(TAG, "oid " + oid + " -> mid " + mid);
            return mid;
        } catch (Exception e) {
            context.logger().warn(TAG, "tv component 解析失败: " + e);
            return null;
        }
    }

    private static Map<String, String> headers() {
        return headersWithReferer(WeiboRules.REFERER);
    }

    private static Map<String, String> headersWithReferer(String referer) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Referer", referer);
        h.put("User-Agent", WeiboRules.USER_AGENT);
        h.put("Accept", "application/json, text/plain, */*");
        h.put("X-Requested-With", "XMLHttpRequest");
        return h;
    }

    private static Map<String, String> headersWithCookie(String cookie) {
        Map<String, String> h = headersWithReferer(WeiboRules.REFERER);
        h.put("Cookie", cookie);
        return h;
    }

    // ------------------------------------------------------------------ 小工具

    /** 去掉微博正文里的 HTML 标签与实体（正文会带 {@code <a>} 与 {@code &amp;}）。 */
    public static String stripHtml(String s) {
        if (s == null) {
            return null;
        }
        String out = s.replaceAll("<[^>]+>", "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
        return out.trim();
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    private static String keysOf(JSONObject o) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(it.next());
        }
        return sb.toString();
    }
}
