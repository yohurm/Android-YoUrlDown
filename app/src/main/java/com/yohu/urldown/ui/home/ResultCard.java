package com.yohu.urldown.ui.home;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.yohu.lib_urlparse.api.FailureReason;
import com.yohu.lib_urlparse.api.MediaAsset;
import com.yohu.lib_urlparse.api.ParseFailure;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.urldown.R;
import com.yohu.urldown.ui.Ui;
import com.yohu.urldown.ui.widget.PlatformMark;
import com.yohu.youi.api.containers.YoCard;
import com.yohu.youi.api.controls.YoButton;
import com.yohu.youi.api.feedback.YoDialog;
import com.yohu.youi.api.theme.YoColors;

import java.util.List;
import java.util.Locale;

/**
 * 解析结果卡。只展示引擎真实返回的字段，缺什么就不显示什么，不用占位文案凑满。
 *
 * <p>卡片平放（无阴影）：它和输入框在同一层，不需要“浮起来”。
 * 品牌色只出现在主按钮和平台标签上。
 */
final class ResultCard {

    private static final int MAX_ASSET_ROWS = 4;

    private final AppCompatActivity activity;
    private final YoColors colors;

    ResultCard(@NonNull AppCompatActivity activity, @NonNull YoColors colors) {
        this.activity = activity;
        this.colors = colors;
    }

    /** 结果卡的下载按钮交给主页处理：要不要提醒重复、加入后去哪看。 */
    interface DownloadHost {
        /** 加入任务列表；真正加入后调用 {@code onQueued}（用户在重复提醒里放弃则不调用）。 */
        void download(@NonNull UrlParseResult result, @NonNull Runnable onQueued);

        void viewTasks();
    }

