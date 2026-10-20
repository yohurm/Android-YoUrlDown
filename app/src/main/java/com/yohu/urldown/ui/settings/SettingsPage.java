package com.yohu.urldown.ui.settings;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.yohu.lib_urlparse.api.Platform;
import com.yohu.urldown.R;
import com.yohu.urldown.settings.AppSettings;
import com.yohu.urldown.task.DownloadRunner;
import com.yohu.urldown.task.TaskStore;
import com.yohu.urldown.ui.TabPage;
import com.yohu.urldown.ui.Ui;
import com.yohu.youi.api.containers.YoList;
import com.yohu.youi.api.containers.list.ListPrefixSpec;
import com.yohu.youi.api.containers.list.ListSuffixSpec;
import com.yohu.youi.api.containers.list.ListTextSpec;
import com.yohu.youi.api.controls.YoSegmentButton;
import com.yohu.youi.api.feedback.YoDialog;
import com.yohu.youi.api.feedback.YoToast;
import com.yohu.youi.api.theme.ColorMode;
import com.yohu.youi.api.theme.YoColors;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页。每一项都接着真实功能，没做的能力不先摆开关占位。
 * 下载相关的（仅 Wi‑Fi、同时下载数）改完立刻通知调度器，不用重启任务。
 *
 * <p>分组用独立的卡片列表 + 组标题，而不是一张长表：行点击回调按位置计，
 * 每组单独一个列表，位置就是组内序号，不会被组头打乱。
 */
public final class SettingsPage implements TabPage {

    private static final ColorMode[] COLOR_MODES = {
            ColorMode.FOLLOW_SYSTEM, ColorMode.LIGHT, ColorMode.DARK
    };

    private final AppCompatActivity activity;
    private final YoColors colors;
    private final AppSettings settings;
    private final ScrollView scroll;
    private final LinearLayout column;
    private final List<YoList> lists = new ArrayList<>();
    private final YoSegmentButton colorMode;
    private final YoSegmentButton parallel;

