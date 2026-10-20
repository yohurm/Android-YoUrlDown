package com.yohu.urldown.ui.tasks;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.yohu.urldown.R;
import com.yohu.urldown.task.DownloadRunner;
import com.yohu.urldown.task.DownloadTask;
import com.yohu.urldown.task.FileActions;
import com.yohu.urldown.task.TaskStore;
import com.yohu.urldown.ui.Ui;
import com.yohu.urldown.util.Bytes;
import com.yohu.urldown.ui.widget.GroupSurface;
import com.yohu.urldown.ui.widget.PlatformMark;
import com.yohu.urldown.ui.widget.ProgressLine;
import com.yohu.urldown.ui.widget.TaskThumb;
import com.yohu.urldown.ui.widget.ThumbnailLoader;
import com.yohu.youi.api.containers.YoSheet;
import com.yohu.youi.api.controls.YoButton;
import com.yohu.youi.api.feedback.YoToast;
import com.yohu.youi.api.theme.YoColors;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务详情（底部面板，高度随内容）。
 *
 * <pre>
 * [缩略图] 标题（最多 3 行）
 *          [抖] 抖音 · 山野日记 · 今天 14:32 添加
 * ── 状态区：进度条+数字（+ 暂停 / 等网络原因）/ 失败原因 / 已保存大小
 * ── 文件（完成时）：文件名 · 大小，点按打开
 * [ 主操作 ]  [ 次操作 ]      进行中：暂停 + 取消；暂停：继续 + 取消；失败：重试；完成：打开 + 分享
 * 复制原链接 · 查看下载记录 · 删除
 * </pre>
 *
 * <p>面板打开期间监听任务变化，进度和状态就地刷新；关掉即注销并销毁。
 */
final class TaskDetailSheet implements TaskStore.Listener {

    interface Host {
        void confirmDeleteFiles(@NonNull DownloadTask task, @NonNull Runnable after);

        void openFirst(@NonNull DownloadTask task);
    }

    private final AppCompatActivity activity;
    private final YoColors colors;
    private final DownloadTask task;
    private final Host host;
    private final LinearLayout statusBox;
    private final LinearLayout filesBox;
    private final LinearLayout actionsBox;
    private final TextView logView;
    private final LinearLayout links;
    private TextView logToggle;
    private final List<YoButton> buttons = new ArrayList<>();
    private ProgressLine progress;
    private TextView progressText;
    private DownloadTask.Status renderedStatus;
    private YoSheet sheet;

    TaskDetailSheet(@NonNull AppCompatActivity activity, @NonNull DownloadTask task, @NonNull Host host) {
        this.activity = activity;
        this.colors = YoColors.of(activity);
        this.task = task;
        this.host = host;

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(4), dp(20), dp(24));

