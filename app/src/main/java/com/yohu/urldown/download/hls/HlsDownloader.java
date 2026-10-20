package com.yohu.urldown.download.hls;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * HLS（m3u8）下载：取播放列表 → master 选码率最高的变体 → 逐片下载、按需 AES-128 解密 →
 * 按顺序追加到同一个文件。TS 分片串接即为可播放的传输流；fMP4 先写初始化段再串接分片。
 *
 * <p>断点续传以<b>分片</b>为单位：已写完的分片数与字节数记在 {@code <目标>.part.idx}，
 * 暂停 / 断网后重新调用会从下一片继续，不重下已完成的部分。
 *
 * <p>同步阻塞，必须在后台线程调用。
 */
public final class HlsDownloader {

    /** 进度回调（在调用线程触发）。 */
    public interface Progress {
        void onProgress(long bytes, int doneSegments, int totalSegments, long speedBytesPerSecond);
    }

    /** 失败原因，供上层分类。 */
    public enum Kind {
        NETWORK, HTTP, FORMAT, STORAGE, CANCELED
    }

    /** 下载失败。 */
    public static final class HlsException extends IOException {
        public final Kind kind;
        public final int httpStatus;

        HlsException(Kind kind, String message, int httpStatus, @Nullable Throwable cause) {
            super(message, cause);
            this.kind = kind;
            this.httpStatus = httpStatus;
        }

        HlsException(Kind kind, String message) {
            this(kind, message, -1, null);
        }
    }

    private static final int BUFFER = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_SEGMENTS = 20_000;
    /** 单个分片的尝试次数（含第一次），之间按 1s、2s 退避。 */
    private static final int SEGMENT_ATTEMPTS = 3;

    private HlsDownloader() {
    }

    /** 按地址后缀或 MIME 判断是不是 HLS 播放列表。 */
    public static boolean isHls(@Nullable String url, @Nullable String mimeType) {
        if (mimeType != null) {
            String m = mimeType.toLowerCase(Locale.ROOT);
            if (m.contains("mpegurl")) {
                return true;
            }
        }
        if (url == null) {
            return false;
        }
        String path = url.toLowerCase(Locale.ROOT);
        int cut = path.indexOf('?');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        cut = path.indexOf('#');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        return path.endsWith(".m3u8");
    }

    /**
     * 下载并合并。
     *
     * @param url      播放列表地址
     * @param headers  请求头（Referer / UA 等，分片与密钥请求同样带上）
     * @param target   输出文件
     * @param progress 进度回调
     * @param canceled 返回 true 时尽快停止（保留已完成的分片以便续传）
     * @return 输出文件
     */
    @NonNull
    public static File download(@NonNull String url, @NonNull Map<String, String> headers,
                                @NonNull File target, @NonNull Progress progress,
                                @NonNull BooleanSupplier canceled) throws HlsException {
        String playlistUrl = url;
        M3u8Playlist playlist = M3u8Playlist.parse(fetchText(playlistUrl, headers, canceled), playlistUrl);
        if (playlist.isMaster()) {
            M3u8Playlist.Variant best = playlist.bestVariant();
            if (best == null) {
                throw new HlsException(Kind.FORMAT, "master playlist has no usable variant");
            }
            playlistUrl = best.uri;
            playlist = M3u8Playlist.parse(fetchText(playlistUrl, headers, canceled), playlistUrl);
        }
        List<M3u8Playlist.Segment> segments = playlist.segments();
        if (segments.isEmpty()) {
            throw new HlsException(Kind.FORMAT, "playlist has no segments " + playlist.warnings());
        }
        if (!playlist.hasEndList()) {
            // 直播流没有终点，下载会无限进行
            throw new HlsException(Kind.FORMAT, "live playlist (no EXT-X-ENDLIST) is not supported");
        }
        if (segments.size() > MAX_SEGMENTS) {
            throw new HlsException(Kind.FORMAT, "too many segments: " + segments.size());
        }
        for (M3u8Playlist.Segment s : segments) {
            if (s.encrypted() && !"AES-128".equalsIgnoreCase(s.keyMethod)) {
                throw new HlsException(Kind.FORMAT, "unsupported key method: " + s.keyMethod);
            }
        }

        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new HlsException(Kind.STORAGE, "cannot create " + parent);
        }
        File part = new File(target.getPath() + ".part");
        File index = new File(target.getPath() + ".part.idx");
        Checkpoint cp = Checkpoint.read(index, part, playlistUrl, segments.size());

