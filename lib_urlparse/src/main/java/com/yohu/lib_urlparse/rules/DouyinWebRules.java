package com.yohu.lib_urlparse.rules;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抖音分享页的**自包含**抽取规则（不依赖任何远程解析脚本或云服务）。
 *
 * <p>直接从分享页里已存在的结构化数据取地址——页面自己会把作品信息（含播放地址）
 * 序列化进 HTML，这是公开、可复现的路径。
 *
 * <p><b>为什么分层</b>：抖音页面结构随版本变化，而且同一内容在
 * {@code _ROUTER_DATA} / {@code RENDER_DATA} / 内联 JSON 之间搬过家。
 * 因此不赌单一字段，而是按"结构化程度从高到低"逐层尝试，
 * 每层都返回命中情况，宿主把命中层写进日志——失败时能一眼看出是"页面没数据"
 * 还是"字段改名了"，而不是笼统的"解析失败"。
 *
 * <p><b>措辞</b>：字段名来自公开可观察的页面结构；本类不声称覆盖所有版本，
 * 未命中的情形由 {@link #lastLayers()} 如实记录。
 */
public final class DouyinWebRules {

    private DouyinWebRules() {
    }

    /** 页面里可能承载结构化数据的变量名（按优先级）。 */
    public static final String[] DATA_VARS = {
            "_ROUTER_DATA", "RENDER_DATA", "__INITIAL_STATE__", "__pace_f__", "_SSR_DATA"
    };

    /** 直链形态：抖音播放地址通常形如 {@code https://.../play/...} 或 {@code .../playwm/...}。 */
    public static final Pattern PATTERN_MP4 =
            Pattern.compile("https?://[^\"'\\\\\\s<>]+?\\.mp4[^\"'\\\\\\s<>]*", Pattern.CASE_INSENSITIVE);

    /**
     * 无痕替换：把播放地址里的 {@code /playwm/} 换成 {@code /play/}。
     *
     * <p><b>说明</b>：这是社区长期观察到的行为（带水印地址与无水印地址只差这一段路径），
     * 但抖音随时可能改变语义。因此本工程的做法是：**两个地址都留下**——
     * 无水印的作为主地址，原始地址作为 {@code alternate} 备用；
     * 这样即使推断失效，用户仍能拿到可下载的文件，而不是一个 403。
     */
    public static String toNoWatermark(String url) {
        if (url == null) {
            return null;
        }
        return url.replace("/playwm/", "/play/");
    }

    // ------------------------------------------------------------------ 结构化层

    /**
     * 从 {@code _ROUTER_DATA} 里找作品对象。
     *
     * <p>形态（公开可观察）：{@code {"loaderData":{"video_(id)/page":{"videoInfoRes":{"item_list":[{...}]}}}}}}
     * 键名里带作品 ID，所以不能按固定键取，必须遍历 {@code loaderData} 的键。
     */
    public static JSONObject findItemFromRouterData(JSONObject routerData) {
        if (routerData == null) {
            return null;
        }
        JSONObject loaderData = routerData.optJSONObject("loaderData");
        if (loaderData == null) {
            // 有些版本把 item_list 直接放在顶层
            JSONObject direct = firstItemIn(routerData);
            return direct != null ? direct : deepFindAweme(routerData, 0);
        }
        JSONObject item = firstItemIn(loaderData);
        if (item != null) {
            return item;
        }
        return deepFindAweme(loaderData, 0);
    }

    /** 在对象里找任意 {@code *Res.item_list[0]} 或 {@code item_list[0]}。 */
    static JSONObject firstItemIn(JSONObject o) {
        if (o == null) {
            return null;
        }
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            Object v = o.opt(k);
            if (!(v instanceof JSONObject)) {
                continue;
            }
            JSONObject child = (JSONObject) v;
            JSONArray list = child.optJSONArray("item_list");
            if (list != null && list.length() > 0) {
                JSONObject item = list.optJSONObject(0);
                if (item != null) {
                    return item;
                }
            }
            JSONObject nested = firstItemIn(child);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    /**
     * 递归找一个"看起来像作品"的对象：含 {@code aweme_id} 或同时含 {@code video} 与 {@code desc}。
     * 这是最后的兜底——页面结构再怎么改名，作品对象总得带着它的 ID 和视频信息。
     */
    static JSONObject deepFindAweme(Object node, int depth) {
        if (depth > 12 || node == null) {
            return null;
        }
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = deepFindAweme(arr.opt(i), depth + 1);
                if (r != null) {
                    return r;
                }
            }
            return null;
        }
        if (!(node instanceof JSONObject)) {
            return null;
        }
        JSONObject o = (JSONObject) node;
        boolean hasId = o.opt("aweme_id") != null || o.opt("awemeId") != null
                // 图文/图集作品用的是 note_id，不是 aweme_id。
                // 实测教训：只认 aweme_id 时，图集作品会被判成"页面里没有作品对象"。
                || o.opt("note_id") != null || o.opt("noteId") != null;
        boolean hasVideoAndDesc = o.optJSONObject("video") != null && o.opt("desc") != null;
        if (hasId && (o.optJSONObject("video") != null || o.optJSONArray("images") != null)) {
            return o;
        }
        // 图集有时连 *_id 都不在作品对象上（ID 在上层），此时"有 images 且有 desc"也认
        if (o.optJSONArray("images") != null && o.opt("desc") != null) {
            return o;
        }
        if (hasVideoAndDesc) {
            return o;
        }
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            Object v = o.opt(it.next());
            if (v instanceof JSONObject || v instanceof JSONArray) {
                JSONObject r = deepFindAweme(v, depth + 1);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 字段抽取

    /** 作品 ID。 */
    public static String itemId(JSONObject item) {
        if (item == null) {
            return null;
        }
        String id = item.optString("aweme_id", null);
        if (id == null || id.isEmpty()) {
            id = item.optString("awemeId", null);
        }
        if (id == null || id.isEmpty()) {
            // 图文/图集作品用 note_id
            id = item.optString("note_id", null);
        }
        if (id == null || id.isEmpty()) {
            id = item.optString("noteId", null);
        }
        return (id == null || id.isEmpty()) ? null : id;
    }

    /**
     * 去掉图片上的水印参数。
     *
     * <p>实测抖音图集图片地址形如
     * {@code ...~tplv-dy-lqen-new-water:1056:1920:xxx:q80.webp}，
     * 其中 {@code -water} 段即水印变体。去掉后请求无水印版本。
     *
     * <p><b>说明</b>：与视频的 {@code playwm→play} 同类：属于社区长期观察到的行为，
     * 官方随时可能改动。因此与视频一样，**原始地址保留为 alternate**，
     * 推断失效时用户仍能下载到（带水印的）图片，而不是 404。
     */
    public static String toNoWatermarkImage(String url) {
        if (url == null) {
            return null;
        }
        return url.replace("tplv-dy-lqen-new-water", "tplv-dy-lqen-new")
                .replace("-water:", ":")
                .replace("~water:", "~");
    }

    /** 标题/文案。 */
    public static String title(JSONObject item) {
        if (item == null) {
            return null;
        }
        String desc = item.optString("desc", null);
        if (desc == null || desc.trim().isEmpty()) {
            desc = item.optString("title", null);
        }
        return (desc == null || desc.trim().isEmpty()) ? null : desc.trim();
    }

    public static String authorName(JSONObject item) {
        JSONObject a = item == null ? null : item.optJSONObject("author");
        return a == null ? null : a.optString("nickname", null);
    }

    public static String authorId(JSONObject item) {
        JSONObject a = item == null ? null : item.optJSONObject("author");
        if (a == null) {
            return null;
        }
        String uid = a.optString("uid", null);
        if (uid == null || uid.isEmpty()) {
            uid = a.optString("sec_uid", null);
        }
        return uid;
    }

    public static String authorAvatar(JSONObject item) {
        JSONObject a = item == null ? null : item.optJSONObject("author");
        return a == null ? null : firstUrlIn(a.optJSONObject("avatar_thumb"));
    }

    /**
     * 视频播放地址（按质量从高到低）。
     *
     * <p>优先 {@code bit_rate[].play_addr}（多档清晰度），再退到 {@code video.play_addr} /
     * {@code play_addr_h264}。每个地址都会给出"无水印形态"与"原始形态"两种。
     */
    /**
     * 这个播放地址是不是**退化数据**（拿不到真正的视频）？
     *
     * <p>实测来源：图集（图文作品）的 {@code video.play_addr.url_list} 里会被填进一条
     * {@code aweme/v1/play/?video_id=<图片URL>} —— 注意 {@code video_id} 的值**本身是一个完整
     * 的图片 URL**。那是抖音给图文作品塞的占位数据，不是视频。
     *
     * <p>不拦的后果很具体：解析会多报一条"视频"（图集本该是 8 张图却报 9 条候选），
     * 下载时又真的去请求这个地址、写下一个 **0 字节** 文件。
     * 所以判据是"参数值里有 {@code ://}"，而不是"下载失败了再说"。
     */
    static boolean looksDegenerate(String url) {
        if (url == null) {
            return true;
        }
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        for (String key : new String[]{"video_id=", "uri=", "vid="}) {
            int i = lower.indexOf(key);
            if (i < 0) {
                continue;
            }
            String value = lower.substring(i + key.length());
            int amp = value.indexOf('&');
            if (amp >= 0) {
                value = value.substring(0, amp);
            }
            if (value.contains("://") || value.contains("%3a%2f%2f")) {
                return true;
            }
        }
        return false;
    }

    public static List<VideoUrl> videoUrls(JSONObject item) {
        List<VideoUrl> out = new ArrayList<>();
        if (item == null) {
            return out;
        }
        JSONObject video = item.optJSONObject("video");
        if (video == null) {
            return out;
        }
        JSONArray bitRates = video.optJSONArray("bit_rate");
        if (bitRates != null) {
            for (int i = 0; i < bitRates.length(); i++) {
                JSONObject br = bitRates.optJSONObject(i);
                if (br == null) {
                    continue;
                }
                JSONObject addr = br.optJSONObject("play_addr");
                String u = firstUrlIn(addr);
                if (u == null || looksDegenerate(u)) {
                    continue;
                }
                out.add(new VideoUrl(u, br.optInt("gear_name", -1) >= 0 ? null : br.optString("gear_name", null),
                        br.optInt("bit_rate", 0), addr.optInt("width", 0), addr.optInt("height", 0)));
            }
        }
        if (out.isEmpty()) {
            for (String key : new String[]{"play_addr", "play_addr_h264", "play_addr_265", "download_addr"}) {
                JSONObject addr = video.optJSONObject(key);
                String u = firstUrlIn(addr);
                if (u != null && !looksDegenerate(u)) {
                    out.add(new VideoUrl(u, key, 0L,
                            addr.optInt("width", 0), addr.optInt("height", 0)));
                    break;
                }
            }
        }
        return out;
    }

    /** 图集（图文作品）的图片地址。 */
    public static List<String> imageUrls(JSONObject item) {
        List<String> out = new ArrayList<>();
        JSONArray images = item == null ? null : item.optJSONArray("images");
        if (images == null) {
            return out;
        }
        for (int i = 0; i < images.length(); i++) {
            JSONObject img = images.optJSONObject(i);
            String u = firstUrlIn(img);
            if (u != null) {
                out.add(u);
            }
        }
        return out;
    }

    public static String coverUrl(JSONObject item) {
        JSONObject video = item == null ? null : item.optJSONObject("video");
        if (video == null) {
            return null;
        }
        String u = firstUrlIn(video.optJSONObject("cover"));
        if (u == null) {
            u = firstUrlIn(video.optJSONObject("origin_cover"));
        }
        if (u == null) {
            u = firstUrlIn(video.optJSONObject("dynamic_cover"));
        }
        return u;
    }

    public static String musicUrl(JSONObject item) {
        JSONObject music = item == null ? null : item.optJSONObject("music");
        return music == null ? null : firstUrlIn(music.optJSONObject("play_url"));
    }

    public static long durationMs(JSONObject item) {
        JSONObject video = item == null ? null : item.optJSONObject("video");
        return video == null ? 0L : video.optLong("duration", 0L);
    }

    /** 从 {@code {"url_list":["..."]}} 形态里取第一个 http 地址。 */
    public static String firstUrlIn(JSONObject urlHolder) {
        if (urlHolder == null) {
            return null;
        }
        JSONArray list = urlHolder.optJSONArray("url_list");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                String u = list.optString(i, null);
                if (u != null && u.startsWith("http")) {
                    return u;
                }
            }
        }
        String single = urlHolder.optString("url", null);
        if (single != null && single.startsWith("http")) {
            return single;
        }
        return null;
    }

    // ------------------------------------------------------------------ 非结构化兜底

    /**
     * 从整页文本里正则捞 mp4 地址（最后兜底）。
     *
     * <p>为什么保留这一层：结构化字段改名时，页面里通常**仍然**会内联真实播放地址
     * （浏览器要拿它去播）。正则捞取不依赖字段名，因此是比"解析失败"更好的结果。
     *
     * <p><b>必须在匹配前先反转义</b>：页面里的地址常以 JSON 转义形态出现
     * （{@code https:\u002F\u002Fhost\u002Fplay\u002Fx.mp4}），连 {@code //} 都是转义的。
     * 若不先还原，形如 {@code https?://} 的模式**一个都匹配不到**——
     * 这个坑由单测抓到（{@code regexFallbackFindsInlineMp4WhenStructureIsUnknown}）。
     */
    public static List<String> regexMp4Urls(String html, int max) {
        List<String> out = new ArrayList<>();
        if (html == null) {
            return out;
        }
        String text = unescapeSlashes(html);
        Matcher m = PATTERN_MP4.matcher(text);
        while (m.find() && out.size() < max) {
            String u = m.group();
            if (!out.contains(u)) {
                out.add(u);
            }
        }
        return out;
    }

    /**
     * 还原被转义的斜杠：{@code \u002F}、{@code \/}、{@code \\/} → {@code /}。
     * 只动斜杠，不碰其它内容，避免破坏正文。
     */
    public static String unescapeSlashes(String s) {
        if (s == null || s.indexOf('\\') < 0) {
            return s;
        }
        return s.replace("\\u002F", "/")
                .replace("\\u002f", "/")
                .replace("\\/", "/");
    }

    /** 页面里是否出现某个数据变量（诊断用）。 */
    public static boolean mentionsVar(String html, String varName) {
        return html != null && html.contains(varName);
    }

    /** 从页面 HTML 里找作品 ID（短链展开后的页面常常只在数据里带 ID，URL 里没有）。 */
    public static final Pattern PATTERN_AWEME_ID_IN_HTML =
            Pattern.compile("\"aweme_?[iI]d\"\\s*:\\s*\"(\\d{10,25})\"");

    public static String extractAwemeIdFromHtml(String html) {
        if (html == null) {
            return null;
        }
        Matcher m = PATTERN_AWEME_ID_IN_HTML.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    /** 一个视频地址候选。 */
    public static final class VideoUrl {
        public final String url;
        public final String gear;
        public final long bitrate;
        public final int width;
        public final int height;

        public VideoUrl(String url, String gear, long bitrate, int width, int height) {
            this.url = url;
            this.gear = gear;
            this.bitrate = bitrate;
            this.width = width;
            this.height = height;
        }

        @Override
        public String toString() {
            return url + (gear != null ? " [" + gear + "]" : "")
                    + (width > 0 ? " " + width + "x" + height : "");
        }
    }

    /** 供宿主记录"哪一层命中"的小工具。 */
    public static String layerSummary(String html, boolean structured, boolean regex, int count) {
        StringBuilder sb = new StringBuilder();
        for (String v : DATA_VARS) {
            sb.append(v).append('=').append(mentionsVar(html, v) ? 'Y' : 'N').append(' ');
        }
        String mode = structured ? "结构化" : (regex ? "正则兜底" : "未命中");
        return String.format(Locale.ROOT, "%s 候选=%d (%s)", sb.toString().trim(), count, mode);
    }
}
