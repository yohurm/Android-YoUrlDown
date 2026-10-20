package com.yohu.urldown;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import com.yohu.lib_urlparse.api.Author;
import com.yohu.lib_urlparse.api.FailureReason;
import com.yohu.lib_urlparse.api.MediaAsset;
import com.yohu.lib_urlparse.api.ParseFailure;
import com.yohu.lib_urlparse.api.ParseTrace;
import com.yohu.lib_urlparse.api.Platform;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.urldown.task.DownloadTask;
import com.yohu.urldown.task.TaskStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 把三个页签渲染成 PNG，输出到 app/build/screenshots/，用于没有真机时检查布局。
 *
 * <p>运行：{@code ./gradlew :app:testDebugUnitTest --tests '*UiScreenshotTest*'}。
 * 只做渲染、不做像素比对；文字由本机字体渲染，和真机字形可能有出入。
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w393dp-h852dp-xxhdpi")
public class UiScreenshotTest {

    private static final File OUT = new File("build/screenshots");

    @Test
    public void lightTheme() throws IOException {
        captureAll("light");
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-night-xxhdpi")
    public void darkTheme() throws IOException {
        captureAll("dark");
    }

    private void captureAll(String theme) throws IOException {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        // 悬浮底栏的高度在首次布局时才回调，页面据此留白后要再走一帧布局
        idleFor(100);
        save(activity, theme + "-1-home-idle");

        activity.homeForTest().showResultForTest(sampleResult());
        idle();
        save(activity, theme + "-2-home-result");
        assertMatrixDesc(activity, "平台: 抖音\n通道: ");

        activity.homeForTest().showFailureForTest(new ParseFailure(FailureReason.NETWORK,
                "douyin.webpage", "connect timed out"));
        idle();
        save(activity, theme + "-3-home-failure");
        assertMatrixDesc(activity, "解析失败\n原因: NETWORK\n通道: douyin.webpage");

        activity.selectTabForTest(MainActivity.TAB_TASKS);
        idle();
        save(activity, theme + "-4-tasks-empty");

        TaskStore store = TaskStore.get();
        long day = 24L * 3600 * 1000;
        DownloadTask old = store.addForTest(sample(Platform.BILIBILI, "【4K】城市夜景延时摄影合集：从黄昏到凌晨"),
                DownloadTask.Status.SUCCESS, day);
        store.addForTest(sample(Platform.KUAISHOU, "家常红烧肉的做法，肥而不腻入口即化"),
                DownloadTask.Status.CANCELED, day);
        DownloadTask failed = store.addForTest(sample(Platform.XIAOHONGSHU, "秋天的第一套穿搭分享"),
                DownloadTask.Status.FAILED);
        DownloadTask done = store.addForTest(sample(Platform.DOUYIN, "周末去山里露营，记录一下清晨的雾和第一杯咖啡"),
                DownloadTask.Status.SUCCESS);
        store.setFilesForTest(done, sampleFiles(activity));
        DownloadTask running = store.addForTest(sample(Platform.WEIBO, "猫咪第一次见到雪的反应"),
                DownloadTask.Status.RUNNING);
        store.addForTest(sample(Platform.YOUTUBE, "How to brew pour-over coffee at home"),
                DownloadTask.Status.QUEUED);
        store.addForTest(sample(Platform.TOUTIAO, "三分钟看懂今年的新能源车补贴政策"),
                DownloadTask.Status.PAUSED);
        store.addForTest(sample(Platform.WECHAT_CHANNELS, "老街坊的早餐铺，开了三十年"),
                DownloadTask.Status.WAITING_NETWORK);
        idle();
        save(activity, theme + "-5-tasks-list");

        activity.tasksForTest().selectFilterForTest(1);
        idle();
        save(activity, theme + "-5b-tasks-active");
        activity.tasksForTest().selectFilterForTest(0);
        idle();

        activity.tasksForTest().openDetailForTest(running);
        idleFor(800);
        saveAllWindows(activity, theme + "-7-detail-running");
        activity.tasksForTest().dismissDetailForTest();
        idleFor(800);

        activity.tasksForTest().openDetailForTest(failed);
        idleFor(800);
        saveAllWindows(activity, theme + "-8-detail-failed");
        activity.tasksForTest().dismissDetailForTest();
        idleFor(800);

        activity.tasksForTest().openDetailForTest(done);
        idleFor(800);
        saveAllWindows(activity, theme + "-9-detail-done");
        activity.tasksForTest().dismissDetailForTest();
        idleFor(800);
        if (old == null) {
            throw new AssertionError();
        }
        store.resetForTest();

        activity.selectTabForTest(MainActivity.TAB_SETTINGS);
        idle();
        save(activity, theme + "-6-settings");
        controller.pause().stop().destroy();
    }

    private static UrlParseResult sample(Platform platform, String title) {
        return UrlParseResult.builder(platform)
                .channel(platform.name().toLowerCase() + ".webpage")
                .title(title)
                .sourceUrl("https://example.com/share/" + platform.name().toLowerCase())
                .author(Author.builder().nickname("山野日记").build())
                .asset(MediaAsset.video("https://cdn.example.com/v.mp4").dimension(1080, 1920).build())
                .asset(MediaAsset.audio("https://cdn.example.com/a.m4a").build())
                .build();
    }

    private static UrlParseResult sampleResult() {
        return UrlParseResult.builder(Platform.DOUYIN)
                .channel("douyin.webpage")
                .workId("7412093381245873451")
                .title("周末去山里露营，记录一下清晨的雾和第一杯咖啡")
                .author(Author.builder().nickname("山野日记").build())
                .asset(MediaAsset.video("https://v3-web.douyinvod.com/video/tos/cn/7412093381245873451.mp4")
                        .dimension(1080, 1920).durationMs(47_000).alternate("https://backup/1.mp4").build())
                .asset(MediaAsset.audio("https://sf3-cdn-tos.douyinstatic.com/obj/music/7412.mp3")
                        .durationMs(47_000).build())
                .trace(ParseTrace.builder()
                        .attempt("douyin.webpage", ParseTrace.Verdict.OK, null, 820, "ok")
                        .build())
                .build();
    }

    private static void idle() {
        for (int i = 0; i < 5; i++) {
            ShadowLooper.idleMainLooper();
        }
    }

    /** tools/device-matrix.ps1 依赖结果卡 contentDescription 的格式。 */
    private static void assertMatrixDesc(MainActivity activity, String prefix) {
        View root = activity.getWindow().getDecorView();
        if (!hasDescPrefix(root, prefix)) {
            throw new AssertionError("没有 contentDescription 以 \"" + prefix + "\" 开头的视图");
        }
    }

    private static boolean hasDescPrefix(View v, String prefix) {
        CharSequence d = v.getContentDescription();
        if (d != null && d.toString().startsWith(prefix)) {
            return true;
        }
        if (v instanceof android.view.ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasDescPrefix(g.getChildAt(i), prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 底部面板 / 菜单在独立窗口里，decorView 画不到；把所有窗口按屏幕位置叠起来。 */
    @SuppressWarnings("unchecked")
    private static List<View> windowRoots() {
        try {
            Class<?> g = Class.forName("android.view.WindowManagerGlobal");
            Object global = g.getMethod("getInstance").invoke(null);
            java.lang.reflect.Field f = g.getDeclaredField("mViews");
            f.setAccessible(true);
            return new ArrayList<>((List<View>) f.get(global));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void saveAllWindows(MainActivity activity, String name) throws IOException {
        View root = activity.getWindow().getDecorView();
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        for (View v : windowRoots()) {
            if (v.getWidth() == 0 || v.getVisibility() != View.VISIBLE) {
                continue;
            }
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            canvas.save();
            canvas.translate(loc[0], loc[1]);
            v.draw(canvas);
            canvas.restore();
        }
        OUT.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(OUT, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    private static void idleFor(long ms) {
        for (long t = 0; t < ms; t += 50) {
            ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    /** 造两份真实文件，让详情面板能列出文件、行尾出现“打开”。 */
    private static List<File> sampleFiles(MainActivity activity) throws IOException {
        File dir = new File(activity.getExternalFilesDir(null), "downloads");
        dir.mkdirs();
        List<File> out = new ArrayList<>();
        String[] names = {"周末去山里露营，记录一下清晨的雾和第一杯咖啡.mp4", "周末去山里露营_封面.jpg"};
        int[] sizes = {7_340_032, 286_720};
        for (int i = 0; i < names.length; i++) {
            File f = new File(dir, names[i]);
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(new byte[sizes[i]]);
            }
            out.add(f);
        }
        return out;
    }

    private static void save(MainActivity activity, String name) throws IOException {
        View root = activity.getWindow().getDecorView();
        int w = root.getWidth();
        int h = root.getHeight();
        if (w == 0 || h == 0) {
            throw new IllegalStateException("root not laid out");
        }
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        OUT.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(OUT, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