        Speed speed = new Speed(cp.bytes);
        byte[] key = null;
        String keyUri = null;
        try (RandomAccessFile out = new RandomAccessFile(part, "rw")) {
            out.setLength(cp.bytes);
            out.seek(cp.bytes);
            if (cp.done == 0 && cp.bytes == 0 && playlist.initSegment() != null) {
                M3u8Playlist.Segment init = playlist.initSegment();
                byte[] data = fetchWithRetry(init.uri, headers, canceled,
                        init.byteRangeStart, init.byteRangeLength);
                out.write(data);
                cp.bytes += data.length;
                cp.write(index);
            }
            for (int i = cp.done; i < segments.size(); i++) {
                if (canceled.getAsBoolean()) {
                    throw new HlsException(Kind.CANCELED, "canceled");
                }
                M3u8Playlist.Segment seg = segments.get(i);
                byte[] data = fetchWithRetry(seg.uri, headers, canceled,
                        seg.byteRangeStart, seg.byteRangeLength);
                if (seg.encrypted()) {
                    if (key == null || !seg.keyUri.equals(keyUri)) {
                        if (seg.keyUri == null) {
                            throw new HlsException(Kind.FORMAT, "EXT-X-KEY has no URI");
                        }
                        key = fetchWithRetry(seg.keyUri, headers, canceled, -1, -1);
                        keyUri = seg.keyUri;
                    }
                    byte[] iv = seg.keyIv != null ? seg.keyIv
                            : M3u8Playlist.sequenceIv(playlist.mediaSequence() + i);
                    data = decryptAes128(data, key, iv);
                }
                out.write(data);
                cp.bytes += data.length;
                cp.done = i + 1;
                cp.write(index);
                progress.onProgress(cp.bytes, cp.done, segments.size(), speed.update(cp.bytes));
            }
        } catch (HlsException e) {
            throw e;
        } catch (IOException e) {
            throw new HlsException(Kind.STORAGE, "write failed: " + e.getMessage(), -1, e);
        }

