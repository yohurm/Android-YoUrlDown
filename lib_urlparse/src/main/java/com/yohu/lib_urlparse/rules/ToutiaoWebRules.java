package com.yohu.lib_urlparse.rules;

import com.yohu.lib_urlparse.util.EncodingUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 今日头条 / 西瓜视频（同一 ID 空间）的**服务端渲染页面**解析规则（纯逻辑，可单测）。
 *
 * <p><b>为什么是这个入口</b>：桌面页（{@code www.toutiao.com/video/…}、
 * {@code www.ixigua.com/…}）是 ByteDance 的 JS 虚拟机壳（{@code _$jsvmprt}），
 * 且站点安全 SDK（{@code lf-security.bytegoofy.com}）会把结果渲染成"网页无法使用"——
 * 连其首页都如此，重载一次也无效（已实测）。
 * 而**移动页 {@code https://m.toutiao.com/i<id>/} 不做这个判定**，直接返回服务端渲染的
 * {@code RENDER_DATA}（实测 39,405 字符，标题正常）。
 *
 * <p><b>取流的两步</b>（全部无需求解签名）：
 * <ol>
 *   <li>{@code RENDER_DATA.articleInfo} 里有 {@code videoId}、{@code title}、
 *       {@code videoDuration}、{@code posterUrl}，以及 {@code playAuthTokenV2}；</li>
 *   <li>{@code playAuthTokenV2} 是 base64，解出来是一段**已经签好名**的
 *       {@code GetPlayInfoToken}（AWS4-HMAC-SHA256，含 video_id/aid/stream_type），
 *       拿它去 {@code https://vod.bytedanceapi.com/?<token>} 就能得到
 *       {@code Result.Data.PlayInfoList[].MainPlayUrl}（实测 HTTP 200，
 *       720p h264 mp4，1280x720，251.4s）。</li>
 * </ol>
 * 换句话说：**签名由页面替我们算好了**，我方不需要复现任何签名算法。
 */
public final class ToutiaoWebRules {

    private ToutiaoWebRules() {
    }

    /** 与头条/西瓜同源的移动入口。 */
    public static final String MOBILE_HOST = "m.toutiao.com";

    /** VOD 取流端点（GetPlayInfo）。 */
    public static final String VOD_ENDPOINT = "https://vod.bytedanceapi.com/?";

    private static final String[] HOSTS = {
            "toutiao.com", "ixigua.com", "ixiguavideo.com",
    };

    private static final Pattern ID_IN_I_PATH = Pattern.compile("/i(\\d{10,25})");
    private static final Pattern ID_IN_VIDEO_PATH = Pattern.compile("/(?:video|group|article)/(\\d{10,25})");
    private static final Pattern ID_IN_QUERY = Pattern.compile("[?&](?:item_id|group_id|video_id)=(\\d{10,25})");
    private static final Pattern RENDER_DATA = Pattern.compile(
            "<script[^>]*id=[\"']RENDER_DATA[\"'][^>]*>([\\s\\S]*?)</script>",
            Pattern.CASE_INSENSITIVE);

    /** 是否可能是头条/西瓜的作品链接。 */
    public static boolean isToutiaoUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String host = hostOf(url);
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        for (String suffix : HOSTS) {
            if (h.equals(suffix) || h.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取作品 ID。覆盖三种真实形态（都实测过）：
     * {@code m.toutiao.com/i7035542323689685535/}、
     * {@code www.toutiao.com/video/7035542323689685535/}、
     * {@code www.ixigua.com/7035542323689685535}。
     *
     * @return 纯数字 ID；取不到返回 null
     */
    public static String extractItemId(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        Matcher m = ID_IN_I_PATH.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        m = ID_IN_VIDEO_PATH.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        m = ID_IN_QUERY.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        // 兜底：路径最后一段是纯数字（西瓜的 /<id> 形态）
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int slash = path.lastIndexOf('/');
        if (slash >= 0 && slash < path.length() - 1) {
            String last = path.substring(slash + 1);
            if (last.matches("\\d{10,25}")) {
                return last;
            }
        }
        return null;
    }

    /** 移动页 URL：绕开桌面页的 JS-VM 壳与安全 SDK 判定。 */
    public static String mobilePageUrl(String itemId) {
        return "https://" + MOBILE_HOST + "/i" + itemId + "/";
    }

    /**
     * 从页面里取 {@code RENDER_DATA} 并解析成 JSON。
     *
     * <p>该 script 的内容是 **URL 编码过的 JSON**（部分页面不编码），两种都要吃。
     *
     * @return 解析成功返回对象；找不到或解析失败返回 null
     */
    public static JSONObject extractRenderData(String html) {
        if (html == null || html.isEmpty()) {
            return null;
        }
        Matcher m = RENDER_DATA.matcher(html);
        if (!m.find()) {
            return null;
        }
        String body = m.group(1).trim();
        String decoded = body;
        try {
            decoded = java.net.URLDecoder.decode(body, "UTF-8");
        } catch (Exception ignored) {
            // 不编码的页面走这里
        }
        try {
            return new JSONObject(decoded);
        } catch (Exception e) {
            return null;
        }
    }

    /** 作品本体对象。 */
    public static JSONObject articleInfo(JSONObject renderData) {
        return renderData == null ? null : renderData.optJSONObject("articleInfo");
    }

    /**
     * 把 {@code playAuthTokenV2} 解成**已签名**的 GetPlayInfo 请求参数。
     *
     * @return 形如 {@code Action=GetPlayInfo&…&video_id=…}；失败返回 null
     */
    public static String decodePlayAuthToken(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }
        try {
            byte[] raw = EncodingUtils.decodeBase64Standard(base64);
            if (raw == null) {
                return null;
            }
            JSONObject o = new JSONObject(new String(raw, "UTF-8"));
            String token = o.optString("GetPlayInfoToken", null);
            return token != null && !token.isEmpty() ? token : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 拼出 VOD 取流 URL。 */
    public static String vodRequestUrl(String playInfoToken) {
        return playInfoToken == null ? null : VOD_ENDPOINT + playInfoToken;
    }

    /** 一条播放地址及其规格。 */
    public static final class PlayUrl {
        public final String url;
        public final String definition;
        public final int width;
        public final int height;
        public final long bitrate;
        public final String format;

        PlayUrl(String url, String definition, int width, int height, long bitrate, String format) {
            this.url = url;
            this.definition = definition;
            this.width = width;
            this.height = height;
            this.bitrate = bitrate;
            this.format = format;
        }
    }

    /**
     * 从 VOD 响应里取播放地址（{@code Result.Data.PlayInfoList[]}）。
     *
     * <p>优先 {@code MainPlayUrl}，为空时退 {@code BackupPlayUrl}。
     */
    public static List<PlayUrl> playUrlsFromVod(JSONObject vod) {
        List<PlayUrl> out = new ArrayList<>();
        if (vod == null) {
            return out;
        }
        JSONObject result = vod.optJSONObject("Result");
        JSONObject data = result == null ? null : result.optJSONObject("Data");
        JSONArray list = data == null ? null : data.optJSONArray("PlayInfoList");
        if (list == null) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject p = list.optJSONObject(i);
            if (p == null) {
                continue;
            }
            String u = p.optString("MainPlayUrl", null);
            if (u == null || u.isEmpty()) {
                u = p.optString("BackupPlayUrl", null);
            }
            if (u == null || !u.startsWith("http") || !seen.add(u)) {
                continue;
            }
            out.add(new PlayUrl(u, p.optString("Definition", null),
                    p.optInt("Width", 0), p.optInt("Height", 0),
                    p.optLong("Bitrate", 0L), p.optString("Format", null)));
        }
        return out;
    }

    /** VOD 响应里的封面。 */
    public static String coverUrlFromVod(JSONObject vod) {
        JSONObject result = vod == null ? null : vod.optJSONObject("Result");
        JSONObject data = result == null ? null : result.optJSONObject("Data");
        return data == null ? null : data.optString("CoverUrl", null);
    }

    /** 秒数 → 毫秒。 */
    public static long durationMs(JSONObject info) {
        if (info == null) {
            return 0L;
        }
        double sec = info.optDouble("videoDuration", 0d);
        return sec <= 0 ? 0L : (long) (sec * 1000d);
    }

    private static String hostOf(String url) {
        try {
            java.net.URI u = java.net.URI.create(url);
            return u.getHost();
        } catch (Exception e) {
            // 退化解析：//host/
            int i = url.indexOf("//");
            if (i < 0) {
                return null;
            }
            String rest = url.substring(i + 2);
            int slash = rest.indexOf('/');
            return slash >= 0 ? rest.substring(0, slash) : rest;
        }
    }
}
