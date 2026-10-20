package com.yohu.urldown;

import android.content.Intent;
import android.os.Bundle;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.yohu.urldown.settings.AppSettings;
import com.yohu.urldown.ui.TabPage;
import com.yohu.urldown.ui.home.HomePage;
import com.yohu.urldown.ui.settings.SettingsPage;
import com.yohu.urldown.ui.tasks.TasksPage;
import com.yohu.youi.api.navigation.YoTabs;
import com.yohu.youi.api.platform.YoSystemBar;
import com.yohu.youi.api.theme.YoColors;

/**
 * 应用外壳：YoTabs 作为窗口根容器，挂主页 / 任务 / 设置三页。
 *
 * <p>底栏用悬浮胶囊（FLOATING）：栏叠在内容上，内容一直铺到屏幕底，
 * 各页按 {@link YoTabs.OnContentInsetListener} 给的高度在末尾留白，最后一项能滚到栏上方；
 * 往下滚时胶囊跟手收起，往回滚再出现，长列表（任务页）多一屏可视区域。
 *
 * <p>外部带入链接的两种入口，都会切到主页并直接解析：
 * <ul>
 *   <li>其他应用“分享 → YoUrlDown”（{@code ACTION_SEND text/plain}，分享文字里夹着链接也行）；</li>
 *   <li>真机自动化：{@code adb shell am start -n com.yohu.urldown/.MainActivity -e url "<链接>"}。</li>
 * </ul>
 */
public class MainActivity extends AppCompatActivity {

    private static final String STATE_TAB = "main.tab";
    private static final String EXTRA_URL = "url";
    /** 打开后切到哪一页（通知点进来时带 {@link #TAB_TASKS}）。 */
    public static final String EXTRA_TAB = "tab";

    static final int TAB_HOME = 1;
    public static final int TAB_TASKS = 2;
    static final int TAB_SETTINGS = 3;

    private YoTabs tabs;
    private HomePage home;
    private TasksPage tasks;
    private SettingsPage settings;
    private int imeBottomPx;
    private int barInsetPx;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        AppSettings.applyColorMode(AppSettings.of(this).colorMode());
        super.onCreate(savedInstanceState);
        YoSystemBar.prepareTransparentSystemBars(this);
        YoColors colors = YoColors.of(this);
        YoSystemBar.setStatusBarDarkIcons(this, !colors.isDark());

        home = new HomePage(this);
        tasks = new TasksPage(this, () -> tabs.setSelectedId(TAB_HOME));
        settings = new SettingsPage(this);
        home.restoreState(savedInstanceState);
        home.setOnViewTasks(() -> tabs.setSelectedId(TAB_TASKS));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(colors.backgroundSecondary());

        int initialTab = savedInstanceState == null
                ? TAB_HOME : savedInstanceState.getInt(STATE_TAB, TAB_HOME);
        tabs = YoTabs.create(this)
                .setBarStyle(YoTabs.BarStyle.FLOATING)
                .setBarOverlap(true)
                .setHidePolicy(YoTabs.HidePolicy.FOLLOW_SCROLL)
                .setItemLayout(YoTabs.ItemLayout.AUTO)
                .setBleedItemId(YoTabs.NO_BLEED)
                .addTab(TAB_HOME, getString(R.string.tab_home),
                        R.drawable.ic_tab_home, R.drawable.ic_tab_home_fill, home.getView())
                .addTab(TAB_TASKS, getString(R.string.tab_tasks),
                        R.drawable.ic_tab_tasks, R.drawable.ic_tab_tasks_fill, tasks.getView())
                .addTab(TAB_SETTINGS, getString(R.string.tab_settings),
                        R.drawable.ic_tab_settings, R.drawable.ic_tab_settings_fill,
                        settings.getView())
                .setSelectedId(initialTab)
                .setOnChangeListener((index, id) -> onTabChanged(id))
                .setOnReselectListener((index, id) -> page(id).scrollToTop())
                .setOnContentInsetListener(inset -> {
                    barInsetPx = inset;
                    applyBottomInsets();
                })
                .build();
        root.addView(tabs.getView(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // 顶部让出状态栏；底部导航条由 YoTabs 自己处理（胶囊浮在导航条之上）。
        // 键盘弹出时根布局不缩（透明系统栏下 adjustResize 不生效），键盘盖住胶囊，
        // 只把主页的底部留白加到键盘高度，让输入框停在键盘上方。
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = YoSystemBar.systemBarsAndCutout(insets);
            view.setPadding(bars.left, bars.top, bars.right, 0);
            imeBottomPx = insets.isVisible(WindowInsetsCompat.Type.ime())
                    ? insets.getInsets(WindowInsetsCompat.Type.ime()).bottom : 0;
            applyBottomInsets();
            return insets;
        });

        bindScroller();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && tabs != null && tabs.getSelectedId() == TAB_HOME) {
            home.onWindowFocused();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (tabs != null) {
            outState.putInt(STATE_TAB, tabs.getSelectedId());
        }
        home.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        if (tabs != null) {
            tabs.destroy();
            tabs = null;
        }
        home.destroy();
        tasks.destroy();
        settings.destroy();
        super.onDestroy();
    }

    @VisibleForTesting
    void selectTabForTest(int id) {
        tabs.setSelectedId(id);
    }

    @VisibleForTesting
    HomePage homeForTest() {
        return home;
    }

    @VisibleForTesting
    TasksPage tasksForTest() {
        return tasks;
    }

    private void onTabChanged(int id) {
        bindScroller();
        applyBottomInsets();
        page(id).onSelected();
    }

    private void bindScroller() {
        if (tabs != null) {
            tabs.bindScroller(page(tabs.getSelectedId()).getScroller());
        }
    }

    /**
     * 页底留白。内容铺到屏幕底，留白 = 胶囊占的高度（含导航条）；
     * 键盘从屏幕底部升起，比胶囊高时主页留白跟到键盘高度。
     */
    private void applyBottomInsets() {
        home.setBottomInset(Math.max(barInsetPx, imeBottomPx));
        tasks.setBottomInset(barInsetPx);
        settings.setBottomInset(barInsetPx);
    }

    @NonNull
    private TabPage page(int id) {
        return switch (id) {
            case TAB_TASKS -> tasks;
            case TAB_SETTINGS -> settings;
            default -> home;
        };
    }

    private void handleIntent(@Nullable Intent intent) {
        if (intent == null) {
            return;
        }
        int tab = intent.getIntExtra(EXTRA_TAB, 0);
        if (tab != 0) {
            intent.removeExtra(EXTRA_TAB);
            tabs.setSelectedId(tab);
        }
        String key = Intent.ACTION_SEND.equals(intent.getAction()) ? Intent.EXTRA_TEXT : EXTRA_URL;
        String url = intent.getStringExtra(key);
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        // 只处理一次，避免重建时重复解析。
        intent.removeExtra(key);
        tabs.setSelectedId(TAB_HOME);
        home.parseExternal(url.trim());
    }
}
