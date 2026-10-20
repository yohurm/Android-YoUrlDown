package com.yohu.urldown.ui.home;

import android.animation.LayoutTransition;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatActivity;

import com.yohu.lib_urlparse.api.ParseFailure;
import com.yohu.lib_urlparse.api.Platform;
import com.yohu.lib_urlparse.api.UrlParseRequest;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.urldown.EngineBootstrap;
import com.yohu.urldown.R;
import com.yohu.urldown.settings.AppSettings;
import com.yohu.urldown.task.DownloadRunner;
import com.yohu.urldown.task.DownloadTask;
import com.yohu.urldown.task.NotifyPermission;
import com.yohu.urldown.task.TaskStore;
import com.yohu.urldown.ui.TabPage;
import com.yohu.urldown.ui.Ui;
import com.yohu.urldown.ui.widget.PlatformMark;
import com.yohu.youi.api.controls.YoButton;
import com.yohu.youi.api.controls.YoTextInput;
import com.yohu.youi.api.feedback.YoDialog;
import com.yohu.youi.api.feedback.YoToast;
import com.yohu.youi.api.theme.YoColors;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主页：一个输入框，解析一切链接。
 *
 * <p>布局：空闲时输入区在可视区的视觉中心（略高于几何中心，上下留白 2:3）；
 * 出结果后结果卡接在按钮下方，留白收缩，输入区自然上移，形成“输入 → 结果”的阅读顺序。
 * 只有一个主操作（解析），粘贴是文字按钮，避免并排两个同权重按钮。
 */
public final class HomePage implements TabPage {

    private static final String STATE_INPUT = "home.input";
    private static final int MAX_CONTENT_WIDTH_DP = 520;

