package com.yohu.lib_urlparse.provider.bilibili;

import com.yohu.lib_urlparse.api.Author;
import com.yohu.lib_urlparse.api.FailureReason;
import com.yohu.lib_urlparse.api.MediaAsset;
import com.yohu.lib_urlparse.api.ParseFailure;
import com.yohu.lib_urlparse.api.Platform;
import com.yohu.lib_urlparse.api.UrlParseRequest;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.lib_urlparse.rules.BilibiliRules;
import com.yohu.lib_urlparse.spi.HttpResponse;
import com.yohu.lib_urlparse.spi.ParseContext;
import com.yohu.lib_urlparse.spi.ParseProvider;
import com.yohu.lib_urlparse.spi.ProviderPriority;
import com.yohu.lib_urlparse.util.UrlUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * B 站：**只用公开接口**，不需要任何凭据。
 *
 * <p>流程：
 * <ol>
 *   <li>展开短链（{@code b23.tv}），取出 {@code BV} 号（或 {@code av} 号）；</li>
 *   <li>{@code x/web-interface/view} 拿标题/作者/封面/时长/cid；</li>
 *   <li>{@code x/player/playurl}：先用 DASH（{@code fnval=16}）拿分离的视频+音频轨
 *       （能挑最高分辨率），失败再退到 {@code platform=html5} 的渐进式 mp4。</li>
 * </ol>
 *
 * <p>两类常见拒绝都如实报出，不假装成功：
 * <ul>
 *   <li>{@code code=-352/-412}（风控）：先访问站点首页收集它自己下发的公开 Cookie
 *       （{@code buvid3} 等）再重试一次；</li>
 *   <li>其它非零 code：把 code 与 message 原样带进失败原因。</li>
 * </ul>
 *
 * <p>纯 JVM 可测：只依赖 {@link com.yohu.lib_urlparse.spi.HttpClient}。
 */
public final class BilibiliProvider implements ParseProvider {

    private static final String TAG = "BilibiliProvider";

    @Override
    public String channel() {
        return "bilibili.api";
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
        if (request.platformHint() == Platform.BILIBILI) {
            return true;
        }
        String url = context != null && context.resolvedUrl() != null
                ? context.resolvedUrl() : request.rawInput();
        if (BilibiliRules.extractBvid(url) != null || BilibiliRules.extractAid(url) != null) {
            return true;
        }
        return BilibiliRules.isBilibiliHost(UrlUtils.host(url));
    }

    @Override
    public UrlParseResult parse(ParseContext context, UrlParseRequest request) throws ParseFailure {
        String input = context.resolvedUrl() != null && !context.resolvedUrl().isEmpty()
                ? context.resolvedUrl() : request.rawInput();

        // 1) 短链展开
        String pageUrl = input;
        if (BilibiliRules.isShortLinkHost(UrlUtils.host(input))) {
            String expanded = expand(input, context);
            if (expanded != null) {
                pageUrl = expanded;
            }
        }

        String bvid = BilibiliRules.extractBvid(pageUrl);
        String aidText = BilibiliRules.extractAid(pageUrl);
        if (bvid == null && aidText == null) {
            throw new ParseFailure(FailureReason.UNRECOGNISED_INPUT, channel(),
                    "链接里没有 BV/av 号: " + pageUrl);
        }
        context.logger().info(TAG, "bvid=" + bvid + " aid=" + aidText + " page=" + pageUrl);

        // 2) view
        JSONObject view = getJson(BilibiliRules.VIEW_API + (bvid != null ? bvid : "av" + aidText),
                context, "view");
        int code = view.optInt("code", -1);
        JSONObject data = view.optJSONObject("data");
        if (code != 0 || data == null) {
            throw new ParseFailure(FailureReason.UNEXPECTED_RESPONSE, channel(),
                    "view 接口 code=" + code + " message=" + view.optString("message", ""));
        }

        String title = data.optString("title", null);
        String desc = data.optString("desc", null);
        String cover = data.optString("pic", null);
        long durationSec = data.optLong("duration", 0L);
        long cid = data.optLong("cid", 0L);
        long realAid = data.optLong("aid", 0L);
        String realBvid = data.optString("bvid", bvid);

        UrlParseResult.Builder out = UrlParseResult.builder(Platform.BILIBILI)
                .channel(channel())
                .sourceUrl(pageUrl)
                .workId(realBvid != null && !realBvid.isEmpty() ? realBvid : bvid)
                .title(title)
                .extra("bilibili.aid", String.valueOf(realAid))
                .extra("bilibili.cid", String.valueOf(cid))
                .extra("bilibili.durationSec", String.valueOf(durationSec));
        if (desc != null && !desc.isEmpty()) {
            out.extra("bilibili.desc", desc);
        }

        JSONObject owner = data.optJSONObject("owner");
        if (owner != null) {
            out.author(Author.builder()
                    .nickname(owner.optString("name", null))
                    .avatarUrl(owner.optString("face", null))
                    .uid(String.valueOf(owner.optLong("mid", 0L)))
                    .build());
        }
        if (cover != null && cover.startsWith("http")) {
            out.asset(MediaAsset.cover(cover).build());
        }

        // 3) 取播放地址
        if (cid <= 0) {
            throw new ParseFailure(FailureReason.UNEXPECTED_RESPONSE, channel(),
                    "view 接口没有返回 cid，无法解析播放地址");
        }
        addPlayUrls(out, realBvid != null && !realBvid.isEmpty() ? realBvid : bvid, realAid, cid,
                durationSec, context);

        UrlParseResult result = out.build();
        if (result.assets(MediaAsset.Kind.VIDEO).isEmpty()) {
            throw new ParseFailure(FailureReason.UNEXPECTED_RESPONSE, channel(),
                    "没有可播放的视频地址（诊断: "
                            + result.extras().get("bilibili.playurl.note") + "）");
        }
        return result;
    }