        // 头部：缩略图 + 标题 + 来源
        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TaskThumb thumb = new TaskThumb(activity);
        thumb.setColors(colors.componentBackgroundSecondary(), colors.backgroundPrimary());
        String key = "task:" + task.id;
        thumb.bind(key, PlatformMark.iconOf(task.platformId, task.platform));
        List<File> files = task.existingFiles();
        ThumbnailLoader.load(thumb, key, files.isEmpty() ? null : files.get(0),
                task.coverUrl, task.coverHeaders);
        head.addView(thumb, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout headText = new LinearLayout(activity);
        headText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams headTextLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        headTextLp.leftMargin = dp(14);
        head.addView(headText, headTextLp);
        TextView title = Ui.medium(activity, task.title, Ui.TEXT_CARD_TITLE, colors.textPrimary());
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setLineSpacing(0f, 1.15f);
        headText.addView(title, Ui.matchWrap());
        TextView source = Ui.text(activity, sourceLine(), 13f, colors.textSecondary());
        LinearLayout.LayoutParams sourceLp = Ui.matchWrap();
        sourceLp.topMargin = dp(6);
        headText.addView(source, sourceLp);
        content.addView(head, Ui.matchWrap());

        statusBox = new LinearLayout(activity);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams statusLp = Ui.matchWrap();
        statusLp.topMargin = dp(20);
        content.addView(statusBox, statusLp);

        filesBox = new LinearLayout(activity);
        filesBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(filesBox, Ui.matchWrap());

        actionsBox = new LinearLayout(activity);
        actionsBox.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsLp = Ui.matchWrap();
        actionsLp.topMargin = dp(20);
        content.addView(actionsBox, actionsLp);

        // 次要操作：一排文字链接，不和主按钮抢视线；随状态重建
        links = new LinearLayout(activity);
        links.setOrientation(LinearLayout.HORIZONTAL);
        links.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams linksLp = Ui.matchWrap();
        linksLp.topMargin = dp(12);
        content.addView(links, linksLp);

        logView = Ui.text(activity, null, 11f, colors.textSecondary());
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setLineSpacing(0f, 1.2f);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(12), dp(10), dp(12), dp(10));
        logView.setBackground(com.yohu.youi.api.common.YoCorner.fill(colors.componentBackgroundSecondary(),
                com.yohu.youi.api.common.YoCorner.Radii.uniform(dp(10))));
        logView.setVisibility(View.GONE);
        LinearLayout.LayoutParams logLp = Ui.matchWrap();
        logLp.topMargin = dp(8);
        content.addView(logView, logLp);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);

        render();
        sheet = YoSheet.create(activity)
                .setContent(scroll)
                .setPreferredPresentation(YoSheet.PreferredPresentation.BOTTOM)
                .setHeight(YoSheet.Height.fitContent())
                .setBackdropMode(YoSheet.BackdropMode.SOLID)
                .setListener(new YoSheet.Listener() {
                    @Override
                    public void onDismissed(@NonNull YoSheet.DismissReason reason) {
                        release();
                    }
                })
                .build();
    }

    void show() {
        TaskStore.get().addListener(this);
        sheet.show();
    }

    private void release() {
        TaskStore.get().removeListener(this);
        for (YoButton b : buttons) {
            b.destroy();
        }
        buttons.clear();
        if (sheet != null) {
            YoSheet s = sheet;
            sheet = null;
            // 放到下一帧销毁，避免在自身回调里拆掉自己
            statusBox.post(s::destroy);
        }
    }

    void dismiss() {
        if (sheet != null) {
            sheet.dismiss();
        }
    }

    @Override
    public void onTasksChanged() {
        if (TaskStore.get().find(task.id) == null) {
            dismiss();
            return;
        }
        render();
    }

    @Override
    public void onTaskProgress(@NonNull DownloadTask t) {
        if (t != task || progress == null) {
            return;
        }
        progress.setProgress(t.progress(), true);
        progressText.setText(progressLine(t));
    }

    /** 进度条下那行：下载中给完整数字；排队给位置；暂停 / 等网络给停在哪。 */
    private String progressLine(DownloadTask t) {
        return switch (t.status()) {
            case RUNNING -> TaskFormat.runningLine(activity, t);
            case QUEUED -> TaskFormat.cardNote(activity, t);
            default -> {
                String left = TaskFormat.progressLeft(activity, t);
                float p = t.progress();
                String percent = p >= 0 ? Math.round(p * 100) + "%" : "";
                yield left.isEmpty() ? percent : percent.isEmpty() ? left : percent + " · " + left;
            }
        };
    }

    private void render() {
        DownloadTask.Status s = task.status();
        if (s == renderedStatus && s == DownloadTask.Status.RUNNING) {
            onTaskProgress(task);
            return;
        }
        renderedStatus = s;
        statusBox.removeAllViews();
        filesBox.removeAllViews();
        for (YoButton b : buttons) {
            b.destroy();
        }
        buttons.clear();
        actionsBox.removeAllViews();
        progress = null;
        progressText = null;

        switch (s) {
            case QUEUED, RUNNING, PAUSED, WAITING_NETWORK -> {
                boolean running = s == DownloadTask.Status.RUNNING;
                progress = new ProgressLine(activity);
                progress.setColors(Ui.withAlpha(colors.textPrimary(), 0.08f),
                        running ? colors.brand() : colors.textQuaternary());
                progress.setProgress(s == DownloadTask.Status.QUEUED ? 0f : task.progress(), false);
                statusBox.addView(progress, Ui.matchWrap());
                progressText = Ui.text(activity, progressLine(task), 13f, colors.textSecondary());
                LinearLayout.LayoutParams lp = Ui.matchWrap();
                lp.topMargin = dp(8);
                statusBox.addView(progressText, lp);
                if (!running && s != DownloadTask.Status.QUEUED) {
                    // 暂停 / 等网络：说明为什么停、怎样会继续
                    TextView why = Ui.text(activity, TaskFormat.cardNote(activity, task), 13f,
                            s == DownloadTask.Status.WAITING_NETWORK ? colors.warning() : colors.textTertiary());
                    LinearLayout.LayoutParams whyLp = Ui.matchWrap();
                    whyLp.topMargin = dp(4);
                    statusBox.addView(why, whyLp);
                }
                if (s == DownloadTask.Status.PAUSED) {
                    if (task.canResume()) {
                        addButton(R.string.task_action_resume, YoButton.Style.PRIMARY,
                                () -> DownloadRunner.resume(activity, task));
                    }
                } else {
                    addButton(R.string.task_action_pause, YoButton.Style.SECONDARY, () -> DownloadRunner.pause(task));
                }
                addButton(R.string.task_action_cancel, YoButton.Style.SECONDARY, () -> DownloadRunner.cancel(task));
            }
            case FAILED, CANCELED -> {
                String why = task.failure() == null ? "" : task.failure();
                TextView reason = Ui.medium(activity, why, 15f,
                        s == DownloadTask.Status.FAILED ? colors.alert() : colors.textPrimary());
                statusBox.addView(reason, Ui.matchWrap());
                TextView when = Ui.text(activity,
                        TaskFormat.dayTime(activity, task.sortTime(), System.currentTimeMillis()),
                        13f, colors.textTertiary());
                LinearLayout.LayoutParams lp = Ui.matchWrap();
                lp.topMargin = dp(4);
                statusBox.addView(when, lp);
                if (task.canRetry()) {
                    addButton(R.string.task_action_retry, YoButton.Style.PRIMARY,
                            () -> DownloadRunner.retry(activity, task));
                }
            }
            case SUCCESS -> {
                List<File> files = task.existingFiles();
                String summary = activity.getString(R.string.task_saved_summary,
                        Bytes.size(task.savedBytes()));
                statusBox.addView(Ui.medium(activity, files.isEmpty()
                                ? activity.getString(R.string.task_files_missing) : summary,
                        15f, colors.textPrimary()), Ui.matchWrap());
                if (!files.isEmpty()) {
                    addFiles(files);
                    addButton(R.string.task_action_open, YoButton.Style.PRIMARY, () -> host.openFirst(task));
                    addButton(R.string.task_action_share, YoButton.Style.SECONDARY, () -> {
                        if (!FileActions.share(activity, task.existingFiles(), task.title)) {
                            YoToast.show(activity, activity.getString(R.string.task_open_failed));
                        }
                    });
                }
            }
        }
        actionsBox.setVisibility(actionsBox.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        buildLinks(s);
        if (logView.getVisibility() == View.VISIBLE) {
            logView.setText(logText());
        }
    }

    private void addFiles(List<File> files) {
        TextView caption = Ui.medium(activity, activity.getString(R.string.task_detail_files), 13f,
                colors.textSecondary());
        caption.setPadding(0, dp(20), 0, dp(8));
        filesBox.addView(caption);
        GroupSurface group = new GroupSurface(activity, colors.componentBackgroundSecondary(),
                colors.componentDivider(), dp(14));
        for (File f : files) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(colors.ripple()), null,
                    new android.graphics.drawable.ColorDrawable(0xFFFFFFFF)));
            TextView name = Ui.text(activity, f.getName(), 14f, colors.textPrimary());
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView size = Ui.text(activity, Bytes.size(f.length()), 13f, colors.textTertiary());
            LinearLayout.LayoutParams sizeLp = Ui.wrap();
            sizeLp.leftMargin = dp(12);
            row.addView(size, sizeLp);
            row.setOnClickListener(v -> {
                if (!FileActions.open(activity, f)) {
                    YoToast.show(activity, activity.getString(R.string.task_open_failed));
                }
            });
            group.addRow(row);
        }
        filesBox.addView(group, Ui.matchWrap());
    }

    private void addButton(int text, YoButton.Style style, Runnable onClick) {
        YoButton button = YoButton.create(activity)
                .setText(activity.getString(text))
                .setStyle(style)
                .setSize(YoButton.Size.MEDIUM)
                .setOnClickListener(v -> onClick.run())
                .build();
        buttons.add(button);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (actionsBox.getChildCount() > 0) {
            lp.leftMargin = dp(12);
        }
        actionsBox.addView(button.getView(), lp);
    }

    private TextView link(String text, int color, View.OnClickListener onClick) {
        TextView v = Ui.text(activity, text, 14f, color);
        v.setPadding(0, dp(10), dp(20), dp(10));
        v.setOnClickListener(onClick);
        return v;
    }

    private void toggleLog() {
        boolean show = logView.getVisibility() != View.VISIBLE;
        logView.setVisibility(show ? View.VISIBLE : View.GONE);
        logToggle.setText(show ? R.string.task_action_hide_log : R.string.task_action_show_log);
        if (show) {
            logView.setText(logText());
        }
    }

    private String logText() {
        String r = task.report();
        return r == null || r.isEmpty() ? activity.getString(R.string.task_no_log) : r;
    }

    private void buildLinks(DownloadTask.Status s) {
        links.removeAllViews();
        if (!TextUtils.isEmpty(task.sourceUrl)) {
            links.addView(link(activity.getString(R.string.task_action_copy_link), colors.textEmphasize(), v -> {
                FileActions.copyText(activity, task.title, task.sourceUrl);
                YoToast.show(activity, activity.getString(R.string.task_link_copied));
            }));
        }
        boolean logShown = logView.getVisibility() == View.VISIBLE;
        logToggle = link(activity.getString(logShown ? R.string.task_action_hide_log
                : R.string.task_action_show_log), colors.textEmphasize(), v -> toggleLog());
        links.addView(logToggle);
        links.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1f));
        if (!s.isFinished()) {
            return;
        }
        if (s == DownloadTask.Status.SUCCESS && !task.existingFiles().isEmpty()) {
            TextView delete = link(activity.getString(R.string.task_action_delete_files), colors.alert(),
                    v -> host.confirmDeleteFiles(task, this::dismiss));
            delete.setPadding(0, dp(10), 0, dp(10));
            links.addView(delete);
        } else {
            TextView remove = link(activity.getString(R.string.task_action_remove), colors.textSecondary(), v -> {
                TaskStore.get().remove(task, false);
                YoToast.show(activity, activity.getString(R.string.task_removed));
            });
            remove.setPadding(0, dp(10), 0, dp(10));
            links.addView(remove);
        }
    }

    private String sourceLine() {
        List<String> parts = new ArrayList<>();
        parts.add(task.platform);
        if (!TextUtils.isEmpty(task.author)) {
            parts.add(task.author);
        }
        parts.add(activity.getString(R.string.task_added_at,
                TaskFormat.dayTime(activity, task.createdAt, System.currentTimeMillis())));
        return String.join(" · ", parts);
    }

    private int dp(float v) {
        return Ui.dp(activity, v);
    }
}
