package com.yohu.lib_urlparse.sniff;

import java.util.Locale;

/**
 * URL 形态判定的小工具。
 *
 * <p><b>归属说明</b>：这不是从样本还原出来的规则，而是**本工程的新增辅助**——
 * {@link MediaUrlSniffer} 保持与转写一致（它要求 Content-Type 证据才接受非视频扩展名），
 * 而 WebView 的 {@code shouldInterceptRequest} 只给得到 URL、拿不到响应头，
 * 所以需要一个"这看起来像媒体吗"的形态判定，来决定**是否值得花一次 HTTP 探测**
 * （样本自己也这么干：报告 08 记录它用 {@code Util.getContent} 主动探 Content-Type）。
 * 把两者分开，是为了不把工程性判断混进已还原的规则里。
 */
public final class MediaExtensions {

    private MediaExtensions() {
    }

    private static final String[] VIDEO = {".mp4", ".m4v", ".mov", ".mkv", ".webm", ".flv",
            ".avi", ".ts", ".m3u8", ".mpd", ".f4v", ".wmv", ".3gp"};
    private static final String[] AUDIO = {".mp3", ".m4a", ".aac", ".wav", ".ogg", ".oga",
            ".flac", ".opus", ".wma"};
    private static final String[] IMAGE = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp",
            ".heic", ".avif"};

    /** 去掉查询串与片段后取小写路径。 */
    public static String pathOf(String url) {
        if (url == null) {
            return "";
        }
        String s = url.toLowerCase(Locale.ROOT);
        int h = s.indexOf('#');
        if (h >= 0) {
            s = s.substring(0, h);
        }
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        return s;
    }

    public static boolean looksLikeVideo(String url) {
        return endsWithAny(pathOf(url), VIDEO);
    }

    public static boolean looksLikeAudio(String url) {
        return endsWithAny(pathOf(url), AUDIO);
    }

    /**
     * 是否像图片。
     *
     * <p>除了路径扩展名，还认**查询参数里的类型标记**。原因是实测遇到的一种真实形态：
     * 微信公众号图床的正文图片是
     * {@code http://mmbiz.qpic.cn/mmbiz_png/<hash>/0?wx_fmt=png} ——
     * **路径上没有任何扩展名**，类型只写在 {@code wx_fmt=} 里。
     * 只按扩展名判断会把这些图全部当成"不像媒体"而不去探测，
     * 表现为"这篇公众号文章没有图片"。
     */
    public static boolean looksLikeImage(String url) {
        if (endsWithAny(pathOf(url), IMAGE)) {
            return true;
        }
        return queryHintsImage(url);
    }

    /**
     * 从查询参数里取**具体的**类型（不带点），取不到返回 null。
     *
     * <p>与 {@link #queryHintsImage(String)} 的区别：这里要回具体是 png 还是 jpg，
     * 用来决定文件扩展名——因为微信图床这类 URL 的路径上没有扩展名，
     * 只看路径会把 PNG 存成 {@code .jpg}（实测发生过）。
     */
    public static String queryHintExtension(String url) {
        if (url == null) {
            return null;
        }
        int q = url.indexOf('?');
        String query = (q >= 0 ? url.substring(q) : "").toLowerCase(Locale.ROOT);
        String path = pathOf(url).toLowerCase(Locale.ROOT);
        if (query.isEmpty() && !path.contains("/format/")) {
            return null;
        }
        String[] candidates = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic"};
        for (String ext : candidates) {
            String bare = ext.substring(1);
            if (query.contains("wx_fmt=" + bare)
                    || query.contains("format=" + bare)
                    || query.contains("format," + bare)
                    || query.contains("/format/" + bare)
                    || path.contains("/format/" + bare)) {
                return bare;
            }
        }
        return null;
    }

    /**
     * 查询串里是否带有图片类型标记。
     *
     * <p>覆盖实测/常见的几种：{@code wx_fmt=png}（微信图床）、
     * {@code format=jpg}、{@code imageView2/format/png}（七牛）、{@code x-oss-process=…jpg}。
     */
    public static boolean queryHintsImage(String url) {
        if (queryHintExtension(url) != null) {
            return true;
        }
        if (url == null) {
            return false;
        }
        int q = url.indexOf('?');
        String query = (q >= 0 ? url.substring(q) : "").toLowerCase(Locale.ROOT);
        return query.contains("imageview2") && containsAnyImageName(query);
    }

    private static boolean containsAnyImageName(String lower) {
        for (String ext : IMAGE) {
            if (lower.contains(ext.substring(1))) {
                return true;
            }
        }
        return false;
    }

    /** 是否像任何一种媒体（用于决定"值不值得探测"）。 */
    public static boolean looksLikeMedia(String url) {
        return looksLikeVideo(url) || looksLikeAudio(url) || looksLikeImage(url);
    }

    /**
     * 是否**明确不可能**是媒体（用于省下 Content-Type 探测预算）。
     *
     * <p>为什么需要：已还原的黑名单里没有 {@code .css}（只有 {@code .js}），
     * 于是样式表会以"缺 Content-Type 证据"进入待探测表，白白吃掉探测名额。
     * 实测快手 h5-share 页一次就有 30 个待探测地址、其中多个是 {@code .css}，
     * 结果 18 个因超出上限被跳过——**真正可能是媒体的地址反而没被探测**。
     *
     * <p>这是宿主侧的效率取舍（哪些值得花一次 HTTP 请求），不是对已还原规则的修改：
     * 这些扩展名在任何情况下都不会是音视频，探测它们只会得出"仍不接受"。
     */
    public static boolean looksLikeNonMedia(String url) {
        String p = pathOf(url);
        String[] exts = {".css", ".js", ".mjs", ".map", ".json", ".xml", ".txt", ".html",
                ".htm", ".svg", ".woff", ".woff2", ".ttf", ".otf", ".eot", ".ico", ".wasm",
                ".data", ".bin", ".php", ".asp", ".jsp"};
        return endsWithAny(p, exts);
    }

    /** 是否像播放列表（嗅探到它就等于拿到了完整视频，优先级最高）。 */
    public static boolean looksLikePlaylist(String url) {
        String p = pathOf(url);
        return p.endsWith(".m3u8") || p.endsWith(".m3u");
    }

    /** 该 URL 是否带扩展名（不带扩展名的媒体 URL 很常见，此时只能靠 Content-Type）。 */
    public static boolean hasExtension(String url) {
        String p = pathOf(url);
        int slash = p.lastIndexOf('/');
        String last = slash >= 0 ? p.substring(slash + 1) : p;
        int dot = last.lastIndexOf('.');
        return dot > 0 && dot < last.length() - 1 && last.length() - dot <= 6;
    }

    private static boolean endsWithAny(String s, String[] exts) {
        for (String e : exts) {
            if (s.endsWith(e)) {
                return true;
            }
        }
        return false;
    }
}

