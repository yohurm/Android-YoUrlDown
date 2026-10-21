package com.yohu.urldown.ui.settings;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
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
import com.yohu.youi.api.controls.YoSelect;
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
 * <p>每一节是一张独立列表，组标题只是列表上方的文字。独立列表的每一行按压水波都是四角全圆，
 * 与单行列表一致；按首 / 中 / 末行切圆角只属于带组头的内部分组卡片，这里不用。
 * 行点击按位置分派。多选一的设置（颜色模式、同时下载数）用下拉菜单，行内只占一个尾随控件的位置，
 * 点整行也能打开菜单。
 */
public final class SettingsPage implements TabPage {

    private static final ColorMode[] COLOR_MODES = {
            ColorMode.FOLLOW_SYSTEM, ColorMode.LIGHT, ColorMode.DARK
    };
    private static final int MAX_PARALLEL = 3;

    /** 下载一节的行位置：仅 Wi‑Fi、同时下载、保存位置、清除已结束任务。 */
    private static final int ROW_PARALLEL = 1;
    private static final int ROW_CLEAR_FINISHED = 3;

    private final AppCompatActivity activity;
    private final YoColors colors;
    private final AppSettings settings;
    private final ScrollView scroll;
    private final LinearLayout column;
    private final List<YoList> sections = new ArrayList<>();
    private final YoSelect colorMode;
    private final YoSelect parallel;

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

        YoSelect.Builder colorModes = trailingSelect()
                .setSelectedItemId(indexOf(settings.colorMode()))
                .setOnSelectListener((index, text, itemId) -> {
                    if (itemId >= 0 && itemId < COLOR_MODES.length
                            && COLOR_MODES[itemId] != settings.colorMode()) {
                        // 切换后 AppCompat 会重建 Activity，页签位置由 MainActivity 保存恢复。
                        settings.setColorMode(COLOR_MODES[itemId]);
                    }
                });
        colorModes.addOption(0, activity.getString(R.string.settings_color_follow))
                .addOption(1, activity.getString(R.string.settings_color_light))
                .addOption(2, activity.getString(R.string.settings_color_dark));
        colorMode = colorModes.build();

        YoSelect.Builder parallelCounts = trailingSelect()
                .setSelectedItemId(settings.maxParallel())
                .setOnSelectListener((index, text, itemId) -> {
                    if (itemId != settings.maxParallel()) {
                        settings.setMaxParallel(itemId);
                        DownloadRunner.onSettingsChanged();
                    }
                });
        for (int n = 1; n <= MAX_PARALLEL; n++) {
            parallelCounts.addOption(n, String.valueOf(n));
        }
        parallel = parallelCounts.build();

        addSection(R.string.settings_group_appearance, YoList.create(activity)
                .addItem(ListPrefixSpec.none(),
                        ListTextSpec.of(activity.getString(R.string.settings_color_mode)),
                        ListSuffixSpec.custom(colorMode.getView()))
                .setOnItemClickListener(position -> colorMode.toggle()));

        addSection(R.string.settings_group_parse, YoList.create(activity)
                .addItem(ListPrefixSpec.none(),
                        ListTextSpec.of(activity.getString(R.string.settings_clipboard_title),
                                activity.getString(R.string.settings_clipboard_desc)),
                        ListSuffixSpec.switch_(settings.clipboardDetect(),
                                settings::setClipboardDetect)));

        addSection(R.string.settings_group_download, YoList.create(activity)
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
                .addItem(ListTextSpec.destructive(
                        activity.getString(R.string.settings_clear_finished)))
                .setOnItemClickListener(this::onDownloadRowClick));

        addSection(R.string.settings_group_about, YoList.create(activity)
                .addItemWithLabel(activity.getString(R.string.settings_version), versionLabel())
                .addItemWithLabel(activity.getString(R.string.settings_platforms),
                        activity.getString(R.string.settings_platforms_value, platformCount()))
                .addItemWithLabel(activity.getString(R.string.settings_ui_kit), "YoUI Kit 1.0.0"));

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

    /** 行尾的下拉：小号触发器，菜单与触发器尾边对齐，不压出屏幕右沿。 */
    @NonNull
    private YoSelect.Builder trailingSelect() {
        return YoSelect.create(activity)
                .setControlSize(YoSelect.ControlSize.SMALL)
                .setMenuAlign(YoSelect.MenuAlignType.END);
    }

    private void onDownloadRowClick(int position) {
        if (position == ROW_PARALLEL) {
            parallel.toggle();
        } else if (position == ROW_CLEAR_FINISHED) {
            confirmClearFinished();
        }
    }

    /**
     * 组标题加一张默认样式的独立列表。节与节的间隔就是列表自带的投影留白。
     *
     * @param list {@link YoList#create} 起头、配好本节各行的链，这里收尾 {@code build()}
     */
    private void addSection(@StringRes int captionRes, @NonNull YoList list) {
        LinearLayout.LayoutParams captionLp = Ui.matchWrap();
        captionLp.setMargins(dp(Ui.PAGE_PADDING_DP), 0, dp(Ui.PAGE_PADDING_DP), 0);
        column.addView(Ui.sectionCaption(activity, colors, activity.getString(captionRes)),
                captionLp);
        YoList section = list.build();
        sections.add(section);
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.setMargins(dp(Ui.PAGE_PADDING_DP), 0, dp(Ui.PAGE_PADDING_DP), 0);
        column.addView(section.getView(), lp);
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
        for (YoList section : sections) {
            section.destroy();
        }
    }
}
