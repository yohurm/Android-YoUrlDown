package com.yohu.urldown.download;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.yohu.lib_download.YoDownload;
import com.yohu.lib_urlparse.api.MediaAsset;

import java.io.File;

/**
 * 把"解析结果里的一条媒体"接到下载引擎 {@code lib_download} 上。
 *
 * <p>职责只有映射：请求参数、回调、错误分类。命名、校验、合并在 {@link DownloadPipeline}。
 *
 * <p><b>请求 ID 由调用方给定</b>（任务 ID + 序号），而不是引擎按"URL + 路径"推导：
 * 直链会过期，重新解析后地址变了，ID 仍然不变，暂停 / 断网后才能接着同一个断点续传。
 */
public final class AssetDownloader {

    /** 引擎回调。默认由引擎切回主线程。 */
    public interface Callback {
        void onProgress(long downloadedBytes, long totalBytes, long speedBytesPerSecond, long etaMillis);

        void onSuccess(@NonNull File file);

        /** 被 {@link #pause} 停下，临时文件与断点保留。 */
        void onPaused();

        /** 被 {@link #cancel} 停下，临时文件已删除。 */
        void onCanceled();

        void onError(@NonNull DownloadError error, @Nullable String detail);
    }

    private AssetDownloader() {
    }

    /**
     * 进程内初始化一次。
     *
     * <p>引擎侧的并行上限放宽到 4：真正的并发由任务调度控制（设置里的“同时下载”），
     * 引擎这里只需要不成为瓶颈。
     */
    public static void init(@NonNull Context context, @NonNull File baseDir) {
        YoDownload.Config config = YoDownload.Config.builder(context.getApplicationContext())
                .defaultDownloadDir(baseDir)
                .maxParallelTasks(4)
                .maxBlocksPerTask(3)
                .timeoutMs(15_000, 30_000)
                .bufferSize(32 * 1024)
                .maxRetryCount(2)
                // 进度回调太密会拖慢 UI；300ms 足够顺滑。
                .progressCallbackIntervalMs(300L)
                .build();
        YoDownload.init(context.getApplicationContext(), config);
    }

    /**
     * 开始或续传一条媒体。
     *
     * @param requestId 稳定的请求 ID（同一任务同一序号不变）
     * @return 是否成功交给引擎
     */
    public static boolean start(@NonNull String requestId,
                                @NonNull MediaAsset asset,
                                @NonNull File dir,
                                @NonNull String fileName,
                                @NonNull Callback callback) {
        return start(requestId, asset, dir, fileName, callback, true);
    }

    private static boolean start(String requestId, MediaAsset asset, File dir, String fileName,
                                 Callback callback, boolean allowRestart) {
        File target = new File(dir, fileName);
        YoDownload.Task previous = YoDownload.query(requestId);
        if (previous != null && (!samePath(previous.request().url(), asset.url())
                || !previous.destinationFile().equals(target))) {
            // 重新解析后直链的签名 / 过期参数会变、CDN 节点也可能换，这些不影响内容；
            // 但路径变了就不能保证是同一份字节，丢掉旧断点从头下。
            YoDownload.remove(requestId, true);
        }
        YoDownload.Request request = YoDownload.Request.builder(asset.url())
                .id(requestId)
                .destinationDir(dir)
                .fileName(fileName)
                .title(fileName)
                .mimeType(asset.mimeType())
                .headers(asset.headers().isEmpty() ? null : asset.headers())
                // 取消即删除临时文件；暂停不受影响。
                .keepTempFileOnCancel(false)
                .replaceExisting(true)
                .build();
        YoDownload.Task task = YoDownload.enqueue(request, new Bridge(callback, requestId, asset,
                dir, fileName, allowRestart));
        return task != null;
    }

    /** 两个地址的路径部分（不含主机与查询串）是否相同。 */
    static boolean samePath(@Nullable String a, @Nullable String b) {
        String pa = path(a);
        return pa != null && pa.equals(path(b));
    }

    @Nullable
    private static String path(@Nullable String url) {
        if (url == null) {
            return null;
        }
        int start = url.indexOf("://");
        int slash = url.indexOf('/', start < 0 ? 0 : start + 3);
        if (slash < 0) {
            return "/";
        }
        int end = url.length();
        int q = url.indexOf('?', slash);
        if (q >= 0) {
            end = q;
        }
        int h = url.indexOf('#', slash);
        if (h >= 0 && h < end) {
            end = h;
        }
        return url.substring(slash, end);
    }

    /** 暂停：保留临时文件与断点。 */
    public static void pause(@NonNull String requestId) {
        YoDownload.pause(requestId);
    }

    /** 取消：删除临时文件。 */
    public static void cancel(@NonNull String requestId) {
        YoDownload.cancel(requestId);
    }

    /** 结束后清理引擎里的断点记录（不删已完成的文件）；{@code deleteTemp} 时一并删临时文件。 */
    public static void forget(@NonNull String requestId, boolean deleteTemp) {
        YoDownload.Task t = YoDownload.query(requestId);
        if (t == null) {
            return;
        }
        if (deleteTemp) {
            //noinspection ResultOfMethodCallIgnored
            t.tempFile().delete();
        }
        YoDownload.remove(requestId, false);
    }

    /** 引擎回调 → {@link Callback}；服务器不支持断点时清掉旧进度从头再来一次。 */
    private static final class Bridge implements YoDownload.Listener {
        private final Callback callback;
        private final String requestId;
        private final MediaAsset asset;
        private final File dir;
        private final String fileName;
        private final boolean allowRestart;

        Bridge(Callback callback, String requestId, MediaAsset asset, File dir, String fileName,
               boolean allowRestart) {
            this.callback = callback;
            this.requestId = requestId;
            this.asset = asset;
            this.dir = dir;
            this.fileName = fileName;
            this.allowRestart = allowRestart;
        }

        @Override
        public void onProgress(@NonNull YoDownload.Task t, @NonNull YoDownload.Progress p) {
            callback.onProgress(p.downloadedBytes(), p.totalBytes(), p.speedBytesPerSecond(),
                    p.etaMillis());
        }

        @Override
        public void onSuccess(@NonNull YoDownload.Task t, @NonNull YoDownload.Result r) {
            callback.onSuccess(t.destinationFile());
        }

        @Override
        public void onPaused(@NonNull YoDownload.Task t, @NonNull YoDownload.Result r) {
            callback.onPaused();
        }

        @Override
        public void onCanceled(@NonNull YoDownload.Task t, @NonNull YoDownload.Result r) {
            callback.onCanceled();
        }

        @Override
        public void onError(@NonNull YoDownload.Task t, @NonNull YoDownload.Result r) {
            if (r.error() == YoDownload.Error.RESUME_UNSUPPORTED && allowRestart) {
                YoDownload.remove(requestId, true);
                if (start(requestId, asset, dir, fileName, callback, false)) {
                    return;
                }
            }
            callback.onError(DownloadError.ofEngine(r.error(), r.throwable()),
                    r.throwable() == null ? null : String.valueOf(r.throwable()));
        }
    }
}
