package com.yohu.lib_urlparse.transfer;

import com.yohu.lib_urlparse.spi.CancelToken;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * M3U8 分片下载与合并（样本功能清单里的「m3u8合并下载」）。
 *
 * <p>流程：取播放列表 → 若为 master 取码率最高变体 → 逐片下载并（如需）AES-128-CBC 解密 →
 * **按顺序追加**到同一个输出文件。TS 分片串接后即为可直接播放的传输流；
 * fMP4 分片（{@code #EXT-X-MAP}）串接后同样可播，因此这里不做容器转换。
 *
 * <p>进度以**已完成分片数 + 当前分片字节**为口径（分片总长度通常不可知）。
 * 取消/失败时保留已合并的部分，并在文件名旁留下 {@code .m3u8.part} 供排查。
 */
public final class M3u8Downloader {

    private static final int BUFFER = 64 * 1024;

    private final int maxSegments;

    public M3u8Downloader() {
        this(20_000);
    }

    public M3u8Downloader(int maxSegments) {
        this.maxSegments = maxSegments;
    }

    public DownloadResult download(DownloadRequest request, DownloadListener listener,
                                   CancelToken token) throws DownloadException {
        DownloadListener cb = listener != null ? listener : DownloadListener.NOOP;
        CancelToken cancel = token != null ? token : CancelToken.none();
        long startNanos = System.nanoTime();

        String playlistUrl = request.url();
        String text = fetchText(playlistUrl, request, cancel);
        M3u8Playlist playlist = M3u8Playlist.parse(text, playlistUrl);

        if (playlist.isMaster()) {
            M3u8Playlist.Variant best = playlist.bestVariant();
            if (best == null) {
                throw new DownloadException(DownloadException.Reason.M3U8,
                        "master playlist has no usable variant: " + playlistUrl);
            }
            playlistUrl = best.uri;
            text = fetchText(playlistUrl, request, cancel);
            playlist = M3u8Playlist.parse(text, playlistUrl);
        }

        List<M3u8Playlist.Segment> segments = playlist.segments();
        if (segments.isEmpty()) {
            throw new DownloadException(DownloadException.Reason.M3U8,
                    "playlist has no segments: " + playlistUrl
                            + (playlist.warnings().isEmpty() ? "" : " (" + playlist.warnings() + ")"));
        }
        if (segments.size() > maxSegments) {
            throw new DownloadException(DownloadException.Reason.M3U8,
                    "playlist has too many segments (" + segments.size() + " > " + maxSegments + ")");
        }

        File target = request.targetFile();
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new DownloadException(DownloadException.Reason.INVALID_INPUT,
                    "cannot create output directory: " + parent.getAbsolutePath());
        }
        File temp = new File(target.getAbsolutePath() + ".m3u8.part");

        cb.onStart(playlistUrl, -1L);
        long written = 0L;
        int index = 0;
        byte[] keyCache = null;
        String keyCacheUri = null;

        try {
            OutputStream out = new FileOutputStream(temp, false);
            try {
                for (M3u8Playlist.Segment seg : segments) {
                    cancel.throwIfCancelled();
                    index++;
                    byte[] data = fetchSegment(seg, request, cancel, index - 1);

                    if (seg.encrypted()) {
                        if (keyCache == null || !seg.keyUri.equals(keyCacheUri)) {
                            keyCache = fetchKey(seg.keyUri, request, cancel);
                            keyCacheUri = seg.keyUri;
                        }
                        byte[] iv = seg.keyIv != null ? seg.keyIv : M3u8Playlist.sequenceIv(index - 1);
                        data = decryptAes128(data, keyCache, iv, playlistUrl, index);
                    }
                    out.write(data);
                    written += data.length;
                    cb.onProgress(new DownloadProgress(playlistUrl, written, -1,
                            (System.nanoTime() - startNanos) / 1_000_000L, 0L));
                }
                out.flush();
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // 关闭失败不覆盖真正原因
                }
            }

            File done = HttpDownloader.moveInto(temp, target);
            DownloadResult result = new DownloadResult(playlistUrl, done, written,
                    (System.nanoTime() - startNanos) / 1_000_000L, DownloadState.COMPLETED,
                    "application/vnd.apple.mpegurl", index);
            cb.onComplete(result);
            return result;
        } catch (DownloadException e) {
            cb.onError(e);
            throw e;
        } catch (IOException e) {
            DownloadException de = new DownloadException(DownloadException.Reason.M3U8,
                    "cannot write merged output: " + e, e);
            cb.onError(de);
            throw de;
        }
    }

    // ------------------------------------------------------------------ 内部

    private String fetchText(String url, DownloadRequest base, CancelToken cancel)
            throws DownloadException {
        byte[] bytes = fetch(url, base, cancel, -1, -1, null);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private byte[] fetchSegment(M3u8Playlist.Segment seg, DownloadRequest base, CancelToken cancel,
                                long sequence) throws DownloadException {
        return fetch(seg.uri, base, cancel, seg.byteRangeStart, seg.byteRangeLength, null);
    }

    private byte[] fetchKey(String keyUri, DownloadRequest base, CancelToken cancel)
            throws DownloadException {
        if (keyUri == null) {
            throw new DownloadException(DownloadException.Reason.M3U8,
                    "segment is AES-128 encrypted but EXT-X-KEY has no URI");
        }
        return fetch(keyUri, base, cancel, -1, -1, null);
    }

    /** 取一个 URL 的全部字节；{@code rangeStart>=0 && rangeLen>0} 时用 Range 请求。 */
    private byte[] fetch(String url, DownloadRequest base, CancelToken cancel,
                         long rangeStart, long rangeLen, String accept)
            throws DownloadException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout((int) base.connectTimeoutMs());
            conn.setReadTimeout((int) base.readTimeoutMs());
            conn.setInstanceFollowRedirects(true);
            if (base.userAgent() != null) {
                conn.setRequestProperty("User-Agent", base.userAgent());
            }
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (accept != null) {
                conn.setRequestProperty("Accept", accept);
            }
            for (java.util.Map.Entry<String, String> e : base.headers().entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            if (rangeStart >= 0 && rangeLen > 0) {
                conn.setRequestProperty("Range", "bytes=" + rangeStart + "-" + (rangeStart + rangeLen - 1));
            }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new DownloadException(DownloadException.Reason.M3U8,
                        "segment/key fetch got HTTP " + status + " for " + url,
                        null, status, -1L);
            }
            InputStream in = new java.io.BufferedInputStream(conn.getInputStream(), BUFFER);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
                byte[] buf = new byte[BUFFER];
                int n;
                while ((n = in.read(buf)) > 0) {
                    cancel.throwIfCancelled();
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // 清理路径
                }
            }
        } catch (DownloadException e) {
            throw e;
        } catch (IOException e) {
            throw new DownloadException(DownloadException.Reason.NETWORK,
                    "cannot fetch " + url + ": " + e, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    static byte[] decryptAes128(byte[] data, byte[] key, byte[] iv, String where, int index)
            throws DownloadException {
        if (key == null || key.length != 16) {
            throw new DownloadException(DownloadException.Reason.M3U8,
                    "AES-128 key must be 16 bytes, got "
                            + (key == null ? "null" : key.length) + " (" + where + " #" + index + ")");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(iv));
            // 部分打包器的分片不加 PKCS5 填充：先按填充解，失败再退化为 NoPadding
            try {
                return cipher.doFinal(data);
            } catch (javax.crypto.BadPaddingException e) {
                Cipher raw = Cipher.getInstance("AES/CBC/NoPadding");
                raw.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new IvParameterSpec(iv));
                return raw.doFinal(data);
            }
        } catch (java.security.GeneralSecurityException e) {
            throw new DownloadException(DownloadException.Reason.M3U8,
                    "AES-128 decrypt failed (" + where + " #" + index + "): " + e, e);
        }
    }

    /** 便于单测：解析播放列表并回报规模。 */
    public static List<String> describe(String playlistText, String baseUrl) {
        M3u8Playlist p = M3u8Playlist.parse(playlistText, baseUrl);
        List<String> out = new ArrayList<>();
        if (p.isMaster()) {
            out.add("master: " + p.variants().size() + " 变体, best=" + p.bestVariant());
        } else {
            out.add("media: " + p.segments().size() + " 分片, 总时长 "
                    + String.format(java.util.Locale.ROOT, "%.2f", p.totalDurationSeconds()) + "s"
                    + (p.hasEndList() ? ", ENDLIST" : ", 直播/未终结"));
        }
        out.addAll(p.warnings());
        return out;
    }
}
