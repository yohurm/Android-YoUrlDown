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
 * 小红书抽取规则。
 *
 * <p><b>参考实现</b>：字段路径（{@code video.consumer.originVideoKey}、
 * {@code video.media.stream.*[].masterUrl/backupUrls}、{@code imageList[].urlDefault}
 * 及其 token 提取方式）对齐公开维护的 {@code JoeanAmier/XHS-Downloader}
 * （{@code source/application/video.py}、{@code image.py}、{@code explore.py}）。
 *
 * <p><b>一个已知风险</b>：该实现用 {@code curl_cffi} 且指定 {@code impersonate}——
 * 也就是**伪造浏览器 TLS 指纹**。这说明小红书很可能对 TLS/HTTP2 指纹有校验，
 * 而本工程用的是 JDK 自带的 {@code HttpURLConnection}，没有指纹伪装能力。
 * 若实测被拦（风控页/空数据），必须如实报告为**能力受限**，而不是绕过去。
 */
public final class XiaohongshuRules {

    private XiaohongshuRules() {
    }

    // ---------------------------------------------------------------- 主机与链接

    public static final String REFERER = "https://www.xiaohongshu.com/";
    public static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; NEPI4E0106) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36";

    /** 图片/视频基础域名（来自参考实现的 generate_*_link）。 */
    public static final String IMAGE_BASE = "https://sns-img-bd.xhscdn.com/";
    public static final String VIDEO_BASE = "https://sns-video-bd.xhscdn.com/";

    public static final Pattern PATTERN_NOTE_ID =
            Pattern.compile("(?:explore|discovery/item|note)/([0-9a-fA-F]{16,32})");

    /** 数据变量名（实测哪些存在由宿主记录，见 layers 诊断）。 */
    public static final String[] DATA_VARS = {
            "__INITIAL_STATE__", "_INITIAL_STATE_", "__NEXT_DATA__"
    };

    public static boolean isXhsHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        return h.equals("xiaohongshu.com") || h.endsWith(".xiaohongshu.com")
                || h.equals("xhslink.com") || h.endsWith(".xhslink.com")
                || h.equals("xhs.cn") || h.endsWith(".xhs.cn");
    }

    public static boolean isShortLinkHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        return h.equals("xhslink.com") || h.endsWith(".xhslink.com")
                || h.equals("xhs.cn") || h.endsWith(".xhs.cn");
    }

    /** 从链接取笔记 ID；取不到返回 null。 */
    public static String extractNoteId(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = PATTERN_NOTE_ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    // ---------------------------------------------------------------- 页面数据

    /**
     * 把页面里 JS 对象文本修成可解析的 JSON。
     *
     * <p>小红书的 {@code __INITIAL_STATE__} 里会出现裸 {@code undefined}
     * （JS 合法、JSON 非法），直接 {@code JSONObject} 会解析失败。
     * 参考实现用 Python 的对象模型绕开；本工程用 JSON，因此必须显式替换。
     * 只替换"值位置上的 undefined"，不动字符串内部。
     */
    public static String normalizeJsObject(String js) {
        if (js == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(js.length());
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < js.length(); i++) {
            char c = js.charAt(i);
            if (inStr) {
                sb.append(c);
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
                sb.append(c);
                continue;
            }
            if (c == 'u' && js.startsWith("undefined", i) && isValuePosition(sb)) {
                sb.append("null");
                i += "undefined".length() - 1;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 前一个非空白字符是否表示"这里该出值"（: 、[ 、, 之一）。 */
    static boolean isValuePosition(StringBuilder soFar) {
        for (int i = soFar.length() - 1; i >= 0; i--) {
            char c = soFar.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            return c == ':' || c == '[' || c == ',';
        }
        return false;
    }

    /** 从 {@code <script>…</script>} 或赋值语句里截取变量对应的对象文本。 */
    public static String extractObjectText(String html, String varName) {
        if (html == null || varName == null) {
            return null;
        }
        int at = html.indexOf(varName);
        while (at >= 0) {
            int brace = html.indexOf('{', at);
            if (brace < 0) {
                return null;
            }
            String slice = balancedObject(html, brace);
            if (slice != null) {
                return slice;
            }
            at = html.indexOf(varName, at + varName.length());
        }
        return null;
    }

    /** 从 {@code start} 的 {@code {} 起截取配对内容（考虑字符串与转义）。 */
    public static String balancedObject(String s, int start) {
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

    /**
     * 从 {@code __INITIAL_STATE__} 里找笔记对象。
     *
     * <p>先走参考实现用到的确定路径 {@code note.noteDetailMap.<id>.note}；
     * 找不到则退到深递归（页面结构改名时仍可能命中），这是本工程加的兜底。
     */
    public static JSONObject findNote(JSONObject root, String noteId) {
        if (root == null) {
            return null;
        }
        JSONObject note = root.optJSONObject("note");
        if (note != null) {
            JSONObject map = note.optJSONObject("noteDetailMap");
            if (map != null) {
                if (noteId != null) {
                    JSONObject entry = map.optJSONObject(noteId);
                    if (entry != null && entry.optJSONObject("note") != null) {
                        return entry.optJSONObject("note");
                    }
                }
                // 没给 ID 或没命中：取 map 里第一个带 note 的条目
                for (Iterator<String> it = map.keys(); it.hasNext(); ) {
                    JSONObject entry = map.optJSONObject(it.next());
                    if (entry != null && entry.optJSONObject("note") != null) {
                        return entry.optJSONObject("note");
                    }
                }
            }
        }
        return deepFindNote(root, 0);
    }

    static JSONObject deepFindNote(Object node, int depth) {
        if (depth > 12 || node == null) {
            return null;
        }
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = deepFindNote(arr.opt(i), depth + 1);
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
        boolean hasId = o.opt("noteId") != null || o.opt("note_id") != null;
        boolean hasMedia = o.optJSONArray("imageList") != null
                || o.optJSONObject("video") != null;
        if (hasId && hasMedia) {
            return o;
        }
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            Object v = o.opt(it.next());
            if (v instanceof JSONObject || v instanceof JSONArray) {
                JSONObject r = deepFindNote(v, depth + 1);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 字段

    public static String noteId(JSONObject note) {
        if (note == null) {
            return null;
        }
        String v = note.optString("noteId", null);
        if (v == null || v.isEmpty()) {
            v = note.optString("note_id", null);
        }
        if (v == null || v.isEmpty()) {
            v = note.optString("id", null);
        }
        return (v == null || v.isEmpty()) ? null : v;
    }

    public static String title(JSONObject note) {
        if (note == null) {
            return null;
        }
        String t = note.optString("title", null);
        if (t == null || t.trim().isEmpty()) {
            t = note.optString("desc", null);
        }
        return (t == null || t.trim().isEmpty()) ? null : t.trim();
    }

    public static String authorName(JSONObject note) {
        JSONObject u = note == null ? null : note.optJSONObject("user");
        if (u == null) {
            return null;
        }
        String n = u.optString("nickname", null);
        if (n == null || n.isEmpty()) {
            n = u.optString("nickName", null);
        }
        return (n == null || n.isEmpty()) ? null : n;
    }

    public static String authorId(JSONObject note) {
        JSONObject u = note == null ? null : note.optJSONObject("user");
        return u == null ? null : u.optString("userId", null);
    }

    public static String noteType(JSONObject note) {
        return note == null ? null : note.optString("type", null);
    }

    /**
     * 图片直链。
     *
     * <p>对齐参考实现：优先 {@code urlDefault}，缺失时用 {@code url}；
     * 再从 URL 里抽出 token（{@code "/".join(url.split("/")[5:]).split("!")[0]}），
     * 拼成 {@code https://sns-img-bd.xhscdn.com/<token>}——这样能去掉原 URL 上的
     * 裁剪/水印类查询串，拿到底图。
     */
    public static List<String> imageUrls(JSONObject note) {
        List<String> out = new ArrayList<>();
        JSONArray list = note == null ? null : note.optJSONArray("imageList");
        if (list == null) {
            return out;
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject img = list.optJSONObject(i);
            if (img == null) {
                continue;
            }
            String raw = img.optString("urlDefault", null);
            if (raw == null || raw.isEmpty()) {
                raw = img.optString("url", null);
            }
            String url = imageTokenToUrl(raw);
            if (url != null) {
                out.add(url);
            }
        }
        return out;
    }

    /** 参考实现的 token 规则：取第 6 段起的路径，并在 {@code !} 处截断。 */
    public static String imageToken(String rawUrl) {
        if (rawUrl == null || rawUrl.isEmpty()) {
            return null;
        }
        String u = unescape(rawUrl);
        String[] parts = u.split("/");
        if (parts.length <= 5) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 5; i < parts.length; i++) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(parts[i]);
        }
        String token = sb.toString();
        int bang = token.indexOf('!');
        if (bang >= 0) {
            token = token.substring(0, bang);
        }
        return token.isEmpty() ? null : token;
    }

    public static String imageTokenToUrl(String rawUrl) {
        String token = imageToken(rawUrl);
        return token == null ? null : IMAGE_BASE + token;
    }

    /**
     * 视频直链：优先 {@code video.consumer.originVideoKey}（参考实现的首选路径），
     * 否则从 {@code video.media.stream.*[]} 里按分辨率挑最高的一条。
     */
    public static String videoUrl(JSONObject note) {
        JSONObject video = note == null ? null : note.optJSONObject("video");
        if (video == null) {
            return null;
        }
        JSONObject consumer = video.optJSONObject("consumer");
        if (consumer != null) {
            String key = consumer.optString("originVideoKey", null);
            if (key != null && !key.isEmpty() && !"null".equals(key)) {
                return VIDEO_BASE + unescape(key);
            }
        }
        JSONObject best = bestStream(video);
        if (best == null) {
            return null;
        }
        return firstUrl(best);
    }

    /** 在 {@code video.media.stream} 的所有档位里按 height 选最高。 */
    public static JSONObject bestStream(JSONObject video) {
        JSONObject media = video.optJSONObject("media");
        JSONObject stream = media == null ? null : media.optJSONObject("stream");
        if (stream == null) {
            return null;
        }
        JSONObject best = null;
        int bestHeight = -1;
        for (Iterator<String> keys = stream.keys(); keys.hasNext(); ) {
            JSONArray arr = stream.optJSONArray(keys.next());
            if (arr == null) {
                continue;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                int h = item.optInt("height", 0);
                if (h > bestHeight) {
                    bestHeight = h;
                    best = item;
                }
            }
        }
        return best;
    }

    /** 参考实现：优先 {@code backupUrls[0]}，否则 {@code masterUrl}。 */
    public static String firstUrl(JSONObject item) {
        if (item == null) {
            return null;
        }
        JSONArray backups = item.optJSONArray("backupUrls");
        if (backups != null && backups.length() > 0) {
            String b = unescape(backups.optString(0, null));
            if (b != null && b.startsWith("http")) {
                return b;
            }
        }
        String m = unescape(item.optString("masterUrl", null));
        return (m != null && m.startsWith("http")) ? m : null;
    }

    /** 参考实现的 {@code format_url}：把 {@code \u002F} 之类转义还原。 */
    public static String unescape(String s) {
        if (s == null || s.indexOf('\\') < 0) {
            return s;
        }
        return s.replace("\\u002F", "/").replace("\\u002f", "/").replace("\\/", "/");
    }

    /** 页面里是否出现某数据变量（诊断用）。 */
    public static boolean mentionsVar(String html, String varName) {
        return html != null && html.contains(varName);
    }

    /** 诊断摘要：哪些变量存在、命中哪一层。 */
    public static String layerSummary(String html, String varUsed, int mediaCount) {
        StringBuilder sb = new StringBuilder();
        for (String v : DATA_VARS) {
            sb.append(v).append('=').append(mentionsVar(html, v) ? 'Y' : 'N').append(' ');
        }
        return sb.toString().trim() + " 命中=" + (varUsed == null ? "无" : varUsed)
                + " 媒体=" + mediaCount
                + (html != null && html.contains("captcha") ? " [疑似风控页]" : "");
    }
}
