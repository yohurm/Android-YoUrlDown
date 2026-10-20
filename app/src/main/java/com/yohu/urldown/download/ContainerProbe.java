package com.yohu.urldown.download;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 落盘后的容器签名探测。
 *
 * <p>为什么需要：CDN 出错时经常**返回 200 + 一段 HTML 错误页**，如果只看 HTTP 状态码，
 * 就会把一段 HTML 存成 {@code .mp4} 并报告"下载成功"。所以下载完成后必须按**文件头字节**确认容器类型。
 *
 * <p>纯 JVM、零依赖；只看文件头，不解码内容。
 */
public final class ContainerProbe {

    /** 探测结果。 */
    public enum Container {
        MP4("video/mp4"),
        WEBM("video/webm"),
        AVI("video/x-msvideo"),
        FLV("video/x-flv"),
        MPEG_TS("video/mp2t"),
        MP3("audio/mpeg"),
        M4A("audio/mp4"),
        OGG("audio/ogg"),
        WAV("audio/wav"),
        JPEG("image/jpeg"),
        PNG("image/png"),
        GIF("image/gif"),
        WEBP("image/webp"),
        /** 能识别是文本（多半是错误页 / 播放列表）。 */
        TEXT("text/plain"),
        /** 无法识别。 */
        UNKNOWN("application/octet-stream");

        private final String mimeType;

        Container(String mimeType) {
            this.mimeType = mimeType;
        }

        public String mimeType() {
            return mimeType;
        }

        public boolean isPlayableMedia() {
            switch (this) {
                case MP4:
                case WEBM:
                case AVI:
                case FLV:
                case MPEG_TS:
                case MP3:
                case M4A:
                case OGG:
                case WAV:
                case JPEG:
                case PNG:
                case GIF:
                case WEBP:
                    return true;
                default:
                    return false;
            }
        }
    }

    /** 只读文件头 12 字节即可判定常见容器。 */
    private static final int HEADER_BYTES = 12;

    private ContainerProbe() {
    }

    /** 探测文件。读不到时返回 {@link Container#UNKNOWN}，不抛异常。 */
    public static Container probe(File file) {
        if (file == null || !file.isFile() || file.length() <= 0) {
            return Container.UNKNOWN;
        }
        try (InputStream in = new FileInputStream(file)) {
            return probe(in);
        } catch (IOException e) {
            return Container.UNKNOWN;
        }
    }

    /** 探测字节数组。 */
    public static Container probe(byte[] head) {
        return classify(head, head == null ? 0 : head.length);
    }

    private static Container probe(InputStream in) throws IOException {
        byte[] head = new byte[HEADER_BYTES];
        int n = 0;
        while (n < head.length) {
            int r = in.read(head, n, head.length - n);
            if (r <= 0) {
                break;
            }
            n += r;
        }
        return classify(head, n);
    }

    private static Container classify(byte[] b, int len) {
        if (b == null || len < 4) {
            return Container.UNKNOWN;
        }
        // MP4 / MOV / M4A：第 4-7 字节是 "ftyp"
        if (len >= 8 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
            String brand = len >= 12
                    ? new String(b, 8, 4, java.nio.charset.StandardCharsets.US_ASCII)
                    : "";
            if (brand.startsWith("M4A") || brand.startsWith("M4B")) {
                return Container.M4A;
            }
            return Container.MP4;
        }
        // WebM / MKV：1A 45 DF A3
        if ((b[0] & 0xFF) == 0x1A && (b[1] & 0xFF) == 0x45
                && (b[2] & 0xFF) == 0xDF && (b[3] & 0xFF) == 0xA3) {
            return Container.WEBM;
        }
        // AVI：RIFF....AVI
        if (len >= 11 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'A' && b[9] == 'V' && b[10] == 'I') {
            return Container.AVI;
        }
        // FLV：'FLV'
        if (b[0] == 'F' && b[1] == 'L' && b[2] == 'V') {
            return Container.FLV;
        }
        // MPEG-TS：每 188 字节一个 0x47；文件头就是 0x47
        if ((b[0] & 0xFF) == 0x47) {
            return Container.MPEG_TS;
        }
        // MP3：ID3 或帧同步 FFEx
        if (b[0] == 'I' && b[1] == 'D' && b[2] == '3') {
            return Container.MP3;
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0) {
            return Container.MP3;
        }
        // OGG
        if (b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S') {
            return Container.OGG;
        }
        // WAV：RIFF....WAVE
        if (len >= 11 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'A' && b[10] == 'V') {
            return Container.WAV;
        }
        // JPEG
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
            return Container.JPEG;
        }
        // PNG
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return Container.PNG;
        }
        // GIF
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return Container.GIF;
        }
        // WEBP：RIFF....WEBP
        if (len >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return Container.WEBP;
        }
        // 文本：HTML 错误页 / m3u8 播放列表
        if (looksLikeText(b, len)) {
            return Container.TEXT;
        }
        return Container.UNKNOWN;
    }

    private static boolean looksLikeText(byte[] b, int len) {
        for (int i = 0; i < len; i++) {
            int c = b[i] & 0xFF;
            boolean printable = c == 0x09 || c == 0x0A || c == 0x0D || (c >= 0x20 && c < 0x7F);
            if (!printable && c != 0xEF && c != 0xBB && c != 0xBF) {
                return false;
            }
        }
        return true;
    }

    /** 下载完成后的校验结论。 */
    public static final class Verdict {
        private final Container container;
        private final boolean acceptable;
        private final String reason;

        Verdict(Container container, boolean acceptable, String reason) {
            this.container = container;
            this.acceptable = acceptable;
            this.reason = reason;
        }

        public Container container() {
            return container;
        }

        public boolean acceptable() {
            return acceptable;
        }

        public String reason() {
            return reason;
        }

        @Override
        public String toString() {
            return "Verdict{" + container + ", acceptable=" + acceptable + ", " + reason + "}";
        }
    }

    /**
     * 判断落盘文件是否可接受。
     *
     * <p>文本文件一律不可接受：它几乎总是 HTML 错误页或未下载完成的播放列表，
     * 而不是媒体本体。
     */
    public static Verdict verify(File file) {
        Container c = probe(file);
        if (!c.isPlayableMedia()) {
            if (c == Container.TEXT) {
                return new Verdict(c, false,
                        "文件内容是文本（多半是 HTML 错误页或未合并的播放列表），不是媒体本体");
            }
            return new Verdict(c, false, "无法识别容器类型（文件头不匹配任何已知格式）");
        }
        return new Verdict(c, true, "容器类型 " + c.name());
    }
}
