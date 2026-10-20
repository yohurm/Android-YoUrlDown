package com.yohu.urldown.ui.tasks;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatActivity;

import com.yohu.urldown.R;
import com.yohu.urldown.task.DownloadRunner;
import com.yohu.urldown.task.DownloadTask;
import com.yohu.urldown.task.FileActions;
import com.yohu.urldown.task.TaskStore;
import com.yohu.urldown.ui.TabPage;
import com.yohu.urldown.ui.Ui;
import com.yohu.urldown.ui.widget.IconAction;
import com.yohu.youi.api.controls.YoButton;
import com.yohu.youi.api.controls.YoSegmentButton;
import com.yohu.youi.api.feedback.YoDialog;
import com.yohu.youi.api.feedback.YoToast;
import com.yohu.youi.api.navigation.YoMenu;
import com.yohu.youi.api.theme.YoColors;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务页。
 *
 * <pre>
 * 任务                                   (⋯)
 * 2 个进行中 · 1 个失败
 * [ 全部 | 进行中 | 已结束 ]
 *
 * 进行中                                  2
 * ┌ 卡片：缩略图(平台) | 标题 / 进度 / 状态 | 一个操作 ┐   见 {@link TaskCardView}
 * └────────────────────────────────────┘
 * 今天                                    3
 * ┌ 卡片 ┐
 * </pre>
 *
 * <p>未结束的（下载中 / 排队 / 等网络 / 暂停）永远置顶；已结束的按自然日分组，新的在前。
 * 点卡片看详情（底部面板），长按出上下文菜单，卡片右侧只给当前状态最常用的一个操作。
 * 卡片按任务 id 复用，进度回调只刷新那一张。
 */
public final class TasksPage implements TabPage, TaskStore.Listener, TaskDetailSheet.Host {

    private static final int FILTER_ALL = 0;
    private static final int FILTER_ACTIVE = 1;
    private static final int FILTER_DONE = 2;

    private static final int MENU_OPEN = 1;
    private static final int MENU_SHARE = 2;
    private static final int MENU_RETRY = 3;
    private static final int MENU_CANCEL = 4;
    private static final int MENU_COPY = 5;
    private static final int MENU_REMOVE = 6;
    private static final int MENU_DELETE = 7;
    private static final int MENU_RETRY_FAILED = 11;
    private static final int MENU_CLEAR_FINISHED = 12;
    private static final int MENU_PAUSE_ALL = 13;
    private static final int MENU_RESUME_ALL = 14;

    private final AppCompatActivity activity;
    private final YoColors colors;
    private final Runnable goHome;
    private final LinearLayout root;
    private final Ui.PageHeader header;
    private final IconAction moreButton;
    private final YoSegmentButton filterBar;
    private final ScrollView scroll;
    private final LinearLayout sections;
    private final LinearLayout emptyView;
    private final TextView emptyTitle;
    private final TextView emptyBody;
    private final YoButton emptyAction;
    private final Map<Long, TaskCardView> cards = new HashMap<>();
    @Nullable
    private YoMenu menu;
    @Nullable
    private TaskDetailSheet detail;

    private int filter = FILTER_ALL;

