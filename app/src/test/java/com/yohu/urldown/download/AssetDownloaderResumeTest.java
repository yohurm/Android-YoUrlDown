package com.yohu.urldown.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.yohu.lib_urlparse.api.MediaAsset;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.nio.file.Files;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

/**
 * 暂停后继续必须接着断点下，而不是从 0 开始（引擎入队时沿用未结束的分块记录）。
 * 本地 MockWebServer 支持 Range，限速让暂停能落在中途；统计暂停后服务器实际发出的字节数。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AssetDownloaderResumeTest {

    private static final int SIZE = 1024 * 1024;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final byte[] body = new byte[SIZE];
    private final AtomicLong servedAfterPause = new AtomicLong();
    private volatile boolean paused;
    private MockWebServer server;

    @Before
    public void setUp() throws Exception {
        new Random(7).nextBytes(body);
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String range = request.getHeader("Range");
                int start = 0;
                int end = SIZE - 1;
                MockResponse r = new MockResponse().addHeader("Accept-Ranges", "bytes")
                        .addHeader("Content-Type", "video/mp4")
                        .addHeader("ETag", "\"v1\"");
                if (range != null && range.startsWith("bytes=")) {
                    String[] p = range.substring(6).split("-", -1);
                    start = Integer.parseInt(p[0]);
                    if (p.length > 1 && !p[1].isEmpty()) {
                        end = Math.min(SIZE - 1, Integer.parseInt(p[1]));
                    }
                    r.setResponseCode(206).addHeader("Content-Range",
                            "bytes " + start + "-" + end + "/" + SIZE);
                }
                if ("HEAD".equals(request.getMethod())) {
                    return r.addHeader("Content-Length", end - start + 1);
                }
                if (paused) {
                    servedAfterPause.addAndGet(end - start + 1);
                }
                return r.setBody(new Buffer().write(body, start, end - start + 1))
                        .throttleBody(32 * 1024, 50, TimeUnit.MILLISECONDS);
            }
        });
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void resumesFromBreakpointAfterPause() throws Exception {
        File dir = tmp.newFolder("out");
        MediaAsset asset = MediaAsset.video(server.url("/v.mp4?sig=1").toString()).mimeType("video/mp4").build();
        Recorder rec = new Recorder();
        String id = "test-resume-1";
        assertTrue(AssetDownloader.start(id, asset, dir, "v.mp4", rec));
        waitUntil(() -> rec.done.get() >= SIZE / 4 || rec.ended(), 30_000);
        assertNull("不应该在暂停前就结束: " + rec.error.get(), rec.error.get());
        AssetDownloader.pause(id);
        waitUntil(() -> rec.paused, 10_000);
        long doneAtPause = rec.done.get();
        assertTrue("暂停时应已下了一部分: " + doneAtPause, doneAtPause > 0 && doneAtPause < SIZE);
        paused = true;

        // 重新解析后签名变了，路径不变：沿用断点
        MediaAsset again = MediaAsset.video(server.url("/v.mp4?sig=2").toString()).mimeType("video/mp4").build();
        Recorder rec2 = new Recorder();
        assertTrue(AssetDownloader.start(id, again, dir, "v.mp4", rec2));
        waitUntil(rec2::ended, 60_000);
        assertNull("续传失败: " + rec2.error.get(), rec2.error.get());
        File out = rec2.file.get();
        assertArrayEquals("续传后文件必须完整一致", body, Files.readAllBytes(out.toPath()));
        long resent = servedAfterPause.get();
        assertTrue("继续后只该下剩下的部分：暂停时 " + doneAtPause + "，之后又发了 " + resent,
                resent <= SIZE - doneAtPause + 64 * 1024);
        AssetDownloader.forget(id, true);
    }

    @Test
    public void samePathIgnoresHostAndQuery() {
        assertTrue(AssetDownloader.samePath("https://a.cdn/v/1.mp4?x=1", "https://b.cdn/v/1.mp4?x=2#t"));
        assertFalse(AssetDownloader.samePath("https://a.cdn/v/1.mp4", "https://a.cdn/v/2.mp4"));
        assertFalse(AssetDownloader.samePath(null, "https://a.cdn/v/1.mp4"));
        assertEquals(true, AssetDownloader.samePath("https://a.cdn", "https://b.cdn/"));
    }

    private static void waitUntil(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("等待超时");
            }
            ShadowLooper.idleMainLooper();
            Thread.sleep(20);
        }
    }

    private static final class Recorder implements AssetDownloader.Callback {
        final AtomicLong done = new AtomicLong();
        final AtomicReference<File> file = new AtomicReference<>();
        final AtomicReference<String> error = new AtomicReference<>();
        volatile boolean paused;

        boolean ended() {
            return file.get() != null || error.get() != null;
        }

        @Override
        public void onProgress(long downloadedBytes, long totalBytes, long speed, long eta) {
            done.set(downloadedBytes);
        }

        @Override
        public void onSuccess(@NonNull File f) {
            file.set(f);
        }

        @Override
        public void onPaused() {
            paused = true;
        }

        @Override
        public void onCanceled() {
            error.set("canceled");
        }

        @Override
        public void onError(@NonNull DownloadError e, @Nullable String detail) {
            error.set(e + ": " + detail);
        }
    }
}