    // ------------------------------------------------------------------ 播放地址

    private void addPlayUrls(UrlParseResult.Builder out, String bvid, long aid, long cid,
                             long durationSec, ParseContext context) {
        // (a) 先试 DASH：能拿到分离的视频/音频轨，且能挑最高分辨率
        String dashNote = null;
        boolean gotDashVideo = false;
        try {
            String url = BilibiliRules.PLAYURL_API + "?bvid=" + bvid + "&cid=" + cid
                    + "&qn=80&fnval=16&fnver=0&fourk=1";
            JSONObject resp = getJson(url, context, "playurl-dash");
            JSONObject data = resp.optJSONObject("data");
            int code = resp.optInt("code", -1);
            if (code == 0 && data != null) {
                JSONObject dash = data.optJSONObject("dash");
                if (dash != null) {
                    JSONArray videos = dash.optJSONArray("video");
                    if (videos != null && videos.length() > 0) {
                        JSONObject best = pickBestVideo(videos);
                        String vurl = firstUrl(best);
                        if (vurl != null) {
                            MediaAsset.Builder vb = MediaAsset.video(vurl)
                                    .mimeType("video/mp4")
                                    .dimension(best.optInt("width", 0), best.optInt("height", 0))
                                    .durationMs(durationSec * 1000L)
                                    .headers(biliHeaders());
                            addBackups(vb, best);
                            out.asset(vb.build());
                            gotDashVideo = true;
                        }
                    }
                    JSONArray audios = dash.optJSONArray("audio");
                    if (audios != null && audios.length() > 0) {
                        JSONObject bestAudio = null;
                        long bestBw = -1;
                        for (int i = 0; i < audios.length(); i++) {
                            JSONObject a = audios.optJSONObject(i);
                            if (a == null) {
                                continue;
                            }
                            long bw = a.optLong("bandwidth", 0L);
                            if (bw > bestBw) {
                                bestBw = bw;
                                bestAudio = a;
                            }
                        }
                        String aurl = firstUrl(bestAudio);
                        if (aurl != null) {
                            out.asset(MediaAsset.audio(aurl).mimeType("audio/mp4")
                                    .headers(biliHeaders()).build());
                        }
                    }
                    dashNote = "dash: " + videos.length() + " 路视频 / "
                            + (audios == null ? 0 : audios.length()) + " 路音频";
                } else {
                    dashNote = "dash 缺失";
                }
            } else {
                dashNote = "code=" + code + " " + resp.optString("message", "");
            }
        } catch (ParseFailure e) {
            dashNote = "失败: " + e.getMessage();
        }
        out.extra("bilibili.dash", dashNote == null ? "unknown" : dashNote);

        if (gotDashVideo) {
            return;
        }

        // (b) 退到 html5 渐进式：匿名最稳，通常只有一个 mp4
        try {
            String url = BilibiliRules.HTML5_PLAYURL_API + "&bvid=" + bvid + "&cid=" + cid;
            JSONObject resp = getJson(url, context, "playurl-html5");
            JSONObject data = resp.optJSONObject("data");
            int code = resp.optInt("code", -1);
            if (code == 0 && data != null) {
                JSONArray durl = data.optJSONArray("durl");
                if (durl != null && durl.length() > 0) {
                    for (int i = 0; i < durl.length(); i++) {
                        JSONObject seg = durl.optJSONObject(i);
                        String u = seg == null ? null : seg.optString("url", null);
                        if (u != null && u.startsWith("http")) {
                            out.asset(MediaAsset.video(u).mimeType("video/mp4")
                                    .durationMs(durationSec * 1000L).headers(biliHeaders()).build());
                        }
                    }
                    out.extra("bilibili.html5", "durl: " + durl.length() + " 段");
                    return;
                }
            }
            out.extra("bilibili.html5", "code=" + code + " " + resp.optString("message", ""));
            out.extra("bilibili.playurl.note", "DASH(" + dashNote + ") 与 html5 均未给出可用地址");
        } catch (ParseFailure e) {
            out.extra("bilibili.html5", "失败: " + e.getMessage());
            out.extra("bilibili.playurl.note", "DASH(" + dashNote + ") 与 html5 均失败");
        }
    }

