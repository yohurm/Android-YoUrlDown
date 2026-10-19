package com.yohu.urldown.download;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.yohu.lib_download.YoDownload;
import com.yohu.lib_urlparse.api.MediaAsset;

import java.io.File;

/**
 * 把"解析结果里的媒体条目"接到下载引擎上。
 *
 * <p>职责边界：本类只做**映射与装配**，不做解析、不推导命名策略
 * （文件名由调用方给）。下载引擎换实现时只需要改这一个文件。
 *
 * <p>下载引擎来自合入的 {@code lib_download}（原 {@code GGEC-Android-EarBuds/lib_common}
 * 的下载子系统），基于 {@code HttpURLConnection} + 分块断点 + 系统通知，不依赖 OkHttp。
 */
public final class AssetDownloader {

    /** 下载结果回调。默认由引擎切回主线程回调。 */
    public interface Callback {
        void onProgress(int percent, long downloadedBytes, long totalBytes);

        void onSuccess(@NonNull File file);

        void onError(@NonNull String message);
    }

    private AssetDownloader() {
    }

    /** 进程内初始化一次。 */
    public static void init(@NonNull Context context, @NonNull File baseDir) {
        YoDownload.Config config = YoDownload.Config.builder(context.getApplicationContext())
                .defaultDownloadDir(baseDir)
                .maxParallelTasks(2)
                .maxBlocksPerTask(3)
                .timeoutMs(15_000, 30_000)
                .bufferSize(16 * 1024)
                .maxRetryCount(2)
                // 进度回调太密会拖慢 UI；200ms 足够顺滑。
                .progressCallbackIntervalMs(200L)
                .build();
        YoDownload.init(context.getApplicationContext(), config);
    }

    /** 关闭下载引擎（退出或测试结束时调用）。 */
    public static void shutdown() {
        YoDownload.shutdown();
    }

    /**
     * 下载一条媒体。
     *
     * @param asset    解析出的媒体条目（自带 headers：很多站点缺 Referer 会 403）
     * @param dir      落盘目录
     * @param fileName 文件名（不含目录）
     * @param title    展示标题，可为 {@code null}
     * @param callback 回调，可为 {@code null}
     * @return 任务 ID；失败返回空串
     */
    public static String download(@NonNull MediaAsset asset,
                                  @NonNull File dir,
                                  @NonNull String fileName,
                                  @Nullable String title,
                                  @Nullable final Callback callback) {
        YoDownload.Request request = YoDownload.Request.builder(asset.url())
                .destinationDir(dir)
                .fileName(fileName)
                .title(title)
                .mimeType(asset.mimeType())
                .headers(asset.headers().isEmpty() ? null : asset.headers())
                // 同一目标文件重复下载时覆盖，避免生成 "(1)" 副本堆积。
                .replaceExisting(true)
                .build();

        YoDownload.Task task = YoDownload.enqueue(request, new YoDownload.Listener() {
            @Override
            public void onProgress(@NonNull YoDownload.Task t,
                                   @NonNull YoDownload.Progress p) {
                if (callback != null) {
                    callback.onProgress(p.percent(), p.downloadedBytes(), p.totalBytes());
                }
            }

            @Override
            public void onSuccess(@NonNull YoDownload.Task t,
                                  @NonNull YoDownload.Result r) {
                if (callback != null) {
                    callback.onSuccess(t.destinationFile());
                }
            }

            @Override
            public void onError(@NonNull YoDownload.Task t,
                                @NonNull YoDownload.Result r) {
                if (callback != null) {
                    callback.onError(String.valueOf(r.error()));
                }
            }
        });
        return task == null ? "" : task.id();
    }

    /** 取消一个任务。 */
    public static boolean cancel(@NonNull String taskId) {
        return YoDownload.cancel(taskId);
    }
}
