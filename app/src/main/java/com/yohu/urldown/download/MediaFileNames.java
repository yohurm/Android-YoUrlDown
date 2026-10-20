package com.yohu.urldown.download;

import com.yohu.lib_urlparse.api.MediaAsset;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.lib_urlparse.sniff.MediaExtensions;

import java.util.Locale;

/**
 * 落盘文件名与真实扩展名判定。
 *
 * <p>解决的问题：
 * <ul>
 *   <li>URL 的"扩展名"经常是假的（{@code ~tplv-noop.image} 这种伪扩展名、CDN 的
 *       {@code /obj/xxx} 无扩展名、{@code ?format=webp} 藏在查询串里）；</li>
 *   <li>直接用作品标题做文件名会带 {@code / : * ? " < > |} 与超长文本，
 *       在部分设备上直接失败；</li>
 *   <li>同一条作品可能同时有视频与图集，文件名必须能区分且不互相覆盖。</li>
 * </ul>
 *
 * <p>纯函数，零 IO。
 */
public final class MediaFileNames {

    /** 文件名主干的最大字符数（不含扩展名）。留出空间给 {@code _1} 后缀与扩展名。 */
    private static final int MAX_STEM = 80;

    private MediaFileNames() {
    }

    /**
     * 为一条媒体推导文件名。
     *
     * @param result 解析结果（提供标题兜底）
     * @param asset  目标媒体
     * @param index  同一次下载中的序号（从 1 开始）；用于避免互相覆盖
     */
    public static String forAsset(UrlParseResult result, MediaAsset asset, int index) {
        String stem = sanitize(titleOf(result, asset));
        if (stem.isEmpty()) {
            stem = "media";
        }
        String ext = extensionFor(asset);
        String suffix = index > 1 ? "_" + index : "";
        return stem + suffix + "." + ext;
    }

    /** 标题优先用解析结果标题，其次用 URL 末段（去查询串）。 */
    static String titleOf(UrlParseResult result, MediaAsset asset) {
        if (result != null && result.title() != null && !result.title().trim().isEmpty()) {
            return result.title().trim();
        }
        String path = MediaExtensions.pathOf(asset == null ? null : asset.url());
        if (path != null) {
            int slash = path.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < path.length()) {
                return path.substring(slash + 1);
            }
        }
        return "media";
    }

    /**
     * 真实扩展名：优先 Content-Type，其次 URL 的真实后缀，最后按类型兜底。
     *
     * <p>绝不把伪扩展名当真：{@code ~tplv-noop.image} 会落到按类型的兜底值。
     */
    public static String extensionFor(MediaAsset asset) {
        if (asset == null) {
            return "bin";
        }
        String mime = asset.mimeType() == null ? "" : asset.mimeType().toLowerCase(Locale.ROOT);
        String fromMime = extFromMime(mime);
        if (fromMime != null) {
            return fromMime;
        }
        String url = asset.url();
        String ext = extensionFromUrl(url);
        if (ext != null) {
            return ext;
        }
        return defaultExtFor(asset.kind());
    }

    /** URL 里可信的扩展名；伪扩展名与无扩展名返回 {@code null}。 */
    public static String extensionFromUrl(String url) {
        String path = MediaExtensions.pathOf(url);
        if (path == null) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = last.lastIndexOf('.');
        if (dot <= 0 || dot == last.length() - 1) {
            return null;
        }
        String ext = last.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!isKnownExtension(ext)) {
            // 例如 "image" 这类伪扩展名：不可信
            return null;
        }
        return ext;
    }

    /** 可信的媒体扩展名白名单。 */
    public static boolean isKnownExtension(String ext) {
        switch (ext) {
            case "mp4":
            case "m4v":
            case "mov":
            case "webm":
            case "mkv":
            case "flv":
            case "avi":
            case "ts":
            case "m3u8":
            case "m4a":
            case "mp3":
            case "aac":
            case "wav":
            case "flac":
            case "ogg":
            case "jpg":
            case "jpeg":
            case "png":
            case "gif":
            case "webp":
            case "heic":
            case "bmp":
                return true;
            default:
                return false;
        }
    }

    private static String extFromMime(String mime) {
        if (mime.isEmpty()) {
            return null;
        }
        int semi = mime.indexOf(';');
        if (semi > 0) {
            mime = mime.substring(0, semi).trim();
        }
        switch (mime) {
            case "video/mp4":
                return "mp4";
            case "video/webm":
                return "webm";
            case "video/quicktime":
                return "mov";
            case "video/x-flv":
                return "flv";
            case "video/x-msvideo":
                return "avi";
            case "video/mp2t":
                return "ts";
            case "application/vnd.apple.mpegurl":
            case "application/x-mpegurl":
            case "audio/mpegurl":
            case "audio/x-mpegurl":
                return "m3u8";
            case "audio/mp4":
            case "audio/x-m4a":
                return "m4a";
            case "audio/mpeg":
                return "mp3";
            case "audio/aac":
                return "aac";
            case "image/jpeg":
                return "jpg";
            case "image/png":
                return "png";
            case "image/gif":
                return "gif";
            case "image/webp":
                return "webp";
            case "image/heic":
                return "heic";
            default:
                return null;
        }
    }

    private static String defaultExtFor(MediaAsset.Kind kind) {
        if (kind == null) {
            return "bin";
        }
        switch (kind) {
            case VIDEO:
                return "mp4";
            case AUDIO:
                return "m4a";
            case IMAGE:
            case COVER:
                return "jpg";
            default:
                return "bin";
        }
    }

    /** 替换（或补上）扩展名：{@code a.m3u8 -> a.ts}。 */
    public static String withExtension(String name, String ext) {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return stem + "." + ext;
    }

    /**
     * 清洗文件名主干：去掉路径分隔符与 Windows/Android 非法字符，压缩空白，限制长度。
     *
     * <p>保留中文（用户看得懂），只处理真正会导致写盘失败的字符。
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                continue;
            }
            switch (c) {
                case '/':
                case '\\':
                case ':':
                case '*':
                case '?':
                case '"':
                case '<':
                case '>':
                case '|':
                    sb.append('_');
                    break;
                default:
                    sb.append(c);
            }
        }
        // 压缩连续空白/下划线
        String out = sb.toString().replaceAll("\\s+", " ").replaceAll("_{2,}", "_").trim();
        // 去掉结尾的点与空格（Windows 上非法）
        while (out.endsWith(".") || out.endsWith(" ")) {
            out = out.substring(0, out.length() - 1);
        }
        if (out.length() > MAX_STEM) {
            out = out.substring(0, MAX_STEM).trim();
        }
        return out;
    }
}
