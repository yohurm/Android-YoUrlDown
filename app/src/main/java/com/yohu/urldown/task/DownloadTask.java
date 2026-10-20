package com.yohu.urldown.task;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.yohu.lib_urlparse.api.MediaAsset;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.urldown.download.DownloadError;
import com.yohu.urldown.download.DownloadPipeline;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一条下载任务。字段只在主线程改（{@link DownloadRunner} 负责切线程），界面只读。
 *
 * <p>{@link #source} 是内存里的完整解析结果；从磁盘恢复的任务没有它
 * （媒体直链会过期，也不值得落盘），继续 / 重试时用 {@link #sourceUrl} 重新解析。
 */
public final class DownloadTask {

    /**
     * 任务状态。合法迁移只有 {@link #canMoveTo} 表里的这些，其余一律拒绝；
     * 完整状态图见 {@code docs/design/07-操作流程.md}。
     */
    public enum Status {
        /** 等空位。 */
        QUEUED,
        /** 正在解析 / 下载 / 合并 / 校验。 */
        RUNNING,
        /** 用户暂停，或应用退出时中断；断点保留。 */
        PAUSED,
        /** 断网或不满足“仅 Wi‑Fi”，网络恢复后自动继续。 */
        WAITING_NETWORK,
        SUCCESS,
        FAILED,
        CANCELED;

        private static final Map<Status, Set<Status>> MOVES = new EnumMap<>(Status.class);

        static {
            MOVES.put(QUEUED, EnumSet.of(RUNNING, PAUSED, WAITING_NETWORK, CANCELED));
            MOVES.put(RUNNING, EnumSet.of(PAUSED, WAITING_NETWORK, SUCCESS, FAILED, CANCELED));
            MOVES.put(PAUSED, EnumSet.of(QUEUED, CANCELED));
            MOVES.put(WAITING_NETWORK, EnumSet.of(QUEUED, PAUSED, CANCELED, FAILED));
            MOVES.put(SUCCESS, EnumSet.noneOf(Status.class));
            MOVES.put(FAILED, EnumSet.of(QUEUED));
            MOVES.put(CANCELED, EnumSet.of(QUEUED));
        }

        public boolean canMoveTo(@NonNull Status next) {
            Set<Status> allowed = MOVES.get(this);
            return allowed != null && allowed.contains(next);
        }

        /** 已结束：不会再自己变化。 */
        public boolean isFinished() {
            return this == SUCCESS || this == FAILED || this == CANCELED;
        }

        /** 占着或等着下载名额（会自己往前走）。 */
        public boolean isActive() {
            return this == QUEUED || this == RUNNING || this == WAITING_NETWORK;
        }

        /** 未结束：进行中 + 暂停。任务页“进行中”分组就是它。 */
        public boolean isUnfinished() {
            return !isFinished();
        }
    }

    /** 进行中的细分阶段。 */
    public enum Stage {
        WAITING, PARSING, DOWNLOADING, MERGING, VERIFYING
    }

    public final long id;
    @NonNull
    public final String title;
    /** 平台展示名（中文）。 */
    @NonNull
    public final String platform;
    /** 平台稳定标识（{@code Platform.id()}），用于图标；旧记录可能为空串。 */
    @NonNull
    public final String platformId;
    @Nullable
    public final String author;
    @Nullable
    public final String sourceUrl;
    @Nullable
    public final String coverUrl;
    @NonNull
    public final Map<String, String> coverHeaders;
    public final int videoCount;
    public final int imageCount;
    public final int audioCount;
    public final long createdAt;

    @Nullable
    UrlParseResult source;
    @NonNull
    Status status = Status.QUEUED;
    @NonNull
    Stage stage = Stage.WAITING;
    /** 进入队列的时间，用来排先后、算“前面还有几个”。 */
    long enqueuedAt;
    long finishedAt;
    /** 失败原因（类型化）；非失败时为 null。 */
    @Nullable
    DownloadError error;
    /** 失败 / 取消 / 暂停时给用户看的一句话。 */
    @Nullable
    String failure;
    @Nullable
    String report;
    @NonNull
    List<File> files = Collections.emptyList();
    long savedBytes;

    int fileIndex;
    int fileCount;
    long fileDone;
    long fileTotal;
    long speedBps;
    /** 本轮因断网自动续传的次数，超过上限转为失败。用户手动继续 / 重试时清零。 */
    int autoResumes;
    /** 上次进程退出时还没下完，启动后自动继续。 */
    boolean interrupted;

    @Nullable
    DownloadPipeline.Control control;
    /** 当前这次暂停是调度器因网络发起的（结束后进 WAITING_NETWORK 而不是 PAUSED）。 */
    boolean pausedForNetwork;

    DownloadTask(long id, @NonNull UrlParseResult source, long createdAt) {
        this.id = id;
        this.source = source;
        String t = source.title();
        this.title = t == null || t.trim().isEmpty() ? "未命名作品" : t.trim();
        this.platform = source.platform().displayName();
        this.platformId = source.platform().id();
        this.author = source.author() == null ? null : source.author().nickname();
        this.sourceUrl = source.sourceUrl();
        List<MediaAsset> covers = source.assets(MediaAsset.Kind.COVER);
        this.coverUrl = covers.isEmpty() ? null : covers.get(0).url();
        this.coverHeaders = covers.isEmpty() ? Collections.emptyMap() : covers.get(0).headers();
        this.videoCount = source.assets(MediaAsset.Kind.VIDEO).size();
        this.imageCount = source.assets(MediaAsset.Kind.IMAGE).size();
        this.audioCount = source.assets(MediaAsset.Kind.AUDIO).size();
        this.createdAt = createdAt;
        this.fileCount = source.downloadable().size();
    }

    /** 从磁盘恢复用。 */
    DownloadTask(long id, @NonNull String title, @NonNull String platform, @NonNull String platformId,
                 @Nullable String author, @Nullable String sourceUrl, @Nullable String coverUrl,
                 int videoCount, int imageCount, int audioCount, long createdAt) {
        this.id = id;
        this.source = null;
        this.title = title;
        this.platform = platform;
        this.platformId = platformId;
        this.author = author;
        this.sourceUrl = sourceUrl;
        this.coverUrl = coverUrl;
        this.coverHeaders = Collections.emptyMap();
        this.videoCount = videoCount;
        this.imageCount = imageCount;
        this.audioCount = audioCount;
        this.createdAt = createdAt;
    }

    /** 引擎请求 ID 的前缀：同一任务多次运行不变，断点才能接上。 */
    @NonNull
    String key() {
        return "task-" + id + "-" + createdAt;
    }

    /**
     * 迁移状态；不合法的迁移返回 false 且不改动。同状态视为合法（幂等）。
     */
    boolean moveTo(@NonNull Status next) {
        if (status == next) {
            return true;
        }
        if (!status.canMoveTo(next)) {
            return false;
        }
        status = next;
        return true;
    }

    @NonNull
    public Status status() {
        return status;
    }

    @NonNull
    public Stage stage() {
        return stage;
    }

    public long enqueuedAt() {
        return enqueuedAt;
    }

    /** 结束时间；未结束为 0。 */
    public long finishedAt() {
        return finishedAt;
    }

    /** 用来分组和排序的时间：结束了用结束时间，否则用创建时间。 */
    public long sortTime() {
        return finishedAt > 0 ? finishedAt : createdAt;
    }

    @Nullable
    public DownloadError error() {
        return error;
    }

    /** 失败 / 取消 / 暂停时给用户看的一句话。 */
    @Nullable
    public String failure() {
        return failure;
    }

    /** 下载流程输出的逐行记录；还没跑完时为 null。 */
    @Nullable
    public String report() {
        return report;
    }

    @NonNull
    public List<File> files() {
        return new ArrayList<>(files);
    }

    /** 仍在磁盘上的已保存文件。 */
    @NonNull
    public List<File> existingFiles() {
        List<File> out = new ArrayList<>();
        for (File f : files) {
            if (f.isFile()) {
                out.add(f);
            }
        }
        return out;
    }

    public long savedBytes() {
        return savedBytes;
    }

    /** 能否重试：失败或取消，且还拿得到解析结果或原链接。 */
    public boolean canRetry() {
        return (status == Status.FAILED || status == Status.CANCELED) && hasSource();
    }

    /** 能否继续：暂停中，且还拿得到解析结果或原链接。 */
    public boolean canResume() {
        return status == Status.PAUSED && hasSource();
    }

    public boolean canPause() {
        return status.isActive();
    }

    private boolean hasSource() {
        return source != null || (sourceUrl != null && !sourceUrl.isEmpty());
    }

    public int fileIndex() {
        return fileIndex;
    }

    public int fileCount() {
        return fileCount;
    }

    public long fileDone() {
        return fileDone;
    }

    public long fileTotal() {
        return fileTotal;
    }

    public long speedBps() {
        return speedBps;
    }

    /** 当前文件预计还要多久（毫秒）；算不出时为 -1。 */
    public long etaMs() {
        if (status != Status.RUNNING || speedBps <= 0 || fileTotal <= 0 || fileDone >= fileTotal) {
            return -1;
        }
        return (fileTotal - fileDone) * 1000L / speedBps;
    }

    /**
     * 整体进度 0..1；不确定（还没拿到总大小、正在解析 / 合并）时返回 -1。
     * 多个文件时按文件数平均，避免第二个文件开始时进度条倒退。
     * 暂停 / 等网络时保留最后的进度。
     */
    public float progress() {
        boolean holding = status == Status.PAUSED || status == Status.WAITING_NETWORK;
        if (!(status == Status.RUNNING && stage == Stage.DOWNLOADING) && !holding) {
            return -1f;
        }
        if (fileCount <= 0 || fileIndex <= 0 || fileTotal <= 0) {
            return -1f;
        }
        float current = Math.min(1f, fileDone / (float) fileTotal);
        return Math.min(1f, ((fileIndex - 1) + current) / fileCount);
    }
}