    @NonNull
    View bindSuccess(@NonNull UrlParseResult result, @NonNull DownloadHost host) {
        LinearLayout body = body();

        LinearLayout tagRow = new LinearLayout(activity);
        tagRow.setOrientation(LinearLayout.HORIZONTAL);
        tagRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(activity);
        mark.setImageResource(PlatformMark.iconOf(result.platform()));
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tagRow.addView(mark, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView platform = Ui.medium(activity, result.platform().displayName(), 13f, colors.textPrimary());
        LinearLayout.LayoutParams platformLp = Ui.wrap();
        platformLp.setMarginStart(dp(6));
        tagRow.addView(platform, platformLp);
        if (!TextUtils.isEmpty(result.workId())) {
            TextView id = Ui.text(activity, result.workId(), Ui.TEXT_CAPTION, colors.textTertiary());
            id.setSingleLine(true);
            id.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginStart(dp(8));
            tagRow.addView(id, lp);
        }
        body.addView(tagRow, Ui.matchWrap());

        if (!TextUtils.isEmpty(result.title())) {
            TextView title = Ui.medium(activity, result.title().trim(), Ui.TEXT_CARD_TITLE,
                    colors.textPrimary());
            title.setMaxLines(3);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setLineSpacing(0f, 1.15f);
            body.addView(title, topMargin(12));
        }

        String author = result.author() == null ? null : result.author().nickname();
        if (!TextUtils.isEmpty(author)) {
            body.addView(Ui.text(activity, activity.getString(R.string.result_author, author),
                    13f, colors.textSecondary()), topMargin(4));
        }

        int videos = result.assets(MediaAsset.Kind.VIDEO).size();
        int images = result.assets(MediaAsset.Kind.IMAGE).size();
        int audios = result.assets(MediaAsset.Kind.AUDIO).size();
        body.addView(Ui.text(activity,
                activity.getString(R.string.result_media_counts, videos, images, audios),
                13f, colors.textSecondary()), topMargin(4));

        List<MediaAsset> assets = result.downloadable();
        if (assets.isEmpty()) {
            body.addView(Ui.text(activity, activity.getString(R.string.result_no_media),
                    Ui.TEXT_BODY, colors.textSecondary()), topMargin(16));
        } else {
            body.addView(divider(), dividerParams());
            int shown = Math.min(MAX_ASSET_ROWS, assets.size());
            for (int i = 0; i < shown; i++) {
                body.addView(assetRow(assets.get(i)), topMargin(i == 0 ? 0 : 10));
            }
            if (assets.size() > shown) {
                body.addView(Ui.text(activity,
                        activity.getString(R.string.result_more_assets, assets.size() - shown),
                        Ui.TEXT_CAPTION, colors.textTertiary()), topMargin(10));
            }
        }

        LinearLayout actions = actionsRow();
        YoButton details = YoButton.create(activity)
                .setText(activity.getString(R.string.result_action_details))
                .setStyle(YoButton.Style.TEXT)
                .setOnClickListener(v -> showText(activity.getString(R.string.result_action_details),
                        result.trace() == null ? "-" : result.trace().summarize()))
                .build();
        actions.addView(details.getView(), Ui.wrap());
        actions.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1f));
        if (!assets.isEmpty()) {
            final YoButton[] holder = new YoButton[1];
            final boolean[] queued = new boolean[1];
            holder[0] = YoButton.create(activity)
                    .setText(activity.getString(R.string.result_action_download))
                    .setStyle(YoButton.Style.PRIMARY)
                    .setOnClickListener(v -> {
                        if (queued[0]) {
                            host.viewTasks();
                            return;
                        }
                        host.download(result, () -> {
                            // 同一条结果只排一次；按钮变成去任务页的入口，下一步一目了然。
                            queued[0] = true;
                            holder[0].setText(activity.getString(R.string.result_action_view_tasks));
                            holder[0].setStyle(YoButton.Style.SECONDARY);
                        });
                    })
                    .build();
            actions.addView(holder[0].getView(), Ui.wrap());
        }
        body.addView(actions, topMargin(16));
        View card = card(body);
        card.setContentDescription(matrixSummary(result));
        return card;
    }

    @NonNull
    View bindFailure(@Nullable ParseFailure failure, @Nullable Throwable error,
                     @NonNull Runnable onRetry) {
        LinearLayout body = body();
        body.addView(Ui.medium(activity, activity.getString(R.string.result_failed_title),
                Ui.TEXT_CARD_TITLE, colors.textPrimary()), Ui.matchWrap());

        String hint;
        String meta;
        String detail;
        boolean retryable;
        if (failure != null) {
            hint = failure.userHint();
            String channel = failure.channel().isEmpty()
                    ? activity.getString(R.string.result_failed_no_channel) : failure.channel();
            meta = activity.getString(R.string.result_failed_meta, reasonLabel(failure.reason()), channel);
            detail = failure.getMessage();
            retryable = failure.retryable();
        } else {
            hint = "解析过程中出现异常";
            meta = error == null ? null : error.getClass().getSimpleName();
            detail = String.valueOf(error);
            retryable = true;
        }
        if (!TextUtils.isEmpty(hint)) {
            TextView hintView = Ui.text(activity, hint, Ui.TEXT_BODY, colors.textSecondary());
            hintView.setLineSpacing(0f, 1.2f);
            body.addView(hintView, topMargin(6));
        }
        if (!TextUtils.isEmpty(meta)) {
            body.addView(Ui.text(activity, meta, Ui.TEXT_CAPTION, colors.textTertiary()), topMargin(8));
        }

        LinearLayout actions = actionsRow();
        YoButton details = YoButton.create(activity)
                .setText(activity.getString(R.string.result_action_failure_details))
                .setStyle(YoButton.Style.TEXT)
                .setOnClickListener(v -> showText(
                        activity.getString(R.string.result_action_failure_details),
                        detail == null ? "-" : detail))
                .build();
        actions.addView(details.getView(), Ui.wrap());
        actions.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1f));
        if (retryable) {
            YoButton retry = YoButton.create(activity)
                    .setText(activity.getString(R.string.result_action_retry))
                    .setStyle(YoButton.Style.SECONDARY)
                    .setOnClickListener(v -> onRetry.run())
                    .build();
            actions.addView(retry.getView(), Ui.wrap());
        }
        body.addView(actions, topMargin(16));
        View card = card(body);
        card.setContentDescription(matrixSummary(failure, error));
        return card;
    }

    /*
     * 真机复验矩阵（tools/device-matrix.ps1）靠 uiautomator dump 读结果：
     * 成功找 "平台:/通道:/媒体:" 行，失败找 "解析失败/原因:/通道:/详情:" 行。
     * 新界面不再把这些原始字段直接铺在屏幕上，所以放进卡片的 contentDescription，
     * 格式与旧版首页文本保持一致；改格式要同步改脚本。
     */
    @NonNull
    static String matrixSummary(@NonNull UrlParseResult r) {
        return "平台: " + r.platform().displayName() + "\n"
                + "通道: " + r.channel() + "\n"
                + "媒体: 视频 " + r.assets(MediaAsset.Kind.VIDEO).size()
                + " / 图片 " + r.assets(MediaAsset.Kind.IMAGE).size()
                + " / 音频 " + r.assets(MediaAsset.Kind.AUDIO).size();
    }

    @NonNull
    static String matrixSummary(@Nullable ParseFailure f, @Nullable Throwable error) {
        if (f == null) {
            return "解析失败\n原因: EXCEPTION\n通道: (无通道认领)\n详情: " + error;
        }
        return "解析失败\n"
                + "原因: " + f.reason() + "\n"
                + "通道: " + (f.channel().isEmpty() ? "(无通道认领)" : f.channel()) + "\n"
                + "详情: " + f.getMessage();
    }

    @NonNull
    static String reasonLabel(@NonNull FailureReason reason) {
        return switch (reason) {
            case UNRECOGNISED_INPUT -> "无法识别输入";
            case NO_MEDIA_FOUND -> "没有找到媒体";
            case UNEXPECTED_RESPONSE -> "返回内容异常";
            case NETWORK -> "网络问题";
            case CREDENTIALS_MISSING -> "缺少登录凭据";
            case CHANNEL_DISABLED -> "通道已停用";
            case CANCELLED -> "已取消";
            case INTERNAL -> "内部错误";
        };
    }

    private View assetRow(MediaAsset asset) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        StringBuilder line = new StringBuilder(kindLabel(asset.kind()));
        if (asset.width() > 0 && asset.height() > 0) {
            line.append(" · ").append(asset.width()).append('×').append(asset.height());
        }
        if (asset.durationMs() > 0) {
            line.append(" · ").append(formatDuration(asset.durationMs()));
        }
        if (!asset.alternates().isEmpty()) {
            line.append(" · 备用地址 ").append(asset.alternates().size());
        }
        row.addView(Ui.text(activity, line, Ui.TEXT_BODY, colors.textPrimary()), Ui.matchWrap());
        TextView url = Ui.text(activity, asset.url(), Ui.TEXT_CAPTION, colors.textTertiary());
        url.setTypeface(Typeface.MONOSPACE);
        url.setSingleLine(true);
        url.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.addView(url, topMargin(2));
        return row;
    }

    private static String kindLabel(MediaAsset.Kind kind) {
        return switch (kind) {
            case VIDEO -> "视频";
            case IMAGE -> "图片";
            case AUDIO -> "音频";
            case COVER -> "封面";
        };
    }

    private static String formatDuration(long ms) {
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private void showText(String title, String text) {
        YoDialog.create(activity)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton(activity.getString(R.string.dialog_close), null)
                .show();
    }

    private LinearLayout body() {
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        // YoCard 内容区自带 16dp 内边距，这里不再叠加。
        body.setPadding(0, 0, 0, 0);
        return body;
    }

    private LinearLayout actionsRow() {
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        return actions;
    }

    private View card(View content) {
        return YoCard.create(activity)
                .setContentView(content)
                .setBackgroundColor(colors.componentBackgroundPrimary())
                .setShadowEnabled(false)
                .setElevation(0f)
                .build()
                .getView();
    }

    private View divider() {
        View v = new View(activity);
        v.setBackgroundColor(colors.componentDivider());
        return v;
    }

    private LinearLayout.LayoutParams dividerParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(14);
        return lp;
    }

    private LinearLayout.LayoutParams topMargin(int dpValue) {
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.topMargin = dp(dpValue);
        return lp;
    }

    private int dp(float v) {
        return Ui.dp(activity, v);
    }
}
