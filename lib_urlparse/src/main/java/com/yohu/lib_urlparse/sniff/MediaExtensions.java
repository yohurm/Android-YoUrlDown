package com.yohu.lib_urlparse.sniff;

import java.util.Locale;

/**
 * 按 URL 形态判断“像不像视频 / 音频 / 图片 / 播放列表”。
 *
 * <p>只看地址（路径扩展名 + 查询串里的类型标记），不发请求。用于：
 * 嗅探时决定一个地址值不值得收、落盘时推断扩展名。
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

    /** 是否像播放列表（嗅探到它就等于拿到了完整视频，优先级最高）。 */
    public static boolean looksLikePlaylist(String url) {
        String p = pathOf(url);
        return p.endsWith(".m3u8") || p.endsWith(".m3u");
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

