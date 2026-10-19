package com.yohu.lib_urlparse.rules;


import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Instagram 抽取规则。
 *
 * <p><b>溯源</b>：样本对 Instagram 只留了平台名与一个示例链接
 * （{@code https://www.instagram.com/p/Ca3V3zGIZ08/}，见报告 11 §11.1 与 16 §16.2），
 * 没有可还原的解析规则。因此这里走 **Instagram 自己的公开嵌入页**
 * （{@code /p/<code>/embed/captioned/}）——它是站点为"在别的网页里展示这个帖子"而提供的
 * 公开接口，无需登录、无需任何凭据。这是本工程自行选择的机制，不是从样本还原的。
 *
 * <p><b>形态说明</b>：样本把 Instagram 归类为"图集"（证据字面量 {@code instagram图集}），
 * 因此本规则对**轮播（sidecar）**与单图/单视频都做覆盖。
 */
public final class InstagramRules {

    private InstagramRules() {
    }

    public static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; NEPI4E0106) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36";

    /** 短码：/p/<code>/、/reel/<code>/、/reels/<code>/、/tv/<code>/。 */
    public static final Pattern PATTERN_SHORTCODE =
            Pattern.compile("instagram\\.com/(?:p|reel|reels|tv)/([A-Za-z0-9_-]{5,20})");

    public static boolean isInstagramHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase(java.util.Locale.ROOT);
        return h.equals("instagram.com") || h.endsWith(".instagram.com")
                || h.equals("instagr.am") || h.endsWith(".instagr.am");
    }

    public static String extractShortcode(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = PATTERN_SHORTCODE.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** 公开嵌入页地址（带 captioned 的版本信息更全）。 */
    public static String embedUrl(String shortcode) {
        return "https://www.instagram.com/p/" + shortcode + "/embed/captioned/";
    }

    /** 备用嵌入页地址。 */
    public static String embedUrlPlain(String shortcode) {
        return "https://www.instagram.com/p/" + shortcode + "/embed/";
    }

    // ---------------------------------------------------------------- 页面抽取

    /**
     * 从嵌入页 HTML 里抽媒体。
     *
     * <p>分层（每层都记进 layers，便于判断"页面没数据"还是"字段改名"）：
     * <ol>
     *   <li>轮播：{@code edge_sidecar_to_children} 里的每个子项（样本称之为"图集"）；</li>
     *   <li>{@code "video_url"} —— 视频直链；</li>
     *   <li>{@code "display_url"} 或 {@code <img class="EmbeddedMediaImage" src=…>} —— 图片直链。</li>
     * </ol>
     */
    public static ParseOutcome extract(String html) {
        ParseOutcome out = new ParseOutcome();
        if (html == null || html.isEmpty()) {
            out.layers = "空页面";
            return out;
        }
        boolean hasSidecar = html.contains("edge_sidecar_to_children");
        boolean hasVideoUrl = html.contains("video_url");
        boolean hasDisplayUrl = html.contains("display_url");
        boolean hasEmbedImg = html.contains("EmbeddedMediaImage");

        // ① 首选：把页面里的 JSON 数据块**真正解析**出来再遍历。
        // 为什么不用正则切片：轮播子项是数组，用 "}]" 之类的粗切会把整段当成一项
        // （实测踩到了：3 个子项只认出 1 个）。结构化解析才靠得住。
        boolean jsonOk = false;
        String jsonBlob = findJsonBlob(html);
        if (jsonBlob != null) {
            try {
                jsonOk = walkShortcodeMedia(new org.json.JSONObject(jsonBlob), out);
            } catch (org.json.JSONException e) {
                jsonOk = false;
            }
        }
        // ② 退化：正则取单值（JSON 被拆散或结构变化时仍可能捞到东西）
        if (!jsonOk) {
            if (out.videos.isEmpty() && hasVideoUrl) {
                String v = decodeEntities(firstJsonString(html, "video_url"));
                if (v != null && v.startsWith("http")) {
                    out.videos.add(v);
                }
            }
            if (out.images.isEmpty() && hasDisplayUrl) {
                String d = decodeEntities(firstJsonString(html, "display_url"));
                if (d != null && d.startsWith("http")) {
                    out.images.add(d);
                }
            }
            if (!hasSidecar && out.images.isEmpty() && hasEmbedImg) {
                Matcher m = Pattern.compile(
                        "class=\"EmbeddedMediaImage\"[^>]*\\ssrc=\"([^\"]+)\"").matcher(html);
                if (m.find()) {
                    out.images.add(decodeEntities(unescape(m.group(1))));
                }
                // 作者名就在这张图的 alt 里（真实页面实测：alt="Instagram post shared by &#064;xxx"）
                Matcher am = Pattern.compile(
                        "class=\"EmbeddedMediaImage\"[^>]*\\salt=\"([^\"]*)\"").matcher(html);
                if (am.find() && out.ownerName == null) {
                    out.ownerName = authorFromAlt(decodeEntities(am.group(1)));
                }
            }
        }
        // ③ 最后兜底：og:video / og:image
        if (out.mediaCount() == 0) {
            String ogv = decodeEntities(firstMeta(html, "og:video"));
            if (ogv != null) {
                out.videos.add(ogv);
            }
            String ogi = decodeEntities(firstMeta(html, "og:image"));
            if (ogi != null) {
                out.images.add(ogi);
            }
        }
        if (out.title == null) {
            out.title = firstJsonString(html, "caption");
        }
        if (out.title == null) {
            Matcher m = Pattern.compile(
                    "class=\"Caption\"[^>]*>(.*?)</div>", Pattern.DOTALL).matcher(html);
            if (m.find()) {
                out.title = stripTags(m.group(1)).trim();
            }
        }
        if (out.ownerName == null) {
            out.ownerName = firstJsonString(html, "username");
        }
        out.layers = "json=" + jsonOk + " sidecar=" + hasSidecar + " video_url=" + hasVideoUrl
                + " display_url=" + hasDisplayUrl + " embedImg=" + hasEmbedImg
                + " 视频=" + out.videos.size() + " 图片=" + out.images.size()
                + (html.contains("LoginAndSignupPage") || html.contains("loginForm")
                        ? " [疑似要求登录]" : "");
        return out;
    }

    /** 在页面里找含 shortcode_media 的 JSON 数据块（花括号配平、字符串感知）。 */
    public static String findJsonBlob(String html) {
        int at = html.indexOf("shortcode_media");
        while (at >= 0) {
            int start = -1;
            for (int i = at; i >= 0; i--) {
                if (html.charAt(i) == '{') {
                    start = i;
                    break;
                }
                if (html.charAt(i) == '>') {
                    break;      // 越过了标签边界：这个 key 不在 JSON 里
                }
            }
            if (start >= 0) {
                String blob = balancedObject(html, start);
                if (blob != null && blob.contains("shortcode_media")) {
                    return blob;
                }
            }
            at = html.indexOf("shortcode_media", at + 1);
        }
        return null;
    }

    static String balancedObject(String s, int start) {
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
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

    /** 遍历结构化数据；找到任何媒体即返回 true。 */
    static boolean walkShortcodeMedia(org.json.JSONObject root, ParseOutcome out) {
        org.json.JSONObject media = root.optJSONObject("shortcode_media");
        if (media == null) {
            org.json.JSONObject g = root.optJSONObject("graphql");
            if (g != null) {
                media = g.optJSONObject("shortcode_media");
            }
        }
        if (media == null) {
            media = deepFindMedia(root, 0);
        }
        if (media == null) {
            return false;
        }
        org.json.JSONObject sidecar = media.optJSONObject("edge_sidecar_to_children");
        if (sidecar != null) {
            org.json.JSONArray edges = sidecar.optJSONArray("edges");
            for (int i = 0; edges != null && i < edges.length(); i++) {
                org.json.JSONObject e = edges.optJSONObject(i);
                addNode(e == null ? null : e.optJSONObject("node"), out);
            }
        }
        addNode(media, out);
        org.json.JSONObject owner = media.optJSONObject("owner");
        if (owner != null) {
            out.ownerName = owner.optString("username", null);
        }
        org.json.JSONObject capEdge = media.optJSONObject("edge_media_to_caption");
        if (capEdge != null && capEdge.optJSONArray("edges") != null
                && capEdge.optJSONArray("edges").length() > 0) {
            org.json.JSONObject n = capEdge.optJSONArray("edges").optJSONObject(0);
            if (n != null && n.optJSONObject("node") != null) {
                out.title = n.optJSONObject("node").optString("text", null);
            }
        }
        return out.mediaCount() > 0;
    }

    private static void addNode(org.json.JSONObject node, ParseOutcome out) {
        if (node == null) {
            return;
        }
        String v = node.optString("video_url", null);
        if (v != null && v.startsWith("http")) {
            if (!out.videos.contains(v)) {
                out.videos.add(v);
            }
            return;
        }
        String d = node.optString("display_url", null);
        if (d != null && d.startsWith("http") && !out.images.contains(d)) {
            out.images.add(d);
        }
    }

    private static org.json.JSONObject deepFindMedia(Object node, int depth) {
        if (depth > 10 || node == null) {
            return null;
        }
        if (node instanceof org.json.JSONArray) {
            org.json.JSONArray a = (org.json.JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject r = deepFindMedia(a.opt(i), depth + 1);
                if (r != null) {
                    return r;
                }
            }
            return null;
        }
        if (!(node instanceof org.json.JSONObject)) {
            return null;
        }
        org.json.JSONObject o = (org.json.JSONObject) node;
        if (o.opt("video_url") != null || o.opt("display_url") != null) {
            return o;
        }
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
            Object v = o.opt(it.next());
            if (v instanceof org.json.JSONObject || v instanceof org.json.JSONArray) {
                org.json.JSONObject r = deepFindMedia(v, depth + 1);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /** 抽一条 {@code "key":"value"} 里的字符串值（去转义）。 */
    public static String firstJsonString(String text, String key) {
        if (text == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(text);
        return m.find() ? unescape(m.group(1)) : null;
    }

    private static String firstMeta(String html, String property) {
        Matcher m = Pattern.compile("<meta[^>]+property=\"" + Pattern.quote(property)
                + "\"[^>]+content=\"([^\"]+)\"").matcher(html);
        if (m.find()) {
            return unescape(m.group(1));
        }
        Matcher m2 = Pattern.compile("<meta[^>]+content=\"([^\"]+)\"[^>]+property=\""
                + Pattern.quote(property) + "\"").matcher(html);
        return m2.find() ? unescape(m2.group(1)) : null;
    }

    /** 取 key 之后若干段（以 {@code }} 粗略切分），用于轮播子项。 */
    static List<String> segmentsAfter(String html, String key, int max) {
        List<String> out = new ArrayList<>();
        int at = html.indexOf(key);
        while (at >= 0 && out.size() < max) {
            int end = html.indexOf("}]", at);
            if (end < 0) {
                break;
            }
            out.add(html.substring(at, Math.min(html.length(), end + 2)));
            at = html.indexOf(key, end);
        }
        return out;
    }

    /**
     * 解 HTML 实体。**必须做**：真实嵌入页把媒体地址写成
     * {@code src="https://…jpg?stp=…&amp;_nc_cat=110&amp;…"}——
     * 不还原 {@code &amp;} 就会把带 {@code &amp;} 的地址丢给下载器，轻则参数失效、重则取不到图。
     * 这是对着真实页面才发现的问题，合成样例里不会出现。
     */
    public static String decodeEntities(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s;
        }
        return s.replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#064;", "@").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&#x2F;", "/")
                .replace("&#47;", "/");
    }

    /** 从 {@code alt="Instagram post shared by @xxx"} 里取作者名（自带实体还原）。 */
    public static String authorFromAlt(String alt) {
        if (alt == null) {
            return null;
        }
        // 自带解码：真实页面把 @ 写成 &#064;，调用方漏解就取不到作者。
        // 让这个函数自己负责，免得依赖调用顺序。
        Matcher m = Pattern.compile("shared by\\s+@?([A-Za-z0-9._]+)")
                .matcher(decodeEntities(alt));
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    public static String unescape(String s) {
        if (s == null) {
            return null;
        }
        return s.replace("\\u002F", "/").replace("\\u002f", "/")
                .replace("\\/", "/").replace("\\\"", "\"")
                .replace("\\n", "\n").replace("\\t", " ");
    }

    public static String stripTags(String s) {
        if (s == null) {
            return null;
        }
        return s.replaceAll("<[^>]+>", " ");
    }

    /** 抽取结果（纯数据，便于单测）。 */
    public static final class ParseOutcome {
        public final List<String> videos = new ArrayList<>();
        public final List<String> images = new ArrayList<>();
        public String title;
        public String ownerName;
        public String layers;

        public int mediaCount() {
            return videos.size() + images.size();
        }

        /** 去重后的全部媒体（视频在前）。 */
        public List<String> allMedia() {
            LinkedHashSet<String> set = new LinkedHashSet<>(videos);
            set.addAll(images);
            return new ArrayList<>(set);
        }
    }
}



