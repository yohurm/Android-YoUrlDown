package com.yohu.urldown;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.yohu.lib_urlparse.api.Author;
import com.yohu.lib_urlparse.api.MediaAsset;
import com.yohu.lib_urlparse.api.Platform;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.urldown.task.DownloadTask;
import com.yohu.urldown.task.TaskStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowBuild;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

/**
 * 页签切换回归：任务页 / 设置页带 YoSegmentButton（控件玻璃），悬浮底栏也是玻璃。
 *
 * <p>旧版 YoUI 里控件玻璃“采样背后”时把盖在上面的底栏也画进去，而底栏又采样含该控件的内容层，
 * 两个 RenderNode 互相引用，切到这两页的第一帧渲染线程递归到崩溃（SIGSEGV，整个进程退出）。
 * YoUI 在 Robolectric 指纹下关掉玻璃硬件管线，所以这里换成真机指纹，并用 PixelCopy 让每帧真的走硬件渲染。
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w393dp-h852dp-xxhdpi")
public class TabSwitchTest {

    private static final int[] TABS = {MainActivity.TAB_HOME, MainActivity.TAB_TASKS,
            MainActivity.TAB_SETTINGS};

    @Before
    public void poseAsDevice() {
        ShadowBuild.setFingerprint("google/oriole/oriole:15/AP3A/1:user/release-keys");
    }

    @Test
    public void programmaticSwitchRendersEveryPage() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        idle(100);
        for (int i = 0; i < 9; i++) {
            int tab = TABS[i % 3];
            a.selectTabForTest(tab);
            frames(a, 3);
            assertShown(a, tab);
        }
        c.pause().stop().destroy();
    }

    @Test
    public void tappingTheBarWithTasksAndScrolling() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        idle(100);
        TaskStore store = TaskStore.get();
        DownloadTask.Status[] statuses = DownloadTask.Status.values();
        for (int i = 0; i < 12; i++) {
            store.addForTest(sample(Platform.values()[i % 5], "任务 " + i), statuses[i % statuses.length]);
        }
        a.homeForTest().showResultForTest(sample(Platform.DOUYIN, "结果"));
        idle(100);
        View decor = a.getWindow().getDecorView();
        List<View> items = new ArrayList<>();
        for (int id : new int[]{R.string.tab_home, R.string.tab_tasks, R.string.tab_settings}) {
            collectBarLabel(decor, a.getString(id), items);
        }
        assertTrue("底栏三个页签都要找到", items.size() == 3);
        long t = SystemClock.uptimeMillis();
        for (int i = 0; i < 12; i++) {
            View item = items.get(i % 3);
            int[] loc = new int[2];
            item.getLocationInWindow(loc);
            tap(decor, loc[0] + item.getWidth() / 2f, loc[1] + item.getHeight() / 2f, t);
            t += 100;
            frames(a, 3);
            assertShown(a, TABS[i % 3]);
            if (i % 2 == 1) {
                drag(decor, 1500, 400, t);
                t += 200;
                frames(a, 1);
                drag(decor, 400, 1500, t);
                t += 200;
                frames(a, 1);
            }
        }
        store.resetForTest();
        c.pause().stop().destroy();
    }

    @Test
    public void emptyStateActionJumpsHome() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        idle(100);
        View decor = a.getWindow().getDecorView();
        long t = SystemClock.uptimeMillis();
        for (int r = 0; r < 3; r++) {
            a.selectTabForTest(MainActivity.TAB_TASKS);
            frames(a, 2);
            List<View> found = new ArrayList<>();
            collectShown(decor, a.getString(R.string.tasks_empty_action), found);
            assertTrue(!found.isEmpty());
            View v = found.get(0);
            int[] loc = new int[2];
            v.getLocationInWindow(loc);
            tap(decor, loc[0] + v.getWidth() / 2f, loc[1] + v.getHeight() / 2f, t);
            t += 200;
            frames(a, 2);
            assertShown(a, MainActivity.TAB_HOME);
        }
        c.pause().stop().destroy();
    }

    @Test
    public void recreateKeepsSelectedTab() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        idle(100);
        c.get().selectTabForTest(MainActivity.TAB_SETTINGS);
        frames(c.get(), 2);
        c.recreate();
        idle(100);
        frames(c.get(), 2);
        assertShown(c.get(), MainActivity.TAB_SETTINGS);
        c.get().selectTabForTest(MainActivity.TAB_TASKS);
        frames(c.get(), 2);
        c.pause().stop().destroy();
    }

    private static void assertShown(MainActivity a, int tab) {
        assertTrue("主页可见性不对", a.homeForTest().getView().isShown() == (tab == MainActivity.TAB_HOME));
        assertTrue("任务页可见性不对", a.tasksForTest().getView().isShown() == (tab == MainActivity.TAB_TASKS));
    }

    private static UrlParseResult sample(Platform platform, String title) {
        return UrlParseResult.builder(platform)
                .channel(platform.name().toLowerCase() + ".webpage")
                .title(title)
                .sourceUrl("https://example.com/share/" + title.hashCode())
                .author(Author.builder().nickname("山野日记").build())
                .asset(MediaAsset.video("https://cdn.example.com/v.mp4").dimension(1080, 1920).build())
                .build();
    }

    /** 让窗口真的出帧：PixelCopy 会驱动 Robolectric 的硬件渲染器。 */
    private static void frames(MainActivity a, int count) {
        View decor = a.getWindow().getDecorView();
        for (int i = 0; i < count; i++) {
            idle(16);
            Bitmap bmp = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
            PixelCopy.request(a.getWindow(), bmp, r -> { }, new Handler(Looper.getMainLooper()));
            idle(0);
        }
    }

    private static void tap(View decor, float x, float y, long t) {
        decor.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0));
        decor.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0));
    }

    private static void drag(View decor, float y0, float y1, long t) {
        float x = 500;
        decor.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y0, 0));
        for (int i = 1; i <= 5; i++) {
            decor.dispatchTouchEvent(MotionEvent.obtain(t, t + i * 10, MotionEvent.ACTION_MOVE,
                    x, y0 + (y1 - y0) * i / 5f, 0));
            idle(10);
        }
        decor.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y1, 0));
    }

    /** 底栏页签文字（在屏幕下部；页面标题里也有同名文字）。 */
    private static void collectBarLabel(View v, String text, List<View> out) {
        if (v instanceof TextView tv && text.contentEquals(tv.getText()) && v.isShown()) {
            int[] loc = new int[2];
            v.getLocationInWindow(loc);
            if (loc[1] > v.getRootView().getHeight() * 3 / 4) {
                out.add(v);
            }
        }
        if (v instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                collectBarLabel(g.getChildAt(i), text, out);
            }
        }
    }

    private static void collectShown(View v, String text, List<View> out) {
        if (v instanceof TextView tv && text.contentEquals(tv.getText()) && v.isShown()) {
            out.add(v);
        }
        if (v instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                collectShown(g.getChildAt(i), text, out);
            }
        }
    }

    private static void idle(long ms) {
        ShadowLooper.idleMainLooper();
        if (ms > 0) {
            ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS);
        }
        ShadowLooper.idleMainLooper();
    }
}