    public TasksPage(@NonNull AppCompatActivity activity, @NonNull Runnable goHome) {
        this.activity = activity;
        this.colors = YoColors.of(activity);
        this.goHome = goHome;

        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(colors.backgroundSecondary());

        FrameLayout headerBox = new FrameLayout(activity);
        header = Ui.pageHeader(activity, colors, activity.getString(R.string.tasks_title));
        headerBox.addView(header.view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        moreButton = new IconAction(activity, colors.ripple());
        moreButton.set(R.drawable.ic_action_more, colors.iconPrimary(),
                activity.getString(R.string.task_action_more));
        moreButton.setOnClickListener(v -> showPageMenu());
        FrameLayout.LayoutParams moreLp = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.END);
        moreLp.topMargin = dp(20);
        moreLp.rightMargin = dp(12);
        headerBox.addView(moreButton, moreLp);
        root.addView(headerBox, Ui.matchWrap());

        filterBar = YoSegmentButton.create(activity)
                .setType(YoSegmentButton.SegmentType.TABS)
                .setSize(YoSegmentButton.Size.MEDIUM)
                .addSegment(FILTER_ALL, activity.getString(R.string.tasks_filter_all))
                .addSegment(FILTER_ACTIVE, activity.getString(R.string.tasks_filter_active))
                .addSegment(FILTER_DONE, activity.getString(R.string.tasks_filter_done))
                .setSelectedIndex(FILTER_ALL)
                .setOnSelectionChangeListener(index -> {
                    filter = index;
                    render();
                    getScroller().scrollTo(0, 0);
                })
                .build();
        LinearLayout.LayoutParams filterLp = Ui.matchWrap();
        filterLp.setMargins(dp(Ui.PAGE_PADDING_DP), 0, dp(Ui.PAGE_PADDING_DP), dp(4));
        root.addView(filterBar.getView(), filterLp);

        FrameLayout body = new FrameLayout(activity);
        root.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        scroll = new ScrollView(activity);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        sections = new LinearLayout(activity);
        sections.setOrientation(LinearLayout.VERTICAL);
        sections.setPadding(dp(Ui.PAGE_PADDING_DP), 0, dp(Ui.PAGE_PADDING_DP), 0);
        scroll.addView(sections, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        body.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        emptyView = new LinearLayout(activity);
        emptyView.setOrientation(LinearLayout.VERTICAL);
        emptyView.setGravity(Gravity.CENTER_HORIZONTAL);
        emptyView.setPadding(dp(40), dp(88), dp(40), 0);
        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.ic_tab_tasks);
        icon.setColorFilter(colors.iconTertiary());
        emptyView.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        emptyTitle = Ui.medium(activity, null, 16f, colors.textPrimary());
        emptyTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = Ui.wrap();
        titleLp.topMargin = dp(16);
        emptyView.addView(emptyTitle, titleLp);
        emptyBody = Ui.text(activity, null, 13f, colors.textSecondary());
        emptyBody.setGravity(Gravity.CENTER);
        emptyBody.setLineSpacing(0f, 1.2f);
        LinearLayout.LayoutParams bodyLp = Ui.wrap();
        bodyLp.topMargin = dp(6);
        emptyView.addView(emptyBody, bodyLp);
        emptyAction = YoButton.create(activity)
                .setText(activity.getString(R.string.tasks_empty_action))
                .setStyle(YoButton.Style.SECONDARY)
                .setOnClickListener(v -> goHome.run())
                .build();
        LinearLayout.LayoutParams actionLp = Ui.wrap();
        actionLp.topMargin = dp(20);
        emptyView.addView(emptyAction.getView(), actionLp);
        body.addView(emptyView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TaskStore.get().addListener(this);
        render();
    }

    @NonNull
    @Override
    public View getView() {
        return root;
    }

    @Nullable
    @Override
    public View getScroller() {
        return scroll;
    }

    @Override
    public void setBottomInset(int insetPx) {
        scroll.setPadding(0, dp(8), 0, insetPx + dp(24));
    }

    @Override
    public void scrollToTop() {
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onTasksChanged() {
        render();
    }

    @Override
    public void onTaskProgress(@NonNull DownloadTask task) {
        TaskCardView card = cards.get(task.id);
        if (card != null) {
            card.bindProgress(task);
        }
    }

    // ---- 渲染 ----

    private void render() {
        TaskStore store = TaskStore.get();
        List<DownloadTask> all = store.all();
        header.setSubtitle(summary(store, all.size()));
        moreButton.setVisibility(all.isEmpty() ? View.INVISIBLE : View.VISIBLE);

        List<DownloadTask> active = new ArrayList<>();
        List<DownloadTask> finished = new ArrayList<>();
        for (DownloadTask t : all) {
            (t.status().isUnfinished() ? active : finished).add(t);
        }
        // 进行中：正在下载的在最上，其后排队 / 等网络（按入队顺序，就是将来的执行顺序），暂停的垫底
        active.sort(Comparator
                .comparingInt((DownloadTask t) -> rank(t.status()))
                .thenComparingLong(DownloadTask::enqueuedAt));
        finished.sort(Comparator.comparingLong(DownloadTask::sortTime).reversed());

        for (TaskCardView card : cards.values()) {
            ViewGroup parent = (ViewGroup) card.getParent();
            if (parent != null) {
                parent.removeView(card);
            }
        }
        sections.removeAllViews();
        Map<Long, TaskCardView> keep = new HashMap<>();
        long now = System.currentTimeMillis();

        if (filter != FILTER_DONE && !active.isEmpty()) {
            addSection(activity.getString(R.string.tasks_section_active), active, keep);
        }
        if (filter != FILTER_ACTIVE && !finished.isEmpty()) {
            Map<String, List<DownloadTask>> byDay = new LinkedHashMap<>();
            for (DownloadTask t : finished) {
                byDay.computeIfAbsent(TaskFormat.dayLabel(activity, t.sortTime(), now),
                        k -> new ArrayList<>()).add(t);
            }
            for (Map.Entry<String, List<DownloadTask>> e : byDay.entrySet()) {
                addSection(e.getKey(), e.getValue(), keep);
            }
        }
        cards.clear();
        cards.putAll(keep);

        boolean empty = sections.getChildCount() == 0;
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        scroll.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);
        if (empty) {
            if (all.isEmpty()) {
                emptyTitle.setText(R.string.tasks_empty_title);
                emptyBody.setText(R.string.tasks_empty_body);
                emptyBody.setVisibility(View.VISIBLE);
                emptyAction.getView().setVisibility(View.VISIBLE);
            } else {
                emptyTitle.setText(filter == FILTER_ACTIVE
                        ? R.string.tasks_empty_active : R.string.tasks_empty_finished);
                emptyBody.setVisibility(View.GONE);
                emptyAction.getView().setVisibility(View.GONE);
            }
        }
    }

    @Nullable
    private String summary(TaskStore store, int total) {
        if (total == 0) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        int active = store.count(DownloadTask.Status.QUEUED, DownloadTask.Status.RUNNING,
                DownloadTask.Status.WAITING_NETWORK);
        int paused = store.count(DownloadTask.Status.PAUSED);
        int failed = store.count(DownloadTask.Status.FAILED);
        if (active > 0) {
            parts.add(activity.getString(R.string.tasks_summary_active, active));
        }
        if (paused > 0) {
            parts.add(activity.getString(R.string.tasks_summary_paused, paused));
        }
        if (failed > 0) {
            parts.add(activity.getString(R.string.tasks_summary_failed, failed));
        }
        if (parts.isEmpty()) {
            parts.add(activity.getString(R.string.tasks_summary_total, total));
        }
        return String.join(" · ", parts);
    }

    /** 进行中分组内的先后：下载中 → 排队 / 等网络 → 暂停。 */
    private static int rank(DownloadTask.Status s) {
        return switch (s) {
            case RUNNING -> 0;
            case QUEUED, WAITING_NETWORK -> 1;
            default -> 2;
        };
    }

    private void addSection(String label, List<DownloadTask> tasks, Map<Long, TaskCardView> keep) {
        LinearLayout caption = new LinearLayout(activity);
        caption.setOrientation(LinearLayout.HORIZONTAL);
        caption.setPadding(dp(4), dp(sections.getChildCount() == 0 ? 12 : 24), dp(4), dp(8));
        TextView name = Ui.medium(activity, label, 13f, colors.textSecondary());
        caption.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        caption.addView(Ui.text(activity, String.valueOf(tasks.size()), 13f, colors.textTertiary()), Ui.wrap());
        sections.addView(caption, Ui.matchWrap());

        for (int i = 0; i < tasks.size(); i++) {
            DownloadTask t = tasks.get(i);
            TaskCardView card = cards.get(t.id);
            if (card == null) {
                card = new TaskCardView(activity, colors, this::onCardAction);
                TaskCardView self = card;
                card.setOnClickListener(v -> openDetail(self.task()));
                card.setOnLongClickListener(v -> {
                    DownloadTask bound = self.task();
                    if (bound != null) {
                        showCardMenu(bound, self);
                    }
                    return true;
                });
            }
            card.bind(t);
            keep.put(t.id, card);
            LinearLayout.LayoutParams lp = Ui.matchWrap();
            if (i > 0) {
                lp.topMargin = dp(8);
            }
            sections.addView(card, lp);
        }
    }

    // ---- 操作 ----

    private void onCardAction(@NonNull DownloadTask task, @NonNull TaskCardView.Action action) {
        switch (action) {
            case PAUSE -> DownloadRunner.pause(task);
            case RESUME -> DownloadRunner.resume(activity, task);
            case RETRY -> DownloadRunner.retry(activity, task);
            case OPEN -> openFirst(task);
        }
    }

    private void share(@NonNull DownloadTask task) {
        if (!FileActions.share(activity, task.existingFiles(), task.title)) {
            YoToast.show(activity, activity.getString(R.string.task_open_failed));
        }
    }

    private void openDetail(@Nullable DownloadTask task) {
        if (task == null) {
            return;
        }
        if (detail != null) {
            detail.dismiss();
        }
        detail = new TaskDetailSheet(activity, task, this);
        detail.show();
    }

    @Override
    public void openFirst(@NonNull DownloadTask task) {
        List<File> files = task.existingFiles();
        if (files.isEmpty() || !FileActions.open(activity, files.get(0))) {
            YoToast.show(activity, activity.getString(R.string.task_open_failed));
        }
    }

    @Override
    public void confirmDeleteFiles(@NonNull DownloadTask task, @NonNull Runnable after) {
        int n = task.existingFiles().size();
        YoDialog.create(activity)
                .setTitle(activity.getString(R.string.task_delete_title))
                .setMessage(activity.getString(R.string.task_delete_body, n))
                .setNegativeButton(activity.getString(R.string.action_cancel), null)
                .setPositiveButton(activity.getString(R.string.task_action_delete_files), () -> {
                    int deleted = TaskStore.get().remove(task, true);
                    YoToast.show(activity, activity.getString(R.string.task_deleted, deleted));
                    after.run();
                })
                .show();
    }

    private void showCardMenu(@NonNull DownloadTask task, @NonNull View anchor) {
        YoMenu.Builder b = YoMenu.create(activity).setAnchorView(anchor);
        boolean hasLink = task.sourceUrl != null && !task.sourceUrl.isEmpty();
        switch (task.status()) {
            case SUCCESS -> {
                boolean hasFiles = !task.existingFiles().isEmpty();
                if (hasFiles) {
                    b.addItem(MENU_OPEN, activity.getString(R.string.task_action_open), R.drawable.ic_action_play);
                    b.addItem(MENU_SHARE, activity.getString(R.string.task_action_share), R.drawable.ic_action_share);
                }
                if (hasLink) {
                    b.addItem(MENU_COPY, activity.getString(R.string.task_action_copy_link));
                }
                b.addDivider();
                b.addItem(MENU_REMOVE, activity.getString(R.string.task_action_remove));
                if (hasFiles) {
                    b.addDestructiveItem(MENU_DELETE, activity.getString(R.string.task_action_delete_files));
                }
            }
            case FAILED, CANCELED -> {
                if (task.canRetry()) {
                    b.addItem(MENU_RETRY, activity.getString(R.string.task_action_retry), R.drawable.ic_action_retry);
                }
                if (hasLink) {
                    b.addItem(MENU_COPY, activity.getString(R.string.task_action_copy_link));
                }
                b.addDivider();
                b.addDestructiveItem(MENU_REMOVE, activity.getString(R.string.task_action_remove));
            }
            case QUEUED, RUNNING, PAUSED, WAITING_NETWORK -> {
                if (hasLink) {
                    b.addItem(MENU_COPY, activity.getString(R.string.task_action_copy_link));
                    b.addDivider();
                }
                b.addDestructiveItem(MENU_CANCEL, activity.getString(R.string.task_action_cancel));
            }
        }
        b.setOnItemClickListener(id -> onRowMenu(task, id));
        showMenu(b);
    }

    private void onRowMenu(DownloadTask task, int id) {
        switch (id) {
            case MENU_OPEN -> openFirst(task);
            case MENU_SHARE -> share(task);
            case MENU_RETRY -> DownloadRunner.retry(activity, task);
            case MENU_CANCEL -> DownloadRunner.cancel(task);
            case MENU_COPY -> {
                if (task.sourceUrl != null) {
                    FileActions.copyText(activity, task.title, task.sourceUrl);
                    YoToast.show(activity, activity.getString(R.string.task_link_copied));
                }
            }
            case MENU_REMOVE -> {
                TaskStore.get().remove(task, false);
                YoToast.show(activity, activity.getString(R.string.task_removed));
            }
            case MENU_DELETE -> confirmDeleteFiles(task, () -> {
            });
            default -> {
            }
        }
    }

    private void showPageMenu() {
        TaskStore store = TaskStore.get();
        int failed = store.count(DownloadTask.Status.FAILED);
        int finished = store.countFinished();
        YoMenu.Builder b = YoMenu.create(activity).setAnchorView(moreButton);
        int pausable = 0;
        int resumable = 0;
        for (DownloadTask t : store.all()) {
            if (t.canPause()) {
                pausable++;
            } else if (t.canResume()) {
                resumable++;
            }
        }
        if (pausable > 0) {
            b.addItem(MENU_PAUSE_ALL, activity.getString(R.string.tasks_menu_pause_all), R.drawable.ic_action_pause);
        }
        if (resumable > 0) {
            b.addItem(MENU_RESUME_ALL, activity.getString(R.string.tasks_menu_resume_all), R.drawable.ic_action_resume);
        }
        if (failed > 0) {
            b.addItem(MENU_RETRY_FAILED, activity.getString(R.string.tasks_menu_retry_failed), R.drawable.ic_action_retry);
        } else {
            b.addDisabledItem(MENU_RETRY_FAILED, activity.getString(R.string.tasks_menu_retry_failed));
        }
        if (finished > 0) {
            b.addDestructiveItem(MENU_CLEAR_FINISHED, activity.getString(R.string.tasks_menu_clear_finished));
        } else {
            b.addDisabledItem(MENU_CLEAR_FINISHED, activity.getString(R.string.tasks_menu_clear_finished));
        }
        b.setOnItemClickListener(id -> {
            if (id == MENU_PAUSE_ALL) {
                YoToast.show(activity, activity.getString(R.string.tasks_paused_n, DownloadRunner.pauseAll()));
            } else if (id == MENU_RESUME_ALL) {
                YoToast.show(activity, activity.getString(R.string.tasks_resumed_n,
                        DownloadRunner.resumeAll(activity)));
            } else if (id == MENU_RETRY_FAILED) {
                int n = DownloadRunner.retryAll(activity);
                YoToast.show(activity, activity.getString(R.string.tasks_retry_started, n));
            } else if (id == MENU_CLEAR_FINISHED) {
                confirmClearFinished(finished);
            }
        });
        showMenu(b);
    }

    private void confirmClearFinished(int finished) {
        YoDialog.create(activity)
                .setTitle(activity.getString(R.string.settings_clear_confirm_title, finished))
                .setMessage(activity.getString(R.string.settings_clear_confirm_body))
                .setNegativeButton(activity.getString(R.string.action_cancel), null)
                .setPositiveButton(activity.getString(R.string.settings_clear_action), () -> {
                    int removed = TaskStore.get().clearFinished();
                    YoToast.show(activity, activity.getString(R.string.settings_cleared, removed));
                })
                .show();
    }

    private void showMenu(YoMenu.Builder builder) {
        if (menu != null) {
            menu.destroy();
        }
        menu = builder.build();
        menu.show();
    }

    // ---- 测试钩子 ----

    @VisibleForTesting
    public void openDetailForTest(@NonNull DownloadTask task) {
        openDetail(task);
    }

    @VisibleForTesting
    public void dismissDetailForTest() {
        if (detail != null) {
            detail.dismiss();
            detail = null;
        }
    }

    @VisibleForTesting
    public void selectFilterForTest(int index) {
        filterBar.setSelectedIndex(index);
        filter = index;
        render();
    }

    private int dp(float v) {
        return Ui.dp(activity, v);
    }

    @Override
    public void destroy() {
        TaskStore.get().removeListener(this);
        if (detail != null) {
            detail.dismiss();
            detail = null;
        }
        if (menu != null) {
            menu.destroy();
            menu = null;
        }
        filterBar.destroy();
        emptyAction.destroy();
    }
}