    public SettingsPage(@NonNull AppCompatActivity activity) {
        this.activity = activity;
        this.colors = YoColors.of(activity);
        this.settings = AppSettings.of(activity);

        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(colors.backgroundSecondary());

        column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(column, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        column.addView(Ui.pageHeader(activity, colors,
                activity.getString(R.string.settings_title)).view, Ui.matchWrap());

        // 外观
        addCaption(R.string.settings_group_appearance);
        colorMode = YoSegmentButton.create(activity)
                .setType(YoSegmentButton.SegmentType.SINGLE_CHOICE)
                .setSize(YoSegmentButton.Size.MEDIUM)
                .addSegment(0, activity.getString(R.string.settings_color_follow))
                .addSegment(1, activity.getString(R.string.settings_color_light))
                .addSegment(2, activity.getString(R.string.settings_color_dark))
                .setSelectedIndex(indexOf(settings.colorMode()))
                .setOnSelectionChangeListener(index -> {
                    if (index >= 0 && index < COLOR_MODES.length
                            && COLOR_MODES[index] != settings.colorMode()) {
                        // 切换后 AppCompat 会重建 Activity，页签位置由 MainActivity 保存恢复。
                        settings.setColorMode(COLOR_MODES[index]);
                    }
                })
                .build();
        column.addView(colorMode.getView(), cardParams());

        // 解析
        addCaption(R.string.settings_group_parse);
        YoList parse = YoList.create(activity)
                .addItem(ListPrefixSpec.none(),
                        ListTextSpec.of(activity.getString(R.string.settings_clipboard_title),
                                activity.getString(R.string.settings_clipboard_desc)),
                        ListSuffixSpec.switch_(settings.clipboardDetect(),
                                settings::setClipboardDetect))
                .build();
        addList(parse);

        // 下载
        addCaption(R.string.settings_group_download);
        parallel = YoSegmentButton.create(activity)
                .setType(YoSegmentButton.SegmentType.SINGLE_CHOICE)
                .setSize(YoSegmentButton.Size.SMALL)
                .addSegment(0, "1")
                .addSegment(1, "2")
                .addSegment(2, "3")
                .setSelectedIndex(settings.maxParallel() - 1)
                .setOnSelectionChangeListener(index -> {
                    if (index >= 0 && index + 1 != settings.maxParallel()) {
                        settings.setMaxParallel(index + 1);
                        DownloadRunner.onSettingsChanged();
                    }
                })
                .build();
        parallel.getView().setLayoutParams(new LinearLayout.LayoutParams(dp(120),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        YoList download = YoList.create(activity)
                .addItem(ListPrefixSpec.none(),
                        ListTextSpec.of(activity.getString(R.string.settings_wifi_only_title),
                                activity.getString(R.string.settings_wifi_only_desc)),
                        ListSuffixSpec.switch_(settings.wifiOnly(), checked -> {
                            settings.setWifiOnly(checked);
                            DownloadRunner.onSettingsChanged();
                        }))
                .addItem(ListPrefixSpec.none(),
                        ListTextSpec.of(activity.getString(R.string.settings_parallel_title),
                                activity.getString(R.string.settings_parallel_desc)),
                        ListSuffixSpec.custom(parallel.getView()))
                .addItem(ListTextSpec.of(activity.getString(R.string.settings_save_dir),
                        DownloadRunner.downloadDir(activity).getAbsolutePath()))
                .addItem(ListTextSpec.destructive(activity.getString(R.string.settings_clear_finished)))
                .setShowDividers(true)
                .setOnItemClickListener(position -> {
                    if (position == 3) {
                        confirmClearFinished();
                    }
                })
                .build();
        addList(download);

        // 关于
        addCaption(R.string.settings_group_about);
        YoList about = YoList.create(activity)
                .addItemWithLabel(activity.getString(R.string.settings_version), versionLabel())
                .addItemWithLabel(activity.getString(R.string.settings_platforms),
                        activity.getString(R.string.settings_platforms_value, platformCount()))
                .addItemWithLabel(activity.getString(R.string.settings_ui_kit), "YoUI Kit 1.0.0")
                .setShowDividers(true)
                .build();
        addList(about);

        View tail = new View(activity);
        column.addView(tail, new LinearLayout.LayoutParams(1, dp(24)));
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
        scroll.setPadding(0, 0, 0, insetPx);
    }

    @Override
    public void scrollToTop() {
        scroll.smoothScrollTo(0, 0);
    }

    private void confirmClearFinished() {
        TaskStore store = TaskStore.get();
        int finished = store.countFinished();
        if (finished == 0) {
            YoToast.show(activity, activity.getString(R.string.settings_clear_nothing));
            return;
        }
        YoDialog.create(activity)
                .setTitle(activity.getString(R.string.settings_clear_confirm_title, finished))
                .setMessage(activity.getString(R.string.settings_clear_confirm_body))
                .setNegativeButton(activity.getString(R.string.action_cancel), null)
                .setPositiveButton(activity.getString(R.string.settings_clear_action), () -> {
                    int removed = store.clearFinished();
                    YoToast.show(activity, activity.getString(R.string.settings_cleared, removed));
                })
                .show();
    }

    private void addCaption(int textRes) {
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.setMargins(dp(Ui.PAGE_PADDING_DP), 0, dp(Ui.PAGE_PADDING_DP), 0);
        column.addView(Ui.sectionCaption(activity, colors, activity.getString(textRes)), lp);
    }

    private void addList(YoList list) {
        lists.add(list);
        View scroller = Ui.findScroller(list.getView());
        if (scroller != null) {
            // 嵌在页面滚动里，列表自身不再抢滚动。
            scroller.setNestedScrollingEnabled(false);
        }
        column.addView(list.getView(), cardParams());
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.setMargins(dp(Ui.PAGE_PADDING_DP), 0, dp(Ui.PAGE_PADDING_DP), 0);
        return lp;
    }

    private static int indexOf(ColorMode mode) {
        for (int i = 0; i < COLOR_MODES.length; i++) {
            if (COLOR_MODES[i] == mode) {
                return i;
            }
        }
        return 0;
    }

    private String versionLabel() {
        try {
            PackageInfo info = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            return info.versionName + " (" + info.getLongVersionCode() + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return "-";
        }
    }

    private static int platformCount() {
        int n = 0;
        for (Platform p : Platform.values()) {
            if (p != Platform.UNKNOWN) {
                n++;
            }
        }
        return n;
    }

    private int dp(float v) {
        return Ui.dp(activity, v);
    }

    @Override
    public void destroy() {
        colorMode.destroy();
        parallel.destroy();
        for (YoList list : lists) {
            list.destroy();
        }
    }
}
