package com.yohu.urldown.download;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.yohu.lib_urlparse.api.MediaAsset;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.urldown.download.hls.HlsDownloader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 一次解析结果的落盘流程：命名 → 逐个下载（直链走下载引擎，m3u8 走 {@link HlsDownloader}）
 * → 音视频分离时合并 → 容器校验。
 *
 * <p>“合并”和“校验”必须在报告成功之前完成：DASH 只下载会得到两个不可直接播放的文件；
 * CDN 出错时又可能把 HTML 错误页存成 {@code .mp4}。
 *
 * <p>可暂停：{@link Control#pause()} 让当前文件停在断点（引擎的分块断点 / HLS 的分片断点），
 * 已完成的文件保留在目录里，下次运行时直接跳过，未完成的从断点继续。
 *
 * <p>同步阻塞调用线程，必须在后台线程调用。
 */
public final class DownloadPipeline {

    /** 多久没有任何进度就判定卡死。引擎自身有读超时与重试，这是最后一道保险。 */
    private static final long STALL_TIMEOUT_MS = 120_000L;
    /** 发出暂停 / 取消后最多等引擎多久确认。 */
    private static final long STOP_GRACE_MS = 10_000L;

    /** 当前所处阶段。 */
    public enum Stage {
        DOWNLOADING, MERGING, VERIFYING
    }

    /** 一次运行的结局。 */
    public enum Outcome {
        SUCCESS, FAILED, PAUSED, CANCELED
    }

    /** 进度回调：下载进度可能在主线程（引擎）或调用线程（HLS）触发，实现方自己切线程。 */
    public interface Listener {
        /** 开始第 {@code index}（从 1 起）个文件，共 {@code count} 个。 */
        default void onFileStart(int index, int count) {
        }

        /** @param totalBytes 未知时为 -1；HLS 按已完成分片的平均大小估算 */
        default void onFileProgress(long downloadedBytes, long totalBytes, long speedBytesPerSecond) {
        }

        default void onStage(@NonNull Stage stage) {
        }
    }

    /** 暂停 / 取消控制，可从任意线程调用。 */
    public static final class Control {
        private static final int RUN = 0;
        private static final int PAUSE = 1;
        private static final int CANCEL = 2;

        private volatile int request = RUN;
        @Nullable
        private volatile String engineId;

        /** 停在断点，保留已下载部分。 */
        public void pause() {
            stop(PAUSE);
        }

        /** 停下并丢弃未完成的部分。 */
        public void cancel() {
            stop(CANCEL);
        }

        public boolean isPaused() {
            return request == PAUSE;
        }

        public boolean isCanceled() {
            return request == CANCEL;
        }

        boolean shouldStop() {
            return request != RUN;
        }

        private synchronized void stop(int what) {
            if (request == CANCEL) {
                return;
            }
            request = what;
            forward();
        }

        private synchronized void attach(@Nullable String id) {
            engineId = id;
            if (id != null && shouldStop()) {
                // 停止请求可能发生在拿到引擎 ID 之前
                forward();
            }
        }

        private void forward() {
            String id = engineId;
            if (id == null) {
                return;
            }
            if (request == CANCEL) {
                AssetDownloader.cancel(id);
            } else if (request == PAUSE) {
                AssetDownloader.pause(id);
            }
        }
    }

    /** 落盘结果。 */
    public static final class Report {
        public final List<String> lines = new ArrayList<>();
        public final List<File> files = new ArrayList<>();
        @NonNull
        public Outcome outcome = Outcome.FAILED;
        /** 失败原因；成功 / 暂停 / 取消时为 null。 */
        @Nullable
        public DownloadError error;

        void line(String s) {
            lines.add(s);
        }

        Report fail(@NonNull DownloadError e) {
            outcome = Outcome.FAILED;
            if (error == null) {
                error = e;
            }
            return this;
        }

        public String text() {
            return String.join("\n", lines);
        }
    }

    private DownloadPipeline() {
    }

    /**
     * 下载一条解析结果里的所有可下载媒体。
     *
     * @param result   解析结果
     * @param dir      落盘目录（会创建）
     * @param key      稳定的任务键，用来生成引擎请求 ID（同一任务多次运行不变）
     */
    @WorkerThread
    @NonNull
    public static Report run(@NonNull UrlParseResult result, @NonNull File dir, @NonNull String key,
                             @NonNull Listener listener, @NonNull Control control) {
        Report report = new Report();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            report.line("无法创建目录: " + dir.getAbsolutePath());
            return report.fail(DownloadError.STORAGE);
        }
        List<MediaAsset> assets = result.downloadable();
        report.line("待下载 " + assets.size() + " 条 -> " + dir.getAbsolutePath());
        if (assets.isEmpty()) {
            return report.fail(DownloadError.NO_MEDIA);
        }

        listener.onStage(Stage.DOWNLOADING);
        List<File> videos = new ArrayList<>();
        List<File> audios = new ArrayList<>();
        for (int i = 0; i < assets.size(); i++) {
            if (control.shouldStop()) {
                return stopped(report, control);
            }
            MediaAsset asset = assets.get(i);
            listener.onFileStart(i + 1, assets.size());
            boolean hls = HlsDownloader.isHls(asset.url(), asset.mimeType());
            String name = MediaFileNames.forAsset(result, asset, i + 1);
            if (hls) {
                name = MediaFileNames.withExtension(name, "ts");
            }
            File existing = existingOutput(dir, name, hls);
            if (existing != null) {
                report.line("已存在，跳过: " + existing.getName());
                report.files.add(existing);
                collect(asset, existing, videos, audios);
                continue;
            }
            report.line("下载 " + asset.kind() + (hls ? " (HLS)" : "") + ": " + name);
            File saved = hls
                    ? downloadHls(asset, new File(dir, name), report, listener, control)
                    : downloadDirect(asset, dir, name, key + "-" + (i + 1), report, listener,
                    control);
            if (saved == null) {
                return control.shouldStop() ? stopped(report, control) : report;
            }
            report.files.add(saved);
            collect(asset, saved, videos, audios);
        }

        if (videos.size() == 1 && audios.size() == 1) {
            return merge(result, dir, videos.get(0), audios.get(0), report, listener, control);
        }
        listener.onStage(Stage.VERIFYING);
        return verifyAll(report);
    }

    // ------------------------------------------------------------------ 单个文件

    @Nullable
    private static File downloadDirect(MediaAsset asset, File dir, String name, String requestId,
                                       Report report, Listener listener, Control control) {
        CountDownLatch done = new CountDownLatch(1);
        File[] saved = new File[1];
        DownloadError[] error = new DownloadError[1];
        String[] detail = new String[1];
        long[] lastProgressAt = {System.currentTimeMillis()};
        boolean started = AssetDownloader.start(requestId, asset, dir, name,
                new AssetDownloader.Callback() {
                    @Override
                    public void onProgress(long downloaded, long total, long speed, long eta) {
                        lastProgressAt[0] = System.currentTimeMillis();
                        listener.onFileProgress(downloaded, total, speed);
                    }

                    @Override
                    public void onSuccess(@NonNull File file) {
                        saved[0] = file;
                        done.countDown();
                    }

                    @Override
                    public void onPaused() {
                        done.countDown();
                    }

                    @Override
                    public void onCanceled() {
                        done.countDown();
                    }

                    @Override
                    public void onError(@NonNull DownloadError e, @Nullable String d) {
                        error[0] = e;
                        detail[0] = d;
                        done.countDown();
                    }
                });
        if (!started) {
            report.line("  引擎拒绝了请求");
            report.fail(DownloadError.UNKNOWN);
            return null;
        }
        control.attach(requestId);
        try {
            long stopAskedAt = 0;
            while (!done.await(500, TimeUnit.MILLISECONDS)) {
                long now = System.currentTimeMillis();
                if (control.shouldStop()) {
                    if (stopAskedAt == 0) {
                        stopAskedAt = now;
                    } else if (now - stopAskedAt > STOP_GRACE_MS) {
                        break;
                    }
                } else if (now - lastProgressAt[0] > STALL_TIMEOUT_MS) {
                    report.line("  长时间没有数据，已暂停");
                    AssetDownloader.pause(requestId);
                    done.await(STOP_GRACE_MS, TimeUnit.MILLISECONDS);
                    report.fail(DownloadError.STALLED);
                    return null;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            AssetDownloader.pause(requestId);
            report.fail(DownloadError.UNKNOWN);
            return null;
        } finally {
            control.attach(null);
        }
        if (saved[0] != null) {
            AssetDownloader.forget(requestId, false);
            report.line("  完成: " + saved[0].getName() + " (" + saved[0].length() + " B)");
            return saved[0];
        }
        if (control.isCanceled()) {
            AssetDownloader.forget(requestId, true);
            return null;
        }
        if (control.isPaused()) {
            report.line("  已暂停，保留断点");
            return null;
        }
        DownloadError e = error[0] == null ? DownloadError.UNKNOWN : error[0];
        report.line("  失败: " + e + (detail[0] == null ? "" : " (" + detail[0] + ")"));
        report.fail(e);
        return null;
    }

    @Nullable
    private static File downloadHls(MediaAsset asset, File target, Report report, Listener listener,
                                    Control control) {
        try {
            File out = HlsDownloader.download(asset.url(), asset.headers(), target,
                    (bytes, doneSegments, totalSegments, speed) -> {
                        long estimate = doneSegments > 0 ? bytes * totalSegments / doneSegments : -1;
                        listener.onFileProgress(bytes, estimate, speed);
                    }, control::shouldStop);
            // fMP4 分片串接后是 MP4，扩展名跟着内容走
            if (ContainerProbe.probe(out) == ContainerProbe.Container.MP4) {
                File mp4 = new File(out.getParentFile(), MediaFileNames.withExtension(out.getName(), "mp4"));
                if ((!mp4.exists() || mp4.delete()) && out.renameTo(mp4)) {
                    out = mp4;
                }
            }
            report.line("  完成: " + out.getName() + " (" + out.length() + " B)");
            return out;
        } catch (HlsDownloader.HlsException e) {
            if (e.kind == HlsDownloader.Kind.CANCELED || control.shouldStop()) {
                if (control.isCanceled()) {
                    HlsDownloader.discardPartial(target);
                } else {
                    report.line("  已暂停，保留已完成的分片");
                }
                return null;
            }
            DownloadError err = DownloadError.ofHls(e);
            report.line("  失败: " + err + " (" + e.getMessage() + ")");
            report.fail(err);
            return null;
        }
    }

    /** 上次运行已完成的文件：直链看原名；HLS 的输出可能被改成了 .mp4。 */
    @Nullable
    private static File existingOutput(File dir, String name, boolean hls) {
        File f = new File(dir, name);
        if (f.isFile() && f.length() > 0) {
            return f;
        }
        if (hls) {
            File mp4 = new File(dir, MediaFileNames.withExtension(name, "mp4"));
            if (mp4.isFile() && mp4.length() > 0) {
                return mp4;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 合并与校验

    private static Report merge(UrlParseResult result, File dir, File video, File audio,
                                Report report, Listener listener, Control control) {
        listener.onStage(Stage.MERGING);
        String stem = MediaFileNames.sanitize(result.title() == null ? "" : result.title());
        File muxed = new File(dir, (stem.isEmpty() ? "media" : stem) + ".mp4");
        // 先写到临时名：视频轨本身可能就叫 <标题>.mp4，边读边覆盖会写坏
        File partial = new File(dir, muxed.getName() + ".muxing");
        try {
            report.line("合并音视频 -> " + muxed.getName());
            DashMuxer.Result r = DashMuxer.mux(video, audio, partial, control::isCanceled);
            report.line("合并完成: " + r);
        } catch (Exception e) {
            deleteQuietly(partial);
            if (control.isCanceled()) {
                return stopped(report, control);
            }
            report.line("合并失败: " + e);
            report.line("（分离轨已保留，可分别播放）");
            return report.fail(DownloadError.MERGE);
        }
        listener.onStage(Stage.VERIFYING);
        ContainerProbe.Verdict v = ContainerProbe.verify(partial);
        report.line("容器校验: " + v.container() + " -> " + v.reason());
        if (!v.acceptable()) {
            deleteQuietly(partial);
            return report.fail(DownloadError.MERGE);
        }
        // 合并成功后删掉分离轨，避免用户看到“有画面没声音”的残片
        report.files.remove(video);
        report.files.remove(audio);
        deleteQuietly(video);
        deleteQuietly(audio);
        deleteQuietly(muxed);
        if (!partial.renameTo(muxed)) {
            report.line("重命名失败: " + partial.getName());
            muxed = partial;
        }
        report.files.add(muxed);
        report.outcome = Outcome.SUCCESS;
        return report;
    }

    private static Report verifyAll(Report report) {
        boolean anyOk = false;
        for (File f : new ArrayList<>(report.files)) {
            ContainerProbe.Verdict v = ContainerProbe.verify(f);
            report.line("校验 " + f.getName() + ": " + v.container()
                    + (v.acceptable() ? " ✅" : " ❌ " + v.reason()));
            if (v.acceptable()) {
                anyOk = true;
            } else {
                report.line("  该文件不是可播放媒体，已删除");
                report.files.remove(f);
                deleteQuietly(f);
            }
        }
        if (anyOk) {
            report.outcome = Outcome.SUCCESS;
            report.error = null;
            return report;
        }
        return report.fail(DownloadError.NOT_MEDIA);
    }

    private static Report stopped(Report report, Control control) {
        report.outcome = control.isCanceled() ? Outcome.CANCELED : Outcome.PAUSED;
        report.error = null;
        report.line(control.isCanceled() ? "已取消" : "已暂停");
        return report;
    }

    private static void collect(MediaAsset asset, File file, List<File> videos, List<File> audios) {
        if (asset.kind() == MediaAsset.Kind.VIDEO) {
            videos.add(file);
        } else if (asset.kind() == MediaAsset.Kind.AUDIO) {
            audios.add(file);
        }
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
