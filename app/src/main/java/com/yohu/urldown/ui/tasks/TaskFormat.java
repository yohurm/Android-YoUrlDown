package com.yohu.urldown.ui.tasks;

import android.content.Context;
import android.text.format.DateFormat;

import androidx.annotation.NonNull;

import com.yohu.urldown.R;
import com.yohu.urldown.task.DownloadTask;
import com.yohu.urldown.task.TaskStore;
import com.yohu.urldown.util.Bytes;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 任务相关的文字格式。全部固定中文、固定格式：
 * 系统 DateUtils / Formatter 跟随 JVM 语言，会冒出 "0 minutes ago"、"MB" 小数位不一。
 * 字节 / 速度 / 剩余时间的格式在 {@link Bytes}，通知栏也用它。
 */
final class TaskFormat {

    private TaskFormat() {
    }

    /** 时分。列表已按自然日分组，行里只需要几点几分。 */
    @NonNull
    static String clock(long at) {
        return DateFormat.format("HH:mm", at).toString();
    }

    /** “今天 14:32” / “昨天 09:10” / “3月2日 18:00”。 */
    @NonNull
    static String dayTime(@NonNull Context c, long at, long now) {
        return dayLabel(c, at, now) + " " + clock(at);
    }

    /** 分组标题：今天 / 昨天 / 某月某日。 */
    @NonNull
    static String dayLabel(@NonNull Context c, long at, long now) {
        int days = daysBetween(at, now);
        if (days == 0) {
            return c.getString(R.string.tasks_section_today);
        }
        if (days == 1) {
            return c.getString(R.string.tasks_section_yesterday);
        }
        return DateFormat.format(isSameYear(at, now) ? "M月d日" : "yyyy年M月d日", at).toString();
    }

    /** 自然日差（按本地时区的日期，不是 24 小时）。 */
    static int daysBetween(long at, long now) {
        return (int) (dayIndex(now) - dayIndex(at));
    }

    private static long dayIndex(long t) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(t);
        long offset = cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET);
        return Math.floorDiv(t + offset, 86_400_000L);
    }

    private static boolean isSameYear(long a, long b) {
        Calendar x = Calendar.getInstance();
        x.setTimeInMillis(a);
        Calendar y = Calendar.getInstance();
        y.setTimeInMillis(b);
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR);
    }

    /**
     * 卡片上的状态行，一句话说清“现在怎样”：
     * 下载中“3.2 / 7.4 MB · 1.2 MB/s · 剩余 5 秒”、排队“排队中 · 前面还有 2 个”、
     * 暂停“已暂停 · 3.2 / 7.4 MB”、等网络写原因、完成“14:05 · 7.4 MB”、失败写原因。
     */
    @NonNull
    static String statusLine(@NonNull Context c, @NonNull DownloadTask t) {
        switch (t.status()) {
            case RUNNING: {
                String left = progressLeft(c, t);
                if (t.stage() != DownloadTask.Stage.DOWNLOADING || t.fileTotal() <= 0) {
                    return left;
                }
                List<String> parts = new ArrayList<>();
                parts.add(left);
                if (t.speedBps() > 0) {
                    parts.add(Bytes.speed(t.speedBps()));
                }
                long eta = t.etaMs();
                if (eta > 0) {
                    parts.add(c.getString(R.string.task_eta, Bytes.remaining(eta)));
                }
                return String.join(" · ", parts);
            }
            case PAUSED: {
                String label = c.getString(R.string.task_status_paused);
                return t.fileTotal() > 0 ? label + " · " + Bytes.pair(t.fileDone(), t.fileTotal()) : label;
            }
            case WAITING_NETWORK: {
                String why = t.failure();
                return why == null || why.isEmpty() ? c.getString(R.string.task_status_waiting_network) : why;
            }
            default:
                return cardNote(c, t);
        }
    }

    /**
     * 排队 / 结束时的一句话：排队说“排在第几”，结束后说“什么时候 / 多大 / 为什么”。详情面板也用。
     */
    @NonNull
    static String cardNote(@NonNull Context c, @NonNull DownloadTask t) {
        switch (t.status()) {
            case QUEUED: {
                int ahead = TaskStore.get().queueAhead(t);
                return ahead == 0 ? c.getString(R.string.task_queued_next)
                        : c.getString(R.string.task_queued_ahead, ahead);
            }
            case RUNNING:
                if (t.fileCount() > 1 && t.fileIndex() > 0) {
                    return c.getString(R.string.task_file_of, t.fileIndex(), t.fileCount());
                }
                return c.getString(R.string.task_added_at, clock(t.createdAt));
            case PAUSED:
                return c.getString(R.string.task_paused_hint);
            case WAITING_NETWORK:
            case FAILED:
                return t.failure() == null ? "" : t.failure();
            case SUCCESS: {
                if (t.existingFiles().isEmpty() && !t.files().isEmpty()) {
                    return c.getString(R.string.task_files_missing);
                }
                List<String> parts = new ArrayList<>();
                parts.add(clock(t.sortTime()));
                if (t.savedBytes() > 0) {
                    parts.add(Bytes.size(t.savedBytes()));
                }
                return String.join(" · ", parts);
            }
            case CANCELED:
            default:
                return c.getString(R.string.task_canceled_at, clock(t.sortTime()));
        }
    }

    /** “3.2 / 7.4 MB”，或当前阶段（解析 / 合并 / 校验 / 连接）。 */
    @NonNull
    static String progressLeft(@NonNull Context c, @NonNull DownloadTask t) {
        if (t.status() == DownloadTask.Status.RUNNING) {
            switch (t.stage()) {
                case PARSING:
                    return c.getString(R.string.task_stage_parsing);
                case MERGING:
                    return c.getString(R.string.task_stage_merging);
                case VERIFYING:
                    return c.getString(R.string.task_stage_verifying);
                default:
                    break;
            }
        }
        if (t.fileTotal() <= 0) {
            return t.status() == DownloadTask.Status.RUNNING
                    ? c.getString(R.string.task_stage_connecting) : "";
        }
        return Bytes.pair(t.fileDone(), t.fileTotal());
    }

    /** 详情页的进行中描述：阶段 / 百分比 / 已下 / 总量 / 速度 / 剩余 / 第几个文件。 */
    @NonNull
    static String runningLine(@NonNull Context c, @NonNull DownloadTask t) {
        List<String> parts = new ArrayList<>();
        String left = progressLeft(c, t);
        if (!left.isEmpty()) {
            parts.add(left);
        }
        if (t.stage() == DownloadTask.Stage.DOWNLOADING && t.fileTotal() > 0) {
            float p = t.progress();
            if (p >= 0) {
                parts.add(0, Math.round(p * 100) + "%");
            }
            if (t.speedBps() > 0) {
                parts.add(Bytes.speed(t.speedBps()));
            }
            long eta = t.etaMs();
            if (eta > 0) {
                parts.add(c.getString(R.string.task_eta, Bytes.remaining(eta)));
            }
        }
        if (t.fileCount() > 1 && t.fileIndex() > 0) {
            parts.add(c.getString(R.string.task_file_of, t.fileIndex(), t.fileCount()));
        }
        return String.join(" · ", parts);
    }
}