    /** 从 DASH 的 video 数组里挑分辨率最高的（同分辨率取码率最高）。public 以便单测。 */
    public static JSONObject pickBestVideo(JSONArray videos) {
        JSONObject best = null;
        int bestPixels = -1;
        long bestBw = -1;
        for (int i = 0; i < videos.length(); i++) {
            JSONObject v = videos.optJSONObject(i);
            if (v == null) {
                continue;
            }
            int px = v.optInt("width", 0) * v.optInt("height", 0);
            long bw = v.optLong("bandwidth", 0L);
            if (px > bestPixels || (px == bestPixels && bw > bestBw)) {
                bestPixels = px;
                bestBw = bw;
                best = v;
            }
        }
        return best;
    }

    /** DASH 轨道地址可能在 {@code baseUrl} 或 {@code base_url}，两个都认。public 以便单测。 */
    public static String firstUrl(JSONObject track) {
        if (track == null) {
            return null;
        }
        String u = track.optString("baseUrl", null);
        if (u == null || u.isEmpty()) {
            u = track.optString("base_url", null);
        }
        if (u != null && u.startsWith("http")) {
            return u;
        }
        JSONArray backups = track.optJSONArray("backupUrl");
        if (backups == null) {
            backups = track.optJSONArray("backup_url");
        }
        if (backups != null && backups.length() > 0) {
            String b = backups.optString(0, null);
            if (b != null && b.startsWith("http")) {
                return b;
            }
        }
        return null;
    }

    private static void addBackups(MediaAsset.Builder b, JSONObject track) {
        JSONArray backups = track.optJSONArray("backupUrl");
        if (backups == null) {
            backups = track.optJSONArray("backup_url");
        }
        if (backups == null) {
            return;
        }
        for (int i = 0; i < backups.length(); i++) {
            String u = backups.optString(i, null);
            if (u != null && u.startsWith("http")) {
                b.alternate(u);
            }
        }
    }

    // ------------------------------------------------------------------ HTTP

    private String expand(String url, ParseContext context) {
        try {
            HttpResponse resp = context.http().get(url, biliHeaders());
            String finalUrl = resp.finalUrl();
            context.logger().info(TAG, "短链 " + url + " -> " + finalUrl);
            return finalUrl;
        } catch (Exception e) {
            context.logger().warn(TAG, "短链展开失败: " + e);
            return null;
        }
    }

    /**
     * 取 JSON；遇到风控（{@code -352}/{@code -412}）先访问一次站点首页收集 Cookie 再重试一次。
     *
     * <p>Cookie 全部来自站点自己的 {@code Set-Cookie}（{@code buvid3} 之类），
     * 不涉及任何私有凭据。
     */
    private JSONObject getJson(String url, ParseContext context, String what) throws ParseFailure {
        Map<String, String> headers = biliHeaders();
        try {
            HttpResponse resp = context.http().get(url, headers);
            JSONObject json = new JSONObject(resp.body().trim());
            int code = json.optInt("code", -1);
            if (code == -352 || code == -412) {
                context.logger().warn(TAG, what + " 风控 code=" + code + "，收集站点 Cookie 后重试");
                String cookie = warmUpCookies(context);
                if (cookie != null && !cookie.isEmpty()) {
                    Map<String, String> retry = new LinkedHashMap<>(headers);
                    retry.put("Cookie", cookie);
                    HttpResponse again = context.http().get(url, retry);
                    return new JSONObject(again.body().trim());
                }
            }
            return json;
        } catch (Exception e) {
            throw new ParseFailure(FailureReason.NETWORK, channel(),
                    what + " 请求失败: " + e, e);
        }
    }

    /** 访问首页拿 Set-Cookie，拼成 Cookie 头。返回 null 表示拿不到。 */
    private String warmUpCookies(ParseContext context) {
        try {
            HttpResponse home = context.http().get(BilibiliRules.REFERER, biliHeaders());
            String setCookie = home.header("set-cookie");
            if (setCookie == null || setCookie.isEmpty()) {
                context.logger().info(TAG, "warm-up 没有下发 Cookie");
                return null;
            }
            StringBuilder sb = new StringBuilder();
            // DefaultHttpClient 用换行分隔多条 Set-Cookie（避免与 Expires 里的逗号混淆）
            for (String line : setCookie.split("\n")) {
                String v = line.trim();
                if (v.isEmpty()) {
                    continue;
                }
                int semi = v.indexOf(';');
                if (semi > 0) {
                    v = v.substring(0, semi);
                }
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(v);
            }
            String c = sb.toString();
            context.logger().info(TAG, "warm-up Cookie 长度=" + c.length());
            return c.isEmpty() ? null : c;
        } catch (Exception e) {
            context.logger().warn(TAG, "warm-up 失败: " + e);
            return null;
        }
    }

    private static Map<String, String> biliHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Referer", BilibiliRules.REFERER);
        h.put("User-Agent", BilibiliRules.USER_AGENT);
        h.put("Accept", "application/json, text/plain, */*");
        h.put("Origin", BilibiliRules.REFERER);
        return h;
    }
}
