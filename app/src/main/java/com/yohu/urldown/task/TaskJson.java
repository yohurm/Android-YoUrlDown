package com.yohu.urldown.task;

import androidx.annotation.NonNull;

import com.yohu.urldown.download.DownloadError;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务列表的落盘格式（JSON）。只存展示和继续 / 重试需要的摘要，不存媒体直链：
 * 直链普遍几小时就过期，存了也没用，继续时按原链接重新解析。
 *
 * <p>版本 2 增加了平台标识、类型化错误与暂停时的进度；版本 1 的记录照常读取。
 */
final class TaskJson {

    static final int VERSION = 2;

    private TaskJson() {
    }

    @NonNull
    static String encode(@NonNull List<DownloadTask> tasks) {
        JSONArray array = new JSONArray();
        try {
            for (DownloadTask t : tasks) {
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("title", t.title);
                o.put("platform", t.platform);
                o.put("platformId", t.platformId);
                o.putOpt("author", t.author);
                o.putOpt("sourceUrl", t.sourceUrl);
                o.putOpt("coverUrl", t.coverUrl);
                o.put("video", t.videoCount);
                o.put("image", t.imageCount);
                o.put("audio", t.audioCount);
                o.put("createdAt", t.createdAt);
                o.put("finishedAt", t.finishedAt);
                o.put("status", t.status.name());
                o.putOpt("error", t.error == null ? null : t.error.name());
                o.putOpt("failure", t.failure);
                o.putOpt("report", t.report);
                o.put("savedBytes", t.savedBytes);
                if (t.status.isUnfinished()) {
                    // 继续时进度条从这里接上，不从 0 跳起
                    o.put("fileIndex", t.fileIndex);
                    o.put("fileCount", t.fileCount);
                    o.put("fileDone", t.fileDone);
                    o.put("fileTotal", t.fileTotal);
                }
                JSONArray files = new JSONArray();
                for (File f : t.files) {
                    files.put(f.getAbsolutePath());
                }
                o.put("files", files);
                array.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            root.put("tasks", array);
            return root.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 解析落盘内容。排队 / 运行 / 等网络的任务说明上次进程在下载途中退出：
     * 恢复成“暂停”并标记 {@code interrupted}，启动后由调度器自动继续。
     */
    @NonNull
    static List<DownloadTask> decode(@NonNull String json) {
        List<DownloadTask> out = new ArrayList<>();
        try {
            JSONArray array = new JSONObject(json).optJSONArray("tasks");
            if (array == null) {
                return out;
            }
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                DownloadTask t = new DownloadTask(
                        o.getLong("id"),
                        o.optString("title", "未命名作品"),
                        o.optString("platform", ""),
                        o.optString("platformId", ""),
                        optString(o, "author"),
                        optString(o, "sourceUrl"),
                        optString(o, "coverUrl"),
                        o.optInt("video"), o.optInt("image"), o.optInt("audio"),
                        o.optLong("createdAt"));
                DownloadTask.Status status;
                try {
                    status = DownloadTask.Status.valueOf(o.optString("status"));
                } catch (IllegalArgumentException e) {
                    status = DownloadTask.Status.FAILED;
                }
                t.error = errorOf(optString(o, "error"));
                t.failure = optString(o, "failure");
                t.report = optString(o, "report");
                t.finishedAt = o.optLong("finishedAt");
                t.savedBytes = o.optLong("savedBytes");
                t.fileIndex = o.optInt("fileIndex");
                t.fileCount = o.optInt("fileCount");
                t.fileDone = o.optLong("fileDone");
                t.fileTotal = o.optLong("fileTotal");
                if (status.isActive()) {
                    status = DownloadTask.Status.PAUSED;
                    t.interrupted = true;
                    t.error = null;
                    t.failure = null;
                }
                t.status = status;
                JSONArray files = o.optJSONArray("files");
                List<File> list = new ArrayList<>();
                if (files != null) {
                    for (int k = 0; k < files.length(); k++) {
                        list.add(new File(files.optString(k)));
                    }
                }
                t.files = list;
                out.add(t);
            }
        } catch (JSONException e) {
            // 文件损坏：宁可丢掉记录也不要启动崩溃；已下载的文件仍在保存目录里。
            return new ArrayList<>();
        }
        return out;
    }

    private static DownloadError errorOf(String name) {
        if (name == null) {
            return null;
        }
        try {
            return DownloadError.valueOf(name);
        } catch (IllegalArgumentException e) {
            return DownloadError.UNKNOWN;
        }
    }

    private static String optString(JSONObject o, String key) {
        return o.isNull(key) ? null : o.optString(key, null);
    }
}
