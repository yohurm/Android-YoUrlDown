package com.yohu.lib_urlparse.transfer;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * M3U8 播放列表解析（纯逻辑，可单测；不做任何网络访问）。
 *
 * <p>样本的功能清单里有 {@code m3u8合并下载}，而 m3u8 是"嗅探到的流"最常见的形态，
 * 因此这里是嗅探链路的必要配套。支持：
 * <ul>
 *   <li><b>master playlist</b>：{@code #EXT-X-STREAM-INF} + 下一行的变体 URI（含 BANDWIDTH/RESOLUTION）；</li>
 *   <li><b>media playlist</b>：{@code #EXTINF} + 分片 URI、{@code #EXT-X-BYTERANGE}；</li>
 *   <li><b>加密</b>：{@code #EXT-X-KEY} 的 {@code METHOD=AES-128} / {@code URI} / {@code IV}
 *       （{@code METHOD=NONE} 表示清除密钥；其它 METHOD 记为不支持而不是静默忽略）；</li>
 *   <li>相对 URI 一律按播放列表自身的 URL 解析（{@code resolve}）。</li>
 * </ul>
 */
public final class M3u8Playlist {

    /** 一个分片。 */
    public static final class Segment {
        public final String uri;
        public final double durationSeconds;
        /** 字节范围长度；0 表示未指定。 */
        public final long byteRangeLength;
        /** 字节范围起始；-1 表示由上一段续接或未指定。 */
        public final long byteRangeStart;
        public final String keyUri;
        public final byte[] keyIv;
        public final String keyMethod;

        Segment(String uri, double durationSeconds, long byteRangeLength, long byteRangeStart,
                String keyUri, byte[] keyIv, String keyMethod) {
            this.uri = uri;
            this.durationSeconds = durationSeconds;
            this.byteRangeLength = byteRangeLength;
            this.byteRangeStart = byteRangeStart;
            this.keyUri = keyUri;
            this.keyIv = keyIv;
            this.keyMethod = keyMethod;
        }

        public boolean encrypted() {
            return keyUri != null && "AES-128".equalsIgnoreCase(keyMethod);
        }

        @Override
        public String toString() {
            return uri + "  (" + durationSeconds + "s"
                    + (byteRangeLength > 0 ? ", range=" + byteRangeStart + "+" + byteRangeLength : "")
                    + (encrypted() ? ", AES-128" : "") + ")";
        }
    }

    /** master playlist 里的一个变体。 */
    public static final class Variant {
        public final String uri;
        public final long bandwidth;
        public final String resolution;
        public final String codecs;

        Variant(String uri, long bandwidth, String resolution, String codecs) {
            this.uri = uri;
            this.bandwidth = bandwidth;
            this.resolution = resolution;
            this.codecs = codecs;
        }

        @Override
        public String toString() {
            return uri + "  bw=" + bandwidth + (resolution != null ? "  " + resolution : "");
        }
    }

    private final String baseUrl;
    private final List<Variant> variants;
    private final List<Segment> segments;
    private final boolean endList;
    private final double targetDuration;
    private final boolean master;
    private final List<String> warnings;

    private M3u8Playlist(String baseUrl, List<Variant> variants, List<Segment> segments,
                        boolean endList, double targetDuration, List<String> warnings) {
        this.baseUrl = baseUrl;
        this.variants = Collections.unmodifiableList(variants);
        this.segments = Collections.unmodifiableList(segments);
        this.endList = endList;
        this.targetDuration = targetDuration;
        this.warnings = Collections.unmodifiableList(warnings);
        this.master = !variants.isEmpty() && segments.isEmpty();
    }

    public String baseUrl() {
        return baseUrl;
    }

    public boolean isMaster() {
        return master;
    }

    public List<Variant> variants() {
        return variants;
    }

    public List<Segment> segments() {
        return segments;
    }

    public boolean hasEndList() {
        return endList;
    }

    public double targetDurationSeconds() {
        return targetDuration;
    }

    /** 解析过程中发现的、不致命但值得报告的问题（例如不支持的加密方式）。 */
    public List<String> warnings() {
        return warnings;
    }

    public double totalDurationSeconds() {
        double t = 0;
        for (Segment s : segments) {
            t += s.durationSeconds;
        }
        return t;
    }

    /** 选码率最高的变体；没有变体时返回 null。 */
    public Variant bestVariant() {
        Variant best = null;
        for (Variant v : variants) {
            if (best == null || v.bandwidth > best.bandwidth) {
                best = v;
            }
        }
        return best;
    }

    /**
     * 解析播放列表。
     *
     * @param text    播放列表文本
     * @param baseUrl 该文本的来源 URL（用于把相对 URI 变成绝对 URI）；可为 null
     */
    public static M3u8Playlist parse(String text, String baseUrl) {
        List<Variant> variants = new ArrayList<>();
        List<Segment> segments = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean endList = false;
        double targetDuration = 0;

        if (text == null || text.trim().isEmpty()) {
            warnings.add("playlist is empty");
            return new M3u8Playlist(baseUrl, variants, segments, false, 0, warnings);
        }

        // 当前生效的密钥（EXT-X-KEY 对其后的所有分片生效，直到被改写）
        String curKeyUri = null;
        byte[] curKeyIv = null;
        String curKeyMethod = null;

        double pendingDuration = 0;
        long pendingRangeLength = 0;
        long pendingRangeStart = -1;
        boolean sawExtinf = false;
        // 必须记住"最后一个分片是否带 EXTINF"。每加一个分片都把 sawExtinf 清零，
        // 因此循环结束后这个标志只反映"最后一段"，不能再用它判断历史分片。
        boolean lastSegmentHadExtinf = true;
        String pendingStreamInf = null;

        String[] lines = text.split("\r?\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                if (line.startsWith("#EXT-X-KEY:")) {
                    String attrs = line.substring("#EXT-X-KEY:".length());
                    String method = attr(attrs, "METHOD");
                    if (method == null || "NONE".equalsIgnoreCase(method)) {
                        curKeyUri = null;
                        curKeyIv = null;
                        curKeyMethod = null;
                    } else {
                        if (!"AES-128".equalsIgnoreCase(method)) {
                            warnings.add("unsupported key method: " + method
                                    + " (only AES-128 is implemented)");
                        }
                        curKeyMethod = method;
                        curKeyUri = absolutize(attr(attrs, "URI"), baseUrl);
                        String iv = attr(attrs, "IV");
                        curKeyIv = parseIv(iv);
                    }
                } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    pendingStreamInf = line.substring("#EXT-X-STREAM-INF:".length());
                } else if (line.startsWith("#EXTINF:")) {
                    String v = line.substring("#EXTINF:".length());
                    int comma = v.indexOf(',');
                    if (comma >= 0) {
                        v = v.substring(0, comma);
                    }
                    try {
                        pendingDuration = Double.parseDouble(v.trim());
                    } catch (NumberFormatException e) {
                        pendingDuration = 0;
                    }
                    sawExtinf = true;
                } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                    String v = line.substring("#EXT-X-BYTERANGE:".length()).trim();
                    int at = v.indexOf('@');
                    try {
                        if (at >= 0) {
                            pendingRangeLength = Long.parseLong(v.substring(0, at));
                            pendingRangeStart = Long.parseLong(v.substring(at + 1));
                        } else {
                            pendingRangeLength = Long.parseLong(v);
                            pendingRangeStart = -1;
                        }
                    } catch (NumberFormatException e) {
                        pendingRangeLength = 0;
                        pendingRangeStart = -1;
                    }
                } else if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                    try {
                        targetDuration = Double.parseDouble(
                                line.substring("#EXT-X-TARGETDURATION:".length()).trim());
                    } catch (NumberFormatException ignored) {
                        // target duration 只是提示，解析失败不影响正确性
                    }
                } else if (line.startsWith("#EXT-X-ENDLIST")) {
                    endList = true;
                }
                continue;
            }

            // 非 # 开头 → URI 行
            String abs = absolutize(line, baseUrl);
            if (pendingStreamInf != null) {
                String attrs = pendingStreamInf;
                pendingStreamInf = null;
                long bw = 0;
                String bwRaw = attr(attrs, "BANDWIDTH");
                if (bwRaw != null) {
                    try {
                        bw = Long.parseLong(bwRaw.trim());
                    } catch (NumberFormatException ignored) {
                        bw = 0;
                    }
                }
                variants.add(new Variant(abs, bw, attr(attrs, "RESOLUTION"), attr(attrs, "CODECS")));
                continue;
            }
            segments.add(new Segment(abs, pendingDuration, pendingRangeLength, pendingRangeStart,
                    curKeyUri, curKeyIv, curKeyMethod));
            lastSegmentHadExtinf = sawExtinf;
            pendingDuration = 0;
            pendingRangeLength = 0;
            pendingRangeStart = -1;
            sawExtinf = false;
        }

        if (!segments.isEmpty() && !lastSegmentHadExtinf) {
            // 有些列表最后一个分片不带 EXTINF；不算错，但值得记一笔
            warnings.add("last segment had no EXTINF (duration unknown)");
        }
        return new M3u8Playlist(baseUrl, variants, segments, endList, targetDuration, warnings);
    }

    /** 解析 {@code KEYFORMAT} 之外的属性值：{@code NAME=VALUE} 或 {@code NAME="VALUE"}。 */
    static String attr(String attrs, String name) {
        if (attrs == null) {
            return null;
        }
        int i = 0;
        int n = attrs.length();
        while (i < n) {
            // 跳过逗号与空白
            while (i < n && (attrs.charAt(i) == ',' || attrs.charAt(i) == ' ')) {
                i++;
            }
            int eq = attrs.indexOf('=', i);
            if (eq < 0) {
                return null;
            }
            String key = attrs.substring(i, eq).trim();
            int vStart = eq + 1;
            String value;
            if (vStart < n && attrs.charAt(vStart) == '"') {
                int close = attrs.indexOf('"', vStart + 1);
                if (close < 0) {
                    return null;
                }
                value = attrs.substring(vStart + 1, close);
                i = close + 1;
            } else {
                int comma = attrs.indexOf(',', vStart);
                if (comma < 0) {
                    value = attrs.substring(vStart);
                    i = n;
                } else {
                    value = attrs.substring(vStart, comma);
                    i = comma;
                }
            }
            if (name.equals(key)) {
                return value.trim();
            }
        }
        return null;
    }

    static byte[] parseIv(String iv) {
        if (iv == null) {
            return null;
        }
        String s = iv.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        if (s.length() > 32) {
            s = s.substring(s.length() - 32);
        }
        while (s.length() < 32) {
            s = "0" + s;
        }
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** m3u8 规定：未给 IV 时用分片序号（从 0 开始的 16 字节大端）作为 IV。 */
    public static byte[] sequenceIv(long sequence) {
        byte[] iv = new byte[16];
        for (int i = 15; i >= 0; i--) {
            iv[i] = (byte) (sequence & 0xFF);
            sequence >>>= 8;
        }
        return iv;
    }

    static String absolutize(String uri, String baseUrl) {
        if (uri == null) {
            return null;
        }
        String u = uri.trim();
        if (u.startsWith("http://") || u.startsWith("https://")) {
            return u;
        }
        if (baseUrl == null) {
            return u;
        }
        try {
            return URI.create(baseUrl).resolve(u).toString();
        } catch (IllegalArgumentException e) {
            // 手工兜底：去掉 baseUrl 最后一段再拼
            int slash = baseUrl.lastIndexOf('/');
            if (slash > "https://".length()) {
                return baseUrl.substring(0, slash + 1) + u;
            }
            return u;
        }
    }
}
