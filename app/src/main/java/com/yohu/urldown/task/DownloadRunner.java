package com.yohu.urldown.task;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.yohu.lib_urlparse.api.FailureReason;
import com.yohu.lib_urlparse.api.MediaAsset;
import com.yohu.lib_urlparse.api.ParseFailure;
import com.yohu.lib_urlparse.api.UrlParseRequest;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.urldown.EngineBootstrap;
import com.yohu.urldown.download.AssetDownloader;
import com.yohu.urldown.download.DownloadError;
import com.yohu.urldown.download.DownloadPipeline;
import com.yohu.urldown.download.MediaFileNames;
import com.yohu.urldown.download.hls.HlsDownloader;
import com.yohu.urldown.settings.AppSettings;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 任务调度：队列、并发上限、暂停 / 继续 / 取消、断网等待与自动续传。
 *
 * <p>所有状态迁移都在主线程完成，并经过 {@link DownloadTask.Status#canMoveTo} 校验；
 * 下载本身在后台线程跑 {@link DownloadPipeline}，结果切回主线程收尾。
 *
 * <ul>
 *   <li><b>并发</b>：同时运行的任务数不超过设置里的“同时下载”，其余按入队先后排队；</li>
 *   <li><b>网络</b>：断网或不满足“仅 Wi‑Fi”时，排队中的任务进入“等待网络”，运行中的任务停在断点；
 *       网络恢复后自动回到队列。下载途中因网络失败的任务最多自动续传
 *       {@value #MAX_AUTO_RESUMES} 次，之后判为失败；</li>
 *   <li><b>直链过期</b>：403 / 404 / 410 时用原链接重新解析一次，拿到新地址再续传；</li>
 *   <li><b>进程退出</b>：没下完的任务恢复为暂停，启动后自动继续。</li>
 * </ul>
 *
 * <p>运行期间由 {@link DownloadService} 保持前台，通知栏显示总进度。
 */
public final class DownloadRunner {

    static final int MAX_AUTO_RESUMES = 3;
    /** 在线但下载因网络失败时，第 n 次自动续传前的等待。 */
    private static final long[] BACKOFF_MS = {3_000L, 10_000L, 30_000L};

    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "yo-download");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Nullable
    private static Context app;
    @Nullable
    private static NetworkMonitor network;

    private DownloadRunner() {
    }

    @NonNull
    public static File downloadDir(@NonNull Context context) {
        return new File(context.getApplicationContext().getExternalFilesDir(null), "downloads");
    }

    /** 进程内调用一次（在 {@link TaskStore#init} 之后）：监听网络，继续上次没下完的任务。 */
    @MainThread
    public static void init(@NonNull Context context) {
        if (app != null) {
            return;
        }
        app = context.getApplicationContext();
        network = new NetworkMonitor(app, DownloadRunner::onConditionsChanged);
        List<DownloadTask> all = TaskStore.get().all();
        // 按原来的先后继续
        for (int i = all.size() - 1; i >= 0; i--) {
            DownloadTask t = all.get(i);
            if (t.interrupted && t.canResume()) {
                t.interrupted = false;
                queue(t);
            }
        }
    }

    // ------------------------------------------------------------------ 操作

    @MainThread
    @NonNull
    public static DownloadTask enqueue(@NonNull Context context, @NonNull UrlParseResult result) {
        init(context);
        DownloadTask task = TaskStore.get().add(result);
        queue(task);
        return task;
    }

    /** 暂停：排队 / 等网络的立即暂停；运行中的停在断点后暂停。 */
    @MainThread
    public static boolean pause(@NonNull DownloadTask task) {
        if (!task.canPause()) {
            return false;
        }
        if (task.status == DownloadTask.Status.RUNNING && task.control != null) {
            task.pausedForNetwork = false;
            task.control.pause();
            return true;
        }
        if (!task.moveTo(DownloadTask.Status.PAUSED)) {
            return false;
        }
        task.error = null;
        task.failure = null;
        task.speedBps = 0;
        TaskStore.get().changed();
        pump();
        return true;
    }

    @MainThread
    public static boolean resume(@NonNull Context context, @NonNull DownloadTask task) {
        init(context);
        if (!task.canResume()) {
            return false;
        }
        task.autoResumes = 0;
        return queue(task);
    }

    /** 失败 / 取消的任务重新开始；从磁盘恢复的任务会先按原链接重新解析。 */
    @MainThread
    public static boolean retry(@NonNull Context context, @NonNull DownloadTask task) {
        init(context);
        if (!task.canRetry()) {
            return false;
        }
        task.autoResumes = 0;
        return queue(task);
    }

    /** 重试全部失败的任务，返回条数。 */
    @MainThread
    public static int retryAll(@NonNull Context context) {
        List<DownloadTask> list = TaskStore.get().all();
        int n = 0;
        // 列表是新的在前；按时间正序重新入队，保持原来的先后
        for (int i = list.size() - 1; i >= 0; i--) {
            DownloadTask t = list.get(i);
            if (t.status == DownloadTask.Status.FAILED && retry(context, t)) {
                n++;
            }
        }
        return n;
    }

    /** 暂停全部进行中的任务，返回条数。 */
    @MainThread
    public static int pauseAll() {
        int n = 0;
        for (DownloadTask t : TaskStore.get().all()) {
            if (pause(t)) {
                n++;
            }
        }
        return n;
    }

    /** 继续全部暂停的任务，返回条数。 */
    @MainThread
    public static int resumeAll(@NonNull Context context) {
        List<DownloadTask> list = TaskStore.get().all();
        int n = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (resume(context, list.get(i))) {
                n++;
            }
        }
        return n;
    }

    /** 取消未结束的任务，丢弃未完成的部分。 */
    @MainThread
    public static void cancel(@NonNull DownloadTask task) {
        if (!task.status.isUnfinished()) {
            return;
        }
        if (task.status == DownloadTask.Status.RUNNING && task.control != null) {
            // 运行中：等流水线停下后由 onJobDone 收尾，避免状态和磁盘不一致
            task.control.cancel();
            return;
        }
        if (!task.moveTo(DownloadTask.Status.CANCELED)) {
            return;
        }
        markEnded(task, null, null);
        discardPartials(task, task.files);
        task.files = new ArrayList<>();
        TaskStore.get().changed();
        pump();
    }

    /** 设置（并发数、仅 Wi‑Fi）变化后调用。 */
    @MainThread
    public static void onSettingsChanged() {
        onConditionsChanged();
    }

    // ------------------------------------------------------------------ 调度

    @MainThread
    private static boolean queue(@NonNull DownloadTask task) {
        if (!task.moveTo(DownloadTask.Status.QUEUED)) {
            return false;
        }
        task.control = null;
        task.pausedForNetwork = false;
        task.stage = DownloadTask.Stage.WAITING;
        task.enqueuedAt = System.nanoTime();
        task.finishedAt = 0;
        task.error = null;
        task.failure = null;
        task.speedBps = 0;
        TaskStore.get().changed();
        pump();
        return true;
    }

    /** 网络或设置变了：等网络的回到队列，或运行中的让路。 */
    @MainThread
    private static void onConditionsChanged() {
        if (app == null) {
            return;
        }
        if (networkOk()) {
            boolean any = false;
            for (DownloadTask t : TaskStore.get().all()) {
                if (t.status == DownloadTask.Status.WAITING_NETWORK && t.control == null
                        && t.moveTo(DownloadTask.Status.QUEUED)) {
                    t.error = null;
                    t.failure = null;
                    any = true;
                }
            }
            if (any) {
                TaskStore.get().changed();
            }
        }
        pump();
    }

    /** 按并发上限与网络条件推进队列。 */
    @MainThread
    private static void pump() {
        Context context = app;
        if (context == null) {
            return;
        }
        TaskStore store = TaskStore.get();
        List<DownloadTask> all = store.all();
        boolean ok = networkOk();
        int running = 0;
        for (DownloadTask t : all) {
            if (t.status == DownloadTask.Status.RUNNING) {
                running++;
                if (!ok && t.control != null && !t.control.isCanceled()) {
                    // 不满足网络条件：停在断点，结束后进“等待网络”
                    t.pausedForNetwork = true;
                    t.control.pause();
                }
            }
        }
        List<DownloadTask> queued = new ArrayList<>();
        for (DownloadTask t : all) {
            if (t.status == DownloadTask.Status.QUEUED) {
                queued.add(t);
            }
        }
        queued.sort(Comparator.comparingLong(DownloadTask::enqueuedAt));
        int limit = AppSettings.of(context).maxParallel();
        boolean changed = false;
        for (DownloadTask t : queued) {
            if (!ok) {
                if (t.moveTo(DownloadTask.Status.WAITING_NETWORK)) {
                    t.error = waitingReason();
                    t.failure = context.getString(t.error.message());
                    changed = true;
                }
            } else if (running < limit) {
                start(context, t);
                running++;
            }
        }
        if (changed) {
            store.changed();
        }
        DownloadService.sync(context);
    }

    private static boolean networkOk() {
        NetworkMonitor n = network;
        if (n == null || app == null) {
            return true;
        }
        return n.isOnline() && (!AppSettings.of(app).wifiOnly() || n.isUnmetered());
    }

    @NonNull
    private static DownloadError waitingReason() {
        NetworkMonitor n = network;
        return n != null && n.isOnline() ? DownloadError.WIFI_REQUIRED : DownloadError.NETWORK;
    }

    @MainThread
    private static void start(@NonNull Context context, @NonNull DownloadTask task) {
        if (!task.moveTo(DownloadTask.Status.RUNNING)) {
            return;
        }
        DownloadPipeline.Control control = new DownloadPipeline.Control();
        task.control = control;
        task.stage = task.source == null ? DownloadTask.Stage.PARSING : DownloadTask.Stage.DOWNLOADING;
        task.speedBps = 0;
        TaskStore.get().changed();
        File dir = downloadDir(context);
        String key = task.key();
        UrlParseResult source = task.source;
        String sourceUrl = task.sourceUrl;
        WORKERS.execute(() -> runJob(context, task, control, source, sourceUrl, dir, key));
    }

    // ------------------------------------------------------------------ 后台

    @WorkerThread
    private static void runJob(Context context, DownloadTask task, DownloadPipeline.Control control,
                               @Nullable UrlParseResult source, @Nullable String sourceUrl,
                               File dir, String key) {
        UrlParseResult src = source;
        boolean refreshed = false;
        if (src == null) {
            src = reparse(context, task, control, sourceUrl);
            if (src == null) {
                return;
            }
            refreshed = true;
        }
        while (true) {
            DownloadPipeline.Report report;
            try {
                report = DownloadPipeline.run(src, dir, key, new ProgressBridge(task, control), control);
            } catch (Throwable t) {
                report = new DownloadPipeline.Report();
                report.lines.add("下载异常：" + t);
                report.error = DownloadError.UNKNOWN;
            }
            if (report.outcome == DownloadPipeline.Outcome.FAILED && report.error != null
                    && report.error.refreshesLink() && !refreshed && !control.isPaused()
                    && !control.isCanceled() && sourceUrl != null) {
                // 直链过期：按原链接重新解析一次，引擎请求 ID 不变，能接着断点下
                UrlParseResult fresh = reparse(context, task, control, sourceUrl);
                if (fresh == null) {
                    return;
                }
                src = fresh;
                refreshed = true;
                continue;
            }
            DownloadPipeline.Report done = report;
            MAIN.post(() -> onJobDone(task, control, done, null));
            return;
        }
    }

    /** 重新解析原链接；失败时直接收尾并返回 null。 */
    @WorkerThread
    @Nullable
    private static UrlParseResult reparse(Context context, DownloadTask task,
                                          DownloadPipeline.Control control, @Nullable String url) {
        MAIN.post(() -> {
            if (task.control == control) {
                task.stage = DownloadTask.Stage.PARSING;
                TaskStore.get().progressed(task);
            }
        });
        DownloadPipeline.Report failed = new DownloadPipeline.Report();
        if (url == null || url.isEmpty()) {
            failed.error = DownloadError.PARSE;
            MAIN.post(() -> onJobDone(task, control, failed, null));
            return null;
        }
        try {
            UrlParseResult parsed = EngineBootstrap.engine(context)
                    .parse(UrlParseRequest.builder(url).build());
            MAIN.post(() -> {
                if (task.control == control) {
                    task.source = parsed;
                    task.fileCount = parsed.downloadable().size();
                    task.stage = DownloadTask.Stage.DOWNLOADING;
                    TaskStore.get().progressed(task);
                }
            });
            return parsed;
        } catch (ParseFailure f) {
            failed.lines.add("重新解析失败: " + f.reason() + " / " + f.getMessage());
            failed.error = f.reason() == FailureReason.NETWORK ? DownloadError.NETWORK : DownloadError.PARSE;
            String hint = f.userHint();
            MAIN.post(() -> onJobDone(task, control, failed, hint));
        } catch (Throwable t) {
            failed.lines.add("重新解析异常: " + t);
            failed.error = DownloadError.PARSE;
            MAIN.post(() -> onJobDone(task, control, failed, null));
        }
        return null;
    }

    // ------------------------------------------------------------------ 收尾

    @MainThread
    private static void onJobDone(DownloadTask task, DownloadPipeline.Control control,
                                  DownloadPipeline.Report report, @Nullable String hint) {
        if (task.control != control) {
            return;
        }
        Context context = app;
        task.control = null;
        task.speedBps = 0;
        task.report = report.text();
        boolean forNetwork = task.pausedForNetwork;
        task.pausedForNetwork = false;
        switch (report.outcome) {
            case SUCCESS -> {
                task.moveTo(DownloadTask.Status.SUCCESS);
                task.autoResumes = 0;
                task.files = new ArrayList<>(report.files);
                long bytes = 0;
                for (File f : report.files) {
                    bytes += f.length();
                }
                task.savedBytes = bytes;
                markEnded(task, null, null);
            }
            case CANCELED -> {
                task.moveTo(DownloadTask.Status.CANCELED);
                markEnded(task, null, null);
                discardPartials(task, report.files);
                task.files = new ArrayList<>();
            }
            case PAUSED -> {
                if (forNetwork) {
                    task.moveTo(DownloadTask.Status.WAITING_NETWORK);
                    task.error = waitingReason();
                    task.failure = context == null ? null : context.getString(task.error.message());
                } else {
                    task.moveTo(DownloadTask.Status.PAUSED);
                    task.error = null;
                    task.failure = null;
                }
            }
            case FAILED -> {
                DownloadError e = report.error == null ? DownloadError.UNKNOWN : report.error;
                String message = context == null ? e.name() : context.getString(e.message());
                if (e.waitsForNetwork() && task.autoResumes < MAX_AUTO_RESUMES) {
                    task.moveTo(DownloadTask.Status.WAITING_NETWORK);
                    task.error = e;
                    task.failure = message;
                    long delay = BACKOFF_MS[Math.min(task.autoResumes, BACKOFF_MS.length - 1)];
                    task.autoResumes++;
                    // 网络看起来正常（多半是抖动）：等一会儿自己回到队列；真断网则等网络回调
                    MAIN.postDelayed(() -> {
                        if (task.status == DownloadTask.Status.WAITING_NETWORK && task.control == null
                                && networkOk() && task.moveTo(DownloadTask.Status.QUEUED)) {
                            task.error = null;
                            task.failure = null;
                            TaskStore.get().changed();
                            pump();
                        }
                    }, delay);
                } else {
                    task.moveTo(DownloadTask.Status.FAILED);
                    markEnded(task, e, hint == null || hint.isEmpty() ? message : message + "：" + hint);
                    task.files = new ArrayList<>(report.files);
                }
            }
        }
        TaskStore.get().changed();
        pump();
    }

    private static void markEnded(DownloadTask task, @Nullable DownloadError error,
                                  @Nullable String failure) {
        task.error = error;
        task.failure = failure;
        task.finishedAt = System.currentTimeMillis();
        task.speedBps = 0;
    }

    /** 取消后清理：已完成的文件、引擎断点与临时文件、HLS 分片断点。 */
    private static void discardPartials(DownloadTask task, List<File> done) {
        List<File> files = new ArrayList<>(done);
        UrlParseResult source = task.source;
        int count = Math.max(task.fileCount, source == null ? 0 : source.downloadable().size());
        String key = task.key();
        Context context = app;
        WORKERS.execute(() -> {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            for (int i = 1; i <= count; i++) {
                AssetDownloader.forget(key + "-" + i, true);
            }
            if (source != null && context != null) {
                File dir = downloadDir(context);
                List<MediaAsset> assets = source.downloadable();
                for (int i = 0; i < assets.size(); i++) {
                    String name = MediaFileNames.forAsset(source, assets.get(i), i + 1);
                    HlsDownloader.discardPartial(new File(dir, MediaFileNames.withExtension(name, "ts")));
                }
            }
        });
    }

    /** 流水线回调 → 主线程写字段 → 只刷新这一张卡。 */
    private static final class ProgressBridge implements DownloadPipeline.Listener {
        private final DownloadTask task;
        private final DownloadPipeline.Control control;

        ProgressBridge(DownloadTask task, DownloadPipeline.Control control) {
            this.task = task;
            this.control = control;
        }

        @Override
        public void onFileStart(int index, int count) {
            post(() -> {
                task.fileIndex = index;
                task.fileCount = count;
                task.fileDone = 0;
                task.fileTotal = 0;
            });
        }

        @Override
        public void onFileProgress(long downloaded, long total, long speed) {
            post(() -> {
                task.fileDone = downloaded;
                task.fileTotal = total;
                task.speedBps = speed;
            });
        }

        @Override
        public void onStage(@NonNull DownloadPipeline.Stage stage) {
            post(() -> task.stage = switch (stage) {
                case DOWNLOADING -> DownloadTask.Stage.DOWNLOADING;
                case MERGING -> DownloadTask.Stage.MERGING;
                case VERIFYING -> DownloadTask.Stage.VERIFYING;
            });
        }

        private void post(Runnable r) {
            MAIN.post(() -> {
                if (task.control != control || task.status != DownloadTask.Status.RUNNING) {
                    return;
                }
                r.run();
                TaskStore.get().progressed(task);
                DownloadService.onProgress();
            });
        }
    }
}