        if (target.exists() && !target.delete()) {
            throw new HlsException(Kind.STORAGE, "cannot replace " + target);
        }
        if (!part.renameTo(target)) {
            throw new HlsException(Kind.STORAGE, "cannot rename " + part + " -> " + target);
        }
        //noinspection ResultOfMethodCallIgnored
        index.delete();
        return target;
    }

    /** 放弃续传：删掉临时文件与断点记录。 */
    public static void discardPartial(@NonNull File target) {
        //noinspection ResultOfMethodCallIgnored
        new File(target.getPath() + ".part").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(target.getPath() + ".part.idx").delete();
    }

    // ------------------------------------------------------------------ 网络

    private static String fetchText(String url, Map<String, String> headers, BooleanSupplier canceled)
            throws HlsException {
        return new String(fetchWithRetry(url, headers, canceled, -1, -1), StandardCharsets.UTF_8);
    }

    private static byte[] fetchWithRetry(String url, Map<String, String> headers,
                                         BooleanSupplier canceled, long rangeStart, long rangeLength)
            throws HlsException {
        HlsException last = null;
        for (int attempt = 1; attempt <= SEGMENT_ATTEMPTS; attempt++) {
            if (canceled.getAsBoolean()) {
                throw new HlsException(Kind.CANCELED, "canceled");
            }
            try {
                return fetch(url, headers, canceled, rangeStart, rangeLength);
            } catch (HlsException e) {
                boolean transient5xx = e.kind == Kind.HTTP && e.httpStatus >= 500;
                if (e.kind != Kind.NETWORK && !transient5xx) {
                    throw e;
                }
                last = e;
                if (attempt < SEGMENT_ATTEMPTS) {
                    sleepQuietly(1000L << (attempt - 1), canceled);
                }
            }
        }
        throw last;
    }

    private static byte[] fetch(String url, Map<String, String> headers, BooleanSupplier canceled,
                                long rangeStart, long rangeLength) throws HlsException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept-Encoding", "identity");
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            if (rangeStart >= 0 && rangeLength > 0) {
                conn.setRequestProperty("Range",
                        "bytes=" + rangeStart + "-" + (rangeStart + rangeLength - 1));
            }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new HlsException(Kind.HTTP, "HTTP " + status + " for " + url, status, null);
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream(BUFFER);
                byte[] chunk = new byte[BUFFER];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    if (canceled.getAsBoolean()) {
                        throw new HlsException(Kind.CANCELED, "canceled");
                    }
                    buf.write(chunk, 0, n);
                }
                return buf.toByteArray();
            }
        } catch (HlsException e) {
            throw e;
        } catch (IOException | IllegalArgumentException e) {
            throw new HlsException(Kind.NETWORK, "cannot fetch " + url + ": " + e, -1, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    static byte[] decryptAes128(byte[] data, byte[] key, byte[] iv) throws HlsException {
        if (key == null || key.length != 16) {
            throw new HlsException(Kind.FORMAT, "AES-128 key must be 16 bytes");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            try {
                return cipher.doFinal(data);
            } catch (BadPaddingException e) {
                // 部分打包器的分片不加 PKCS#7 填充：退化为 NoPadding
                Cipher raw = Cipher.getInstance("AES/CBC/NoPadding");
                raw.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
                return raw.doFinal(data);
            }
        } catch (GeneralSecurityException e) {
            throw new HlsException(Kind.FORMAT, "AES-128 decrypt failed: " + e, -1, e);
        }
    }

    private static void sleepQuietly(long ms, BooleanSupplier canceled) throws HlsException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (canceled.getAsBoolean()) {
                throw new HlsException(Kind.CANCELED, "canceled");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new HlsException(Kind.CANCELED, "interrupted");
            }
        }
    }

    // ------------------------------------------------------------------ 断点

    /**
     * 续传记录：播放列表标识、分片总数、已完成分片数、已写字节数。
     *
     * <p>播放列表标识去掉查询串：重新解析后签名 / 过期参数会变，内容不变。
     */
    static final class Checkpoint {
        final String playlist;
        final int total;
        int done;
        long bytes;

        private Checkpoint(String playlist, int total, int done, long bytes) {
            this.playlist = playlist;
            this.total = total;
            this.done = done;
            this.bytes = bytes;
        }

        /** 记录与当前播放列表、临时文件都对得上才续传，否则从头开始。 */
        static Checkpoint read(File index, File part, String playlistUrl, int total) {
            String playlist = keyOf(playlistUrl);
            Checkpoint fresh = new Checkpoint(playlist, total, 0, 0);
            if (!index.isFile() || !part.isFile()) {
                return fresh;
            }
            try {
                // Files.readAllBytes 从 API 26 起可用；InputStream.readAllBytes 要 33
                String[] f = new String(java.nio.file.Files.readAllBytes(index.toPath()),
                        StandardCharsets.UTF_8).split("\n");
                if (f.length < 4 || !f[0].equals(playlist) || Integer.parseInt(f[1]) != total) {
                    return fresh;
                }
                int done = Integer.parseInt(f[2]);
                long bytes = Long.parseLong(f[3]);
                if (done < 0 || done > total || bytes < 0 || part.length() < bytes) {
                    return fresh;
                }
                return new Checkpoint(playlist, total, done, bytes);
            } catch (IOException | RuntimeException e) {
                return fresh;
            }
        }

        static String keyOf(String url) {
            int end = url.length();
            int q = url.indexOf('?');
            if (q >= 0) {
                end = q;
            }
            int h = url.indexOf('#');
            if (h >= 0 && h < end) {
                end = h;
            }
            return url.substring(0, end);
        }

        void write(File index) throws IOException {
            String body = playlist + "\n" + total + "\n" + done + "\n" + bytes + "\n";
            try (OutputStream out = new FileOutputStream(index, false)) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /** 最近约 2 秒的平均速度。 */
    private static final class Speed {
        private long markBytes;
        private long markTime = System.nanoTime();
        private long value;

        Speed(long startBytes) {
            markBytes = startBytes;
        }

        long update(long bytes) {
            long now = System.nanoTime();
            long elapsed = now - markTime;
            if (elapsed >= 2_000_000_000L || value == 0 && elapsed > 200_000_000L) {
                value = (bytes - markBytes) * 1_000_000_000L / Math.max(1, elapsed);
                markBytes = bytes;
                markTime = now;
            }
            return value;
        }
    }
}
