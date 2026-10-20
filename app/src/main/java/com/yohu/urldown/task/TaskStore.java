package com.yohu.urldown.task;

import android.content.Context;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.yohu.lib_urlparse.api.UrlParseResult;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 进程内的任务列表，最新的在前。只在主线程读写。
 *
 * <p>结构变化（新增、状态变化、删除）会落盘到 {@code files/tasks.json}，
 * 进度变化只通知界面、不写盘。调用过 {@link #init} 才会落盘，测试里不调用就是纯内存。
 */
public final class TaskStore {

    /** 列表上限：超出时丢最旧的已结束记录（文件不删）。 */
    static final int MAX_RECORDS = 300;
    private static final String TAG = "YoUrlDown";
    private static final TaskStore INSTANCE = new TaskStore();

    /** 列表监听。结构变化必回调；进度变化只回调 {@link #onTaskProgress}，界面可就地刷新单行。 */
    public interface Listener {
        void onTasksChanged();

        default void onTaskProgress(@NonNull DownloadTask task) {
        }
    }

    private final List<DownloadTask> tasks = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "yo-task-store");
        t.setDaemon(true);
        return t;
    });
    private long nextId = 1;
    @Nullable
    private File file;

    private TaskStore() {
    }

    @NonNull
    public static TaskStore get() {
        return INSTANCE;
    }

    /** 进程内调用一次：读取上次保存的列表。重复调用无副作用。 */
    @MainThread
    public void init(@NonNull Context context) {
        checkMain();
        if (file != null) {
            return;
        }
        file = new File(context.getApplicationContext().getFilesDir(), "tasks.json");
        if (!file.isFile()) {
            return;
        }
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            List<DownloadTask> restored = TaskJson.decode(json);
            tasks.addAll(restored);
            for (DownloadTask t : restored) {
                nextId = Math.max(nextId, t.id + 1);
            }
            notifyChanged();
        } catch (IOException e) {
            Log.w(TAG, "读取任务列表失败: " + e);
        }
    }

    @MainThread
    @NonNull
    DownloadTask add(@NonNull UrlParseResult result) {
        checkMain();
        DownloadTask task = new DownloadTask(nextId++, result, System.currentTimeMillis());
        tasks.add(0, task);
        trim();
        changed();
        return task;
    }

    /** 只给测试造数据：直接放一条指定状态的任务，不触发下载。 */
    @VisibleForTesting
    @MainThread
    @NonNull
    public DownloadTask addForTest(@NonNull UrlParseResult result, @NonNull DownloadTask.Status status) {
        return addForTest(result, status, 0L);
    }

    /** 同上，结束时间往前推 {@code ageMs}，用来造“昨天”的分组。 */
    @VisibleForTesting
    @MainThread
    @NonNull
    public DownloadTask addForTest(@NonNull UrlParseResult result, @NonNull DownloadTask.Status status,
                                   long ageMs) {
        DownloadTask task = add(result);
        task.status = status;
        task.enqueuedAt = task.createdAt + task.id;
        switch (status) {
            case RUNNING -> {
                task.stage = DownloadTask.Stage.DOWNLOADING;
                task.fileIndex = 1;
                task.fileDone = 3_400_000L;
                task.fileTotal = 7_800_000L;
                task.speedBps = 1_250_000L;
                task.fileCount = Math.max(1, task.fileCount);
            }
            case PAUSED -> {
                task.fileIndex = 1;
                task.fileDone = 2_100_000L;
                task.fileTotal = 7_800_000L;
                task.fileCount = Math.max(1, task.fileCount);
            }
            case WAITING_NETWORK -> {
                task.fileIndex = 1;
                task.fileDone = 5_200_000L;
                task.fileTotal = 7_800_000L;
                task.fileCount = Math.max(1, task.fileCount);
                task.error = com.yohu.urldown.download.DownloadError.NETWORK;
                task.failure = "网络断开，联网后会自动继续";
            }
            case SUCCESS -> {
                task.finishedAt = task.createdAt;
                task.savedBytes = 7_800_000L;
                task.report = "完成";
            }
            case FAILED -> {
                task.finishedAt = task.createdAt;
                task.error = com.yohu.urldown.download.DownloadError.LINK_EXPIRED;
                task.failure = "链接已过期，重试会重新解析";
                task.report = "失败: LINK_EXPIRED";
            }
            case CANCELED -> {
                task.finishedAt = task.createdAt;
                task.failure = "已取消";
            }
            default -> {
            }
        }
        if (task.finishedAt > 0) {
            task.finishedAt -= ageMs;
        }
        changed();
        return task;
    }

    /** 只给测试用：给已完成的任务挂上真实文件。 */
    @VisibleForTesting
    @MainThread
    public void setFilesForTest(@NonNull DownloadTask task, @NonNull List<File> files) {
        task.files = new ArrayList<>(files);
        long bytes = 0;
        for (File f : files) {
            bytes += f.length();
        }
        task.savedBytes = bytes;
        changed();
    }

    /** 只给测试用：清空全部任务（含进行中的），让每个用例从空列表开始。 */
    @VisibleForTesting
    @MainThread
    public void resetForTest() {
        tasks.clear();
        notifyChanged();
    }

    /** 结构变化：通知 + 落盘。 */
    @MainThread
    void changed() {
        notifyChanged();
        persist();
    }

    /** 进度变化：只通知。 */
    @MainThread
    void progressed(@NonNull DownloadTask task) {
        checkMain();
        for (Listener l : listeners) {
            l.onTaskProgress(task);
        }
    }

    @MainThread
    private void notifyChanged() {
        checkMain();
        for (Listener l : listeners) {
            l.onTasksChanged();
        }
    }

    @MainThread
    @NonNull
    public List<DownloadTask> all() {
        return new ArrayList<>(tasks);
    }

    @MainThread
    @Nullable
    public DownloadTask find(long id) {
        for (DownloadTask t : tasks) {
            if (t.id == id) {
                return t;
            }
        }
        return null;
    }

    /**
     * 同一条原链接已经在列表里、且还“有效”的任务：未结束的，或已完成且文件还在的。
     * 失败 / 取消 / 文件已被删的不算，重新下载是正常操作，不必提醒。列表新的在前，返回最近一条。
     */
    @MainThread
    @Nullable
    public DownloadTask findDuplicate(@Nullable String sourceUrl) {
        if (sourceUrl == null || sourceUrl.trim().isEmpty()) {
            return null;
        }
        String key = sourceUrl.trim();
        for (DownloadTask t : tasks) {
            if (t.sourceUrl == null || !t.sourceUrl.trim().equals(key)) {
                continue;
            }
            DownloadTask.Status s = t.status();
            if (s.isUnfinished() || (s == DownloadTask.Status.SUCCESS && !t.existingFiles().isEmpty())) {
                return t;
            }
        }
        return null;
    }

    @MainThread
    public int count(@NonNull DownloadTask.Status... statuses) {
        int n = 0;
        for (DownloadTask task : tasks) {
            for (DownloadTask.Status s : statuses) {
                if (task.status == s) {
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    @MainThread
    public int countFinished() {
        return count(DownloadTask.Status.SUCCESS, DownloadTask.Status.FAILED,
                DownloadTask.Status.CANCELED);
    }

    /**
     * 排在它前面、还没开始的任务数（不含正在下载的）。队列按入队时间先进先出。
     */
    @MainThread
    public int queueAhead(@NonNull DownloadTask task) {
        int n = 0;
        for (DownloadTask t : tasks) {
            if (t != task && t.status == DownloadTask.Status.QUEUED && t.enqueuedAt < task.enqueuedAt) {
                n++;
            }
        }
        return n;
    }

    /**
     * 从列表移除一条已结束的任务（未结束的要先取消）。
     *
     * @param deleteFiles 同时删除已保存的文件
     * @return 删掉的文件数
     */
    @MainThread
    public int remove(@NonNull DownloadTask task, boolean deleteFiles) {
        checkMain();
        if (task.status.isUnfinished() || !tasks.remove(task)) {
            return 0;
        }
        int deleted = 0;
        if (deleteFiles) {
            for (File f : task.files) {
                if (f.isFile() && f.delete()) {
                    deleted++;
                }
            }
        }
        changed();
        return deleted;
    }

    /** 移除已结束的记录，不删文件。返回移除条数。 */
    @MainThread
    public int clearFinished() {
        checkMain();
        int before = tasks.size();
        tasks.removeIf(t -> t.status.isFinished());
        int removed = before - tasks.size();
        if (removed > 0) {
            changed();
        }
        return removed;
    }

    public void addListener(@NonNull Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NonNull Listener listener) {
        listeners.remove(listener);
    }

    private void trim() {
        for (int i = tasks.size() - 1; i >= 0 && tasks.size() > MAX_RECORDS; i--) {
            if (tasks.get(i).status.isFinished()) {
                tasks.remove(i);
            }
        }
    }

    private void persist() {
        File target = file;
        if (target == null) {
            return;
        }
        // 在主线程拍快照，写盘放后台；单线程执行器保证写入顺序。
        String json = TaskJson.encode(tasks);
        writer.execute(() -> {
            File tmp = new File(target.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } catch (IOException e) {
                Log.w(TAG, "保存任务列表失败: " + e);
                return;
            }
            if (!tmp.renameTo(target)) {
                Log.w(TAG, "保存任务列表失败: rename");
            }
        });
    }

    private static void checkMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("TaskStore 只能在主线程访问");
        }
    }
}
