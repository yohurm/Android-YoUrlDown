package com.yohu.lib_urlparse.transfer;

import com.yohu.lib_urlparse.spi.CancelToken;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * 单文件 HTTP 下载器：支持**断点续传**、进度回调、协作式取消、临时文件原子改名的实现。
 *
 * <p>设计要点：
 * <ul>
 *   <li>下载中一律写 {@code <目标>.part}，只有完整收完才改名成目标文件——
 *       这样"半截文件"永远不会被误当成成品（宿主扫描目录时不会拿到坏文件）；</li>
 *   <li>续传靠 {@code Range: bytes=N-}。服务端若忽略 Range 而返回 200，则**重新开始**
 *       而不是把新内容追加到旧字节后面（那会得到一个损坏文件）；</li>
 *   <li>取消/网络中断时**保留** {@code .part}，下次带 {@code resume=true} 继续；</li>
 *   <li>零第三方依赖（{@link HttpURLConnection}），因此纯 JVM 上也能跑测试。</li>
 * </ul>
 */
public final class HttpDownloader {

    private static final int BUFFER = 64 * 1024;
    private static final long PROGRESS_INTERVAL_MS = 200L;

    public DownloadResult download(DownloadRequest request, DownloadListener listener,
                                   CancelToken token) throws DownloadException {
        DownloadListener cb = listener != null ? listener : DownloadListener.NOOP;
        CancelToken cancel = token != null ? token : CancelToken.none();

        File target = request.targetFile();
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new DownloadException(DownloadException.Reason.INVALID_INPUT,
                    "cannot create output directory: " + parent.getAbsolutePath());
        }
        File part = request.partFile();

        long existing = part.isFile() ? part.length() : 0L;
        if (!request.resume() && existing > 0) {
            if (!part.delete()) {
                throw new DownloadException(DownloadException.Reason.IO,
                        "cannot delete existing partial file: " + part.getAbsolutePath());
            }
            existing = 0L;
        }
        long expected = request.expectedSize();
        if (existing > 0 && expected > 0 && existing == expected) {
            // 上次其实已经下完，只是没来得及改名
            File done = moveInto(part, target);
            long elapsed = 0L;
            DownloadResult r = new DownloadResult(request.url(), done, existing, elapsed,
                    DownloadState.COMPLETED, null, 1);
            cb.onComplete(r);
            return r;
        }

        long startNanos = System.nanoTime();
        HttpURLConnection conn = null;
        long written = existing;
        long total = expected > 0 ? expected : -1L;
        String contentType = null;
        try {
            conn = open(request, existing);
            int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_PARTIAL) {
                total = parseTotalFromContentRange(conn.getHeaderField("Content-Range"), existing);
                if (total <= 0) {
                    long len = conn.getContentLength();
                    total = len > 0 ? existing + len : -1L;
                }
            } else if (status == 200) {
                if (existing > 0) {
                    // 服务端不支持 Range：必须从头来，否则文件会被拼坏
                    existing = 0L;
                    written = 0L;
                    truncate(part);
                }
                long len = conn.getContentLength();
                total = len > 0 ? len : (expected > 0 ? expected : -1L);
            } else if (status == 416) {
                // Range 不被满足：通常意味着 .part 已经是完整的
                if (part.isFile() && part.length() > 0) {
                    File done = moveInto(part, target);
                    DownloadResult r = new DownloadResult(request.url(), done, part.length(),
                            elapsedMs(System.nanoTime() - startNanos), DownloadState.COMPLETED, null, 1);
                    cb.onComplete(r);
                    return r;
                }
                throw new DownloadException(DownloadException.Reason.HTTP_STATUS,
                        "HTTP 416 but no partial file to finish", null, status, existing);
            } else if (status < 200 || status >= 300) {
                throw new DownloadException(DownloadException.Reason.HTTP_STATUS,
                        "unexpected HTTP status " + status + " for " + request.url(),
                        null, status, existing);
            }
            contentType = conn.getContentType();

            cb.onStart(request.url(), total);

