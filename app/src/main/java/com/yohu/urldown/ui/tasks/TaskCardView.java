package com.yohu.urldown.ui.tasks;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.yohu.urldown.R;
import com.yohu.urldown.task.DownloadTask;
import com.yohu.urldown.task.FileActions;
import com.yohu.urldown.ui.Ui;
import com.yohu.urldown.ui.widget.IconAction;
import com.yohu.urldown.ui.widget.PlatformMark;
import com.yohu.urldown.ui.widget.ProgressLine;
import com.yohu.urldown.ui.widget.TaskThumb;
import com.yohu.urldown.ui.widget.ThumbnailLoader;
import com.yohu.youi.api.common.YoCorner;
import com.yohu.youi.api.theme.YoColors;

import java.io.File;
import java.util.List;

/**
 * 任务卡片。固定三行高度，所有状态同一套版式：
 *
 * <pre>
 * ┌──────────────────────────────────────────────┐
 * │ ┌────┐ 周末去山里露营，记录一下清晨的雾…  (Ⅱ) │  标题一行
 * │ │平台│ ▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░░░░░░         │  进度条：仅未结束
 * │ └────┘ 3.2 / 7.4 MB · 1.2 MB/s · 剩余 5 秒    │  状态一行
 * └──────────────────────────────────────────────┘
 * </pre>
 *
 * <ul>
 *   <li>平台只在缩略图位置标识一次（{@link TaskThumb}）；平台名、作者、媒体明细放进详情面板。</li>
 *   <li>状态只说一次：状态行就是“现在怎样”，不再另挂标签。失败用警示色，其余中性。</li>
 *   <li>只放一个操作：当前状态最常用的那个（暂停 / 继续 / 重试 / 播放或打开）。
 *       取消、分享、复制链接、删除等在长按菜单和详情面板里。</li>
 * </ul>
 * 进度刷新走 {@link #bindProgress}，只改进度条和状态行，不重建视图。
 */
@SuppressLint("ViewConstructor") // 只在代码里创建，不进 XML
final class TaskCardView extends LinearLayout {

    /** 卡片上的操作。 */
    enum Action { PAUSE, RESUME, RETRY, OPEN }

    interface Listener {
        void onAction(@NonNull DownloadTask task, @NonNull Action action);
    }

    private final YoColors colors;
    private final TaskThumb thumb;
    private final TextView title;
    private final ProgressLine progress;
    private final TextView status;
    private final IconAction action;
    @Nullable
    private DownloadTask task;
    @Nullable
    private Action current;

    TaskCardView(@NonNull Context context, @NonNull YoColors colors, @NonNull Listener listener) {
        super(context);
        this.colors = colors;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(12), dp(12), dp(6), dp(12));
        YoCorner.Radii radii = YoCorner.Radii.uniform(dp(16));
        setBackground(new RippleDrawable(ColorStateList.valueOf(colors.ripple()),
                YoCorner.fill(colors.backgroundPrimary(), radii), YoCorner.fill(0xFFFFFFFF, radii)));
        setClickable(true);
        setLongClickable(true);

        thumb = new TaskThumb(context);
        thumb.setColors(colors.componentBackgroundSecondary(), colors.backgroundPrimary());
        addView(thumb, new LayoutParams(dp(48), dp(48)));

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(VERTICAL);
        LayoutParams textLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = dp(12);
        textLp.rightMargin = dp(4);
        addView(text, textLp);

        title = Ui.text(context, null, 15f, colors.textPrimary());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(title, Ui.matchWrap());

        progress = new ProgressLine(context);
        LayoutParams progressLp = Ui.matchWrap();
        progressLp.topMargin = dp(8);
        text.addView(progress, progressLp);

        status = Ui.text(context, null, 12.5f, colors.textSecondary());
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        LayoutParams statusLp = Ui.matchWrap();
        statusLp.topMargin = dp(6);
        text.addView(status, statusLp);