    /** 解析在单独线程跑：网络和 WebView 等待都不能占主线程。进程级，重建 Activity 不中断。 */
    private static final ExecutorService PARSE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "yo-parse");
        t.setDaemon(true);
        return t;
    });

    private final AppCompatActivity activity;
    private final YoColors colors;

    private final ScrollView scroll;
    private final LinearLayout column;
    private final YoTextInput input;
    private final YoButton pasteButton;
    private final YoButton parseButton;
    private final LinearLayout resultSlot;
    private final View topSpacer;

    @Nullable
    private Runnable onViewTasks;
    private boolean destroyed;
    private boolean parsing;
    private int requestSeq;
    /** 上次自动读到的剪贴板文本，同一段文字只自动填一次，用户清空后不再反复填回。 */
    @Nullable
    private String lastAutoClip;

    public HomePage(@NonNull AppCompatActivity activity) {
        this.activity = activity;
        this.colors = YoColors.of(activity);

        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(colors.backgroundSecondary());

        column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = sidePadding();
        column.setPadding(side, dp(24), side, dp(16));
        LayoutTransition transition = new LayoutTransition();
        transition.enableTransitionType(LayoutTransition.CHANGING);
        column.setLayoutTransition(transition);
        scroll.addView(column, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        topSpacer = new View(activity);
        column.addView(topSpacer, spacer(2f));

        TextView title = Ui.bold(activity, activity.getString(R.string.home_title),
                24f, colors.textPrimary());
        title.setGravity(Gravity.CENTER);
        column.addView(title, Ui.matchWrap());

        TextView subtitle = Ui.text(activity, activity.getString(R.string.home_subtitle),
                Ui.TEXT_BODY, colors.textSecondary());
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleLp = Ui.matchWrap();
        subtitleLp.topMargin = dp(6);
        column.addView(subtitle, subtitleLp);

        input = YoTextInput.create(activity)
                .setPlaceholder(activity.getString(R.string.home_input_hint))
                .setStyle(YoTextInput.Style.DEFAULT)
                .setSize(YoTextInput.Size.NORMAL)
                .setMultiline(true, 3, 6)
                .setShowCancel(true)
                .setShowErrorEnabled(true)
                .setBackgroundColor(colors.componentBackgroundPrimary())
                .setOnValueChangeListener(value -> clearError())
                .build();
        LinearLayout.LayoutParams inputLp = Ui.matchWrap();
        inputLp.topMargin = dp(24);
        column.addView(input.getView(), inputLp);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        pasteButton = YoButton.create(activity)
                .setText(activity.getString(R.string.home_action_paste))
                .setStyle(YoButton.Style.TEXT)
                .setSize(YoButton.Size.DEFAULT)
                .setOnClickListener(v -> pasteFromClipboard(true))
                .build();
        actions.addView(pasteButton.getView(), Ui.wrap());
        actions.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1f));
        parseButton = YoButton.create(activity)
                .setText(activity.getString(R.string.home_action_parse))
                .setStyle(YoButton.Style.PRIMARY)
                .setSize(YoButton.Size.DEFAULT)
                .setOnClickListener(v -> submit())
                .build();
        LinearLayout.LayoutParams parseLp = new LinearLayout.LayoutParams(dp(120),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        actions.addView(parseButton.getView(), parseLp);
        LinearLayout.LayoutParams actionsLp = Ui.matchWrap();
        actionsLp.topMargin = dp(12);
        column.addView(actions, actionsLp);

        resultSlot = new LinearLayout(activity);
        resultSlot.setOrientation(LinearLayout.VERTICAL);
        resultSlot.setVisibility(View.GONE);
        LinearLayout.LayoutParams resultLp = Ui.matchWrap();
        resultLp.topMargin = dp(24);
        column.addView(resultSlot, resultLp);

        // 底部留白始终保留：空闲时与顶部按 2:3 分配让输入区居中略偏上，出结果后把平台说明压到底。
        column.addView(new View(activity), spacer(3f));

        LinearLayout.LayoutParams marksLp = Ui.matchWrap();
        marksLp.topMargin = dp(24);
        column.addView(platformMarks(), marksLp);
        TextView platforms = Ui.text(activity, platformsLine(), Ui.TEXT_CAPTION,
                colors.textTertiary());
        platforms.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams platformsLp = Ui.matchWrap();
        platformsLp.topMargin = dp(8);
        column.addView(platforms, platformsLp);
    }

    @NonNull
    @Override
    public View getView() {
        return scroll;
    }

    @Nullable
    @Override
    public View getScroller() {
        return scroll;
    }

    @Override
    public void setBottomInset(int insetPx) {
        if (scroll.getPaddingBottom() == insetPx && !scroll.isInLayout()) {
            return;
        }
        scroll.setPadding(0, 0, 0, insetPx);
        // 底栏高度在宿主布局途中回调；此时改内边距，本轮布局会清掉重排标记而沿用旧的测量，
        // 空闲态撑满视口的内容列就还按没有留白的高度排，平台行落到悬浮底栏下面。下一帧再排一次。
        scroll.post(scroll::requestLayout);
    }

    @Override
    public void scrollToTop() {
        scroll.smoothScrollTo(0, 0);
    }

    public void saveState(@NonNull Bundle out) {
        out.putCharSequence(STATE_INPUT, input.getText());
    }

    public void restoreState(@Nullable Bundle in) {
        if (in == null) {
            return;
        }
        CharSequence text = in.getCharSequence(STATE_INPUT);
        if (text != null && text.length() > 0) {
            input.setText(text);
        }
    }

    /** 外部带入的链接（adb / 分享）：填入并直接解析。 */
    public void parseExternal(@NonNull String text) {
        input.setText(text);
        submit();
    }

    /** 窗口拿到焦点后才能读剪贴板（Android 10+ 的限制）。 */
    public void onWindowFocused() {
        if (destroyed || parsing || input.getText().length() > 0) {
            return;
        }
        if (!AppSettings.of(activity).clipboardDetect()) {
            return;
        }
        String clip = readClipboard();
        if (clip == null || clip.equals(lastAutoClip) || !looksLikeLink(clip)) {
            return;
        }
        lastAutoClip = clip;
        input.setText(clip);
        YoToast.show(activity, activity.getString(R.string.home_clipboard_filled));
    }

    private void pasteFromClipboard(boolean manual) {
        String clip = readClipboard();
        if (clip == null) {
            if (manual) {
                YoToast.show(activity, activity.getString(R.string.home_clipboard_empty));
            }
            return;
        }
        lastAutoClip = clip;
        input.setText(clip);
    }

    private void submit() {
        if (parsing) {
            return;
        }
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            input.showError(activity.getString(R.string.home_error_empty));
            return;
        }
        hideKeyboard();
        startParse(text);
    }

    private void startParse(String text) {
        parsing = true;
        final int seq = ++requestSeq;
        parseButton.setLoading(true);
        pasteButton.setEnabled(false);
        input.setEnabled(false);
        PARSE_EXECUTOR.execute(() -> {
            UrlParseResult result = null;
            ParseFailure failure = null;
            Throwable error = null;
            try {
                result = EngineBootstrap.engine(activity)
                        .parse(UrlParseRequest.builder(text).build());
            } catch (ParseFailure f) {
                failure = f;
            } catch (Throwable t) {
                error = t;
            }
            UrlParseResult r = result;
            ParseFailure f = failure;
            Throwable e = error;
            activity.runOnUiThread(() -> {
                if (destroyed || seq != requestSeq) {
                    return;
                }
                parsing = false;
                parseButton.setLoading(false);
                pasteButton.setEnabled(true);
                input.setEnabled(true);
                if (r != null) {
                    showResult(r);
                } else {
                    showFailure(f, e, text);
                }
            });
        });
    }

    @VisibleForTesting
    public void showResultForTest(@NonNull UrlParseResult result) {
        showResult(result);
    }

    @VisibleForTesting
    public void showFailureForTest(@NonNull ParseFailure failure) {
        showFailure(failure, null, "");
    }

    private void showResult(@NonNull UrlParseResult result) {
        ResultCard card = new ResultCard(activity, colors);
        View view = card.bindSuccess(result, new ResultCard.DownloadHost() {
            @Override
            public void download(@NonNull UrlParseResult r, @NonNull Runnable onQueued) {
                confirmAndEnqueue(r, onQueued);
            }

            @Override
            public void viewTasks() {
                if (onViewTasks != null) {
                    onViewTasks.run();
                }
            }
        });
        setResultView(view);
    }

    /**
     * 加入任务列表。同一条原链接正在下载就不再加，直接引导去任务页；
     * 已经下载过且文件还在，问一句再下。
     */
    private void confirmAndEnqueue(@NonNull UrlParseResult result, @NonNull Runnable onQueued) {
        DownloadTask dup = TaskStore.get().findDuplicate(result.sourceUrl());
        if (dup == null) {
            enqueue(result, onQueued);
            return;
        }
        boolean running = dup.status().isUnfinished();
        YoDialog.Builder dialog = YoDialog.create(activity)
                .setTitle(activity.getString(running
                        ? R.string.result_dup_running_title : R.string.result_dup_done_title))
                .setMessage(activity.getString(running
                        ? R.string.result_dup_running_body : R.string.result_dup_done_body));
        if (running) {
            dialog.setNegativeButton(activity.getString(R.string.action_cancel), null)
                    .setPositiveButton(activity.getString(R.string.result_action_view_tasks), () -> {
                        if (onViewTasks != null) {
                            onViewTasks.run();
                        }
                    });
        } else {
            dialog.setNegativeButton(activity.getString(R.string.action_cancel), null)
                    .setPositiveButton(activity.getString(R.string.result_dup_again),
                            () -> enqueue(result, onQueued));
        }
        dialog.show();
    }

    private void enqueue(@NonNull UrlParseResult result, @NonNull Runnable onQueued) {
        NotifyPermission.askOnce(activity);
        DownloadRunner.enqueue(activity, result);
        YoToast.show(activity, activity.getString(R.string.result_queued_toast));
        onQueued.run();
    }

    /** 结果卡上的“查看任务”切到任务页（由外壳提供）。 */
    public void setOnViewTasks(@Nullable Runnable action) {
        onViewTasks = action;
    }

    private void showFailure(@Nullable ParseFailure failure, @Nullable Throwable error,
                             @NonNull String text) {
        ResultCard card = new ResultCard(activity, colors);
        View view = card.bindFailure(failure, error, () -> startParse(text));
        setResultView(view);
    }

    private void setResultView(@NonNull View view) {
        resultSlot.removeAllViews();
        resultSlot.addView(view, Ui.matchWrap());
        resultSlot.setVisibility(View.VISIBLE);
        // 有结果时去掉顶部留白，输入区上移，结果卡紧随其后。
        topSpacer.setVisibility(View.GONE);
    }

    private void clearError() {
        input.showError(null);
    }

    private void hideKeyboard() {
        View focus = activity.getCurrentFocus();
        InputMethodManager imm = (InputMethodManager) activity
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (focus != null && imm != null) {
            imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            focus.clearFocus();
        }
    }

    @Nullable
    private String readClipboard() {
        ClipboardManager cm = (ClipboardManager) activity
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            return null;
        }
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return null;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(activity);
        if (text == null) {
            return null;
        }
        String s = text.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean looksLikeLink(@NonNull String text) {
        return text.contains("http://") || text.contains("https://");
    }

    /** 一排平台图标，放得下几个放几个（窄屏少放），顺序同引擎枚举。 */
    @NonNull
    private View platformMarks() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        int size = 18;
        int gap = 6;
        int screenDp = activity.getResources().getConfiguration().screenWidthDp;
        int availDp = Math.min(screenDp, MAX_CONTENT_WIDTH_DP) - 48;
        int fit = Math.max(1, (availDp + gap) / (size + gap));
        int shown = 0;
        for (Platform p : Platform.values()) {
            if (p == Platform.UNKNOWN || shown >= fit) {
                continue;
            }
            ImageView icon = new ImageView(activity);
            icon.setImageResource(PlatformMark.iconOf(p));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(size), dp(size));
            if (shown > 0) {
                lp.setMarginStart(dp(gap));
            }
            row.addView(icon, lp);
            shown++;
        }
        return row;
    }

    /** 平台清单取自解析引擎的枚举，数量随引擎走，不写死。 */
    @NonNull
    private String platformsLine() {
        List<String> names = new ArrayList<>();
        for (Platform p : Platform.values()) {
            if (p != Platform.UNKNOWN) {
                names.add(p.displayName());
            }
        }
        List<String> head = names.subList(0, Math.min(3, names.size()));
        return activity.getString(R.string.home_platforms, String.join("、", head), names.size());
    }

    private int sidePadding() {
        int screenDp = activity.getResources().getConfiguration().screenWidthDp;
        int side = Math.max(24, (screenDp - MAX_CONTENT_WIDTH_DP) / 2);
        return dp(side);
    }

    private LinearLayout.LayoutParams spacer(float weight) {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, weight);
    }

    private int dp(float v) {
        return Ui.dp(activity, v);
    }

    @Override
    public void destroy() {
        destroyed = true;
        input.destroy();
        pasteButton.destroy();
        parseButton.destroy();
    }
}