            InputStream in = new BufferedInputStream(conn.getInputStream(), BUFFER);
            OutputStream out = new FileOutputStream(part, existing > 0);
            byte[] buf = new byte[BUFFER];
            long lastReport = 0L;
            long markBytes = written;
            long markNanos = startNanos;
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    cancel.throwIfCancelled();
                    out.write(buf, 0, n);
                    written += n;
                    long now = System.nanoTime();
                    if (elapsedMs(now - lastReport) >= PROGRESS_INTERVAL_MS) {
                        lastReport = now;
                        cb.onProgress(progress(request.url(), written, total,
                                elapsedMs(System.nanoTime() - startNanos), speed(markBytes, markNanos, written, now)));
                    }
                }
                out.flush();
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // 关闭失败不该覆盖真正的原因
                }
                try {
                    in.close();
                } catch (IOException ignored) {
                    // 同上
                }
            }

            cb.onProgress(progress(request.url(), written, total, elapsedMs(System.nanoTime() - startNanos),
                    speed(markBytes, markNanos, written, System.nanoTime())));

            if (total > 0 && written != total) {
                throw new DownloadException(DownloadException.Reason.INCOMPLETE,
                        "incomplete: got " + written + " of " + total + " bytes"
                                + " (partial kept for resume)", null, status, written);
            }

            File done = moveInto(part, target);
            DownloadResult result = new DownloadResult(request.url(), done, written,
                    elapsedMs(System.nanoTime() - startNanos), DownloadState.COMPLETED, contentType, 1);
            cb.onComplete(result);
            return result;
        } catch (DownloadException e) {
            cb.onError(e);
            throw e;
        } catch (IOException e) {
            DownloadException de = new DownloadException(DownloadException.Reason.NETWORK,
                    "network/IO failure: " + e, e, -1, written);
            cb.onError(de);
            throw de;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static HttpURLConnection open(DownloadRequest request, long from) throws DownloadException {
        HttpURLConnection conn;
        try {
            // URI.toURL() 而不是 new URL(String)：后者在 JDK 20+ 已废弃
            conn = (HttpURLConnection) URI.create(request.url()).toURL().openConnection();
        } catch (IOException | IllegalArgumentException e) {
            throw new DownloadException(DownloadException.Reason.INVALID_INPUT,
                    "bad url: " + request.url() + " (" + e + ")", e);
        }
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout((int) request.connectTimeoutMs());
            conn.setReadTimeout((int) request.readTimeoutMs());
            conn.setInstanceFollowRedirects(true);
            if (request.userAgent() != null) {
                conn.setRequestProperty("User-Agent", request.userAgent());
            }
            // 媒体流不要压缩：既省 CPU，也避免 Content-Length 与实际字节数不一致
            conn.setRequestProperty("Accept-Encoding", "identity");
            for (java.util.Map.Entry<String, String> e : request.headers().entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            if (from > 0) {
                conn.setRequestProperty("Range", "bytes=" + from + "-");
            }
            return conn;
        } catch (java.net.ProtocolException e) {
            throw new DownloadException(DownloadException.Reason.OTHER,
                    "cannot set request method: " + e, e);
        }
    }

    /** 解析 {@code Content-Range: bytes 100-999/1000} 里的总长。 */
    static long parseTotalFromContentRange(String header, long fallbackDone) {
        if (header == null) {
            return -1L;
        }
        int slash = header.lastIndexOf('/');
        if (slash < 0 || slash == header.length() - 1) {
            return -1L;
        }
        String total = header.substring(slash + 1).trim();
        if ("*".equals(total)) {
            return -1L;
        }
        try {
            return Long.parseLong(total);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    static void truncate(File f) throws DownloadException {
        if (f.exists() && !f.delete()) {
            throw new DownloadException(DownloadException.Reason.IO,
                    "cannot truncate partial file: " + f.getAbsolutePath());
        }
    }

    /** 把 .part 就位到目标路径：先删旧的同名目标，再改名；改名失败则退化为流式复制。 */
    static File moveInto(File part, File target) throws DownloadException {
        if (target.exists() && !target.delete()) {
            throw new DownloadException(DownloadException.Reason.IO,
                    "cannot replace existing file: " + target.getAbsolutePath());
        }
        if (part.renameTo(target)) {
            return target;
        }
        // 跨卷或 Windows 上被占用时 renameTo 会失败：退化为复制
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new BufferedInputStream(new java.io.FileInputStream(part), BUFFER);
            out = new java.io.BufferedOutputStream(new FileOutputStream(target), BUFFER);
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            out.close();
            out = null;
            in.close();
            in = null;
            if (!part.delete()) {
                // 复制成功即可；残留的 .part 不影响结果，仅记录在日志里
                part.deleteOnExit();
            }
            return target;
        } catch (IOException e) {
            throw new DownloadException(DownloadException.Reason.IO,
                    "cannot move partial file into place: " + e, e);
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // 清理路径
            }
        }
    }

    private static DownloadProgress progress(String url, long written, long total,
                                             long elapsedMs, long bps) {
        return new DownloadProgress(url, written, total, elapsedMs, bps);
    }

    private static long speed(long markBytes, long markNanos, long nowBytes, long nowNanos) {
        long dt = nowNanos - markNanos;
        if (dt <= 0) {
            return 0L;
        }
        long db = nowBytes - markBytes;
        if (db <= 0) {
            return 0L;
        }
        return (long) (db * 1_000_000_000.0 / dt);
    }

    private static long elapsedMs(long deltaNanos) {
        return deltaNanos / 1_000_000L;
    }
}
