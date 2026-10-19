package com.yohu.urldown.download;

import com.yohu.lib_urlparse.api.MediaAsset;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.lib_urlparse.transfer.ContainerProbe;
import com.yohu.lib_urlparse.transfer.FileNames;

import com.yohu.urldown.adapter.AndroidMediaMuxer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 一次解析结果的落盘流程：命名 → 下载 → （音视频分离时）合并 → 容器校验。
 *
 * <p>为什么把"合并"和"校验"放进同一条流水线：B 站这类站点的 DASH 是音视频分离的，
 * 只下载会得到两个不可直接播放的文件；而 CDN 出错时又可能把 HTML 错误页存成 {@code .mp4}。
 * 这两件事都必须在"报告下载成功"之前完成，否则报告就是假的。
 *
 * <p>本类**同步阻塞**调用线程（内部用 latch 等下载回调），因此必须在后台线程调用。
 */
public final class DownloadPipeline {

    /** 单个文件的最大等待时间。 */
    private static final long TIMEOUT_MS = 5 * 60 * 1000L;

    /** 落盘结果。 */
    public static final class Report {
        public final List<String> lines = new ArrayList<>();
        public final List<File> files = new ArrayList<>();
        public boolean success;

        void line(String s) {
            lines.add(s);
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
     * @param result 解析结果
     * @param dir    落盘目录（会创建）
     */
    public static Report download(UrlParseResult result, File dir) {
        Report report = new Report();
        if (result == null) {
            report.line("无解析结果");
            return report;
        }
        if (dir == null) {
            report.line("落盘目录为空");
            return report;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            report.line("无法创建目录: " + dir.getAbsolutePath());
            return report;
        }

        List<MediaAsset> assets = result.downloadable();
        report.line("待下载 " + assets.size() + " 条 -> " + dir.getAbsolutePath());
        if (assets.isEmpty()) {
            return report;
        }

        List<File> videos = new ArrayList<>();
        List<File> audios = new ArrayList<>();
        int index = 0;
        for (MediaAsset asset : assets) {
            index++;
            String name = FileNames.forAsset(result, asset, index);
            File target = new File(dir, name);
            if (target.isFile() && target.length() > 0) {
                report.line("已存在，跳过: " + name);
                collect(asset, target, videos, audios);
                continue;
            }
            File saved = downloadOne(asset, dir, name, report);
            if (saved != null) {
                collect(asset, saved, videos, audios);
            }
        }

        // 音视频分离 -> 合并
        if (videos.size() == 1 && audios.size() == 1) {
            File video = videos.get(0);
            File audio = audios.get(0);
            String stem = FileNames.sanitize(
                    result.title() == null || result.title().trim().isEmpty()
                            ? "media" : result.title());
            if (stem.isEmpty()) {
                stem = "media";
            }
            File muxed = new File(dir, stem + ".mp4");
            try {
                report.line("合并音视频 -> " + muxed.getName());
                com.yohu.lib_urlparse.spi.MediaMuxer.Result r =
                        new AndroidMediaMuxer().mux(video, audio, muxed);
                report.line("合并完成: " + r.bytes() + " B (" + r.note() + ")");
                ContainerProbe.Verdict v = ContainerProbe.verify(r.output());
                report.line("容器校验: " + v.container() + " -> " + v.reason());
                if (v.acceptable()) {
                    report.files.add(r.output());
                    // 合并成功后删掉分离轨道，避免用户看到"有画面没声音"的残片
                    deleteQuietly(video);
                    deleteQuietly(audio);
                    report.success = true;
                }
            } catch (Exception e) {
                report.line("合并失败: " + e);
                report.line("（分离轨已保留，可分别播放/自行合并）");
                report.files.add(video);
                report.files.add(audio);
            }
            return report;
        }

        // 单文件：逐个校验
        boolean anyOk = false;
        for (File f : report.files) {
            ContainerProbe.Verdict v = ContainerProbe.verify(f);
            report.line("校验 " + f.getName() + ": " + v.container()
                    + (v.acceptable() ? " ✅" : " ❌ " + v.reason()));
            if (v.acceptable()) {
                anyOk = true;
            } else {
                report.line("  该文件不是可播放媒体，已删除");
                deleteQuietly(f);
            }
        }
        report.success = anyOk;
        return report;
    }

    private static void collect(MediaAsset asset, File file, List<File> videos, List<File> audios) {
        if (asset.kind() == MediaAsset.Kind.VIDEO) {
            videos.add(file);
        } else if (asset.kind() == MediaAsset.Kind.AUDIO) {
            audios.add(file);
        }
    }

    private static File downloadOne(MediaAsset asset, File dir, String name, Report report) {
        final CountDownLatch done = new CountDownLatch(1);
        final File[] saved = new File[1];
        final String[] error = new String[1];
        report.line("下载 " + asset.kind() + ": " + name);
        AssetDownloader.download(asset, dir, name, name, new AssetDownloader.Callback() {
            @Override
            public void onProgress(int percent, long downloadedBytes, long totalBytes) {
                // 进度在 UI 侧另有展示；这里不刷屏
            }

            @Override
            public void onSuccess(File file) {
                saved[0] = file;
                done.countDown();
            }

            @Override
            public void onError(String message) {
                error[0] = message;
                done.countDown();
            }
        });
        try {
            if (!done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                report.line("  超时");
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (saved[0] == null) {
            report.line("  失败: " + error[0]);
            return null;
        }
        report.line("  完成: " + saved[0].getName() + " (" + saved[0].length() + " B)");
        report.files.add(saved[0]);
        return saved[0];
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