        action = new IconAction(context, colors.ripple());
        action.setOnClickListener(v -> {
            if (task != null && current != null) {
                listener.onAction(task, current);
            }
        });
        addView(action, new LayoutParams(dp(40), dp(40)));
    }

    @Nullable
    DownloadTask task() {
        return task;
    }

    /** 整卡绑定：换任务或状态变化时调用。 */
    void bind(@NonNull DownloadTask t) {
        boolean same = task == t;
        task = t;
        DownloadTask.Status s = t.status();

        title.setText(t.title);
        String key = "task:" + t.id;
        thumb.bind(key, PlatformMark.iconOf(t.platformId, t.platform));
        List<File> files = t.existingFiles();
        ThumbnailLoader.load(thumb, key, files.isEmpty() ? null : files.get(0), t.coverUrl, t.coverHeaders);

        boolean showProgress = s == DownloadTask.Status.RUNNING || s == DownloadTask.Status.PAUSED
                || s == DownloadTask.Status.WAITING_NETWORK;
        progress.setVisibility(showProgress ? VISIBLE : GONE);
        // 暂停 / 等网络时进度条转灰：停在断点，不再动
        progress.setColors(Ui.withAlpha(colors.textPrimary(), 0.08f),
                s == DownloadTask.Status.RUNNING ? colors.brand() : colors.textQuaternary());
        if (showProgress) {
            progress.setProgress(t.progress(), same);
        }
        int color = colors.textSecondary();
        if (s == DownloadTask.Status.FAILED) {
            color = colors.alert();
        } else if (s == DownloadTask.Status.SUCCESS && files.isEmpty() && !t.files().isEmpty()) {
            color = colors.textTertiary();
        }
        status.setTextColor(color);
        status.setText(TaskFormat.statusLine(getContext(), t));
        bindAction(t, files);
        setContentDescription(t.platform + "，" + t.title + "，" + status.getText());
    }

    /** 只刷新进度：下载回调 300ms 一次，不能每次都重建卡片。 */
    void bindProgress(@NonNull DownloadTask t) {
        if (task != t) {
            return;
        }
        if (progress.getVisibility() == VISIBLE) {
            progress.setProgress(t.progress(), true);
        }
        status.setText(TaskFormat.statusLine(getContext(), t));
    }

    private void bindAction(DownloadTask t, List<File> files) {
        current = switch (t.status()) {
            case QUEUED, RUNNING, WAITING_NETWORK -> Action.PAUSE;
            case PAUSED -> t.canResume() ? Action.RESUME : null;
            case FAILED, CANCELED -> t.canRetry() ? Action.RETRY : null;
            case SUCCESS -> files.isEmpty() ? null : Action.OPEN;
        };
        if (current == null) {
            action.setVisibility(INVISIBLE); // 占位不收：所有卡片右缘对齐
            return;
        }
        action.setVisibility(VISIBLE);
        Context c = getContext();
        switch (current) {
            case PAUSE -> action.set(R.drawable.ic_action_pause, colors.iconSecondary(),
                    c.getString(R.string.task_action_pause));
            case RESUME -> action.set(R.drawable.ic_action_resume, colors.brand(),
                    c.getString(R.string.task_action_resume));
            case RETRY -> action.set(R.drawable.ic_action_retry, colors.brand(),
                    c.getString(R.string.task_action_retry));
            case OPEN -> {
                // 视频 / 音频给“播放”，图片等给“打开”，图标说的就是点了会发生什么
                String mime = FileActions.mimeOf(files.get(0));
                boolean playable = mime.startsWith("video/") || mime.startsWith("audio/");
                action.set(playable ? R.drawable.ic_action_play : R.drawable.ic_action_open, colors.brand(),
                        c.getString(R.string.task_action_open));
            }
        }
    }

    private int dp(float v) {
        return Ui.dp(getContext(), v);
    }
}
