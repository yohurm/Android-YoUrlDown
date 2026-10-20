package com.yohu.urldown.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.yohu.lib_urlparse.api.Author;
import com.yohu.lib_urlparse.api.MediaAsset;
import com.yohu.lib_urlparse.api.Platform;
import com.yohu.lib_urlparse.api.UrlParseResult;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/** 任务列表落盘格式：往返不丢字段；进行中的任务恢复成“中断”；坏文件不崩。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class TaskJsonTest {

    private static DownloadTask task(long id, DownloadTask.Status status) {
        UrlParseResult r = UrlParseResult.builder(Platform.BILIBILI)
                .title("城市夜景")
                .sourceUrl("https://b23.tv/abc")
                .author(Author.builder().nickname("夜行者").build())
                .asset(MediaAsset.video("https://cdn/v.m4s").build())
                .asset(MediaAsset.audio("https://cdn/a.m4s").build())
                .asset(MediaAsset.cover("https://cdn/c.jpg").build())
                .build();
        DownloadTask t = new DownloadTask(id, r, 1_700_000_000_000L);
        t.status = status;
        return t;
    }

    @Test
    public void roundTripKeepsSummary() {
        DownloadTask done = task(7, DownloadTask.Status.SUCCESS);
        done.finishedAt = 1_700_000_100_000L;
        done.savedBytes = 12345;
        done.report = "line1\nline2";
        done.files = Arrays.asList(new File("/x/a.mp4"));

        List<DownloadTask> back = TaskJson.decode(TaskJson.encode(Arrays.asList(done)));
        assertEquals(1, back.size());
        DownloadTask t = back.get(0);
        assertEquals(7, t.id);
        assertEquals("城市夜景", t.title);
        assertEquals("哔哩哔哩", t.platform);
        assertEquals("夜行者", t.author);
        assertEquals("https://b23.tv/abc", t.sourceUrl);
        assertEquals("https://cdn/c.jpg", t.coverUrl);
        assertEquals(1, t.videoCount);
        assertEquals(1, t.audioCount);
        assertEquals(DownloadTask.Status.SUCCESS, t.status());
        assertEquals(1_700_000_100_000L, t.finishedAt());
        assertEquals(12345, t.savedBytes());
        assertEquals("line1\nline2", t.report());
        assertEquals("/x/a.mp4", t.files().get(0).getPath());
        // 媒体直链不落盘：恢复的任务没有解析结果，重试要走重新解析
        assertNull(t.source);
    }

    @Test
    public void activeTasksComeBackAsInterruptedPause() {
        DownloadTask running = task(1, DownloadTask.Status.RUNNING);
        running.fileIndex = 1;
        running.fileCount = 1;
        running.fileDone = 300;
        running.fileTotal = 1000;
        List<DownloadTask> back = TaskJson.decode(TaskJson.encode(Arrays.asList(
                running, task(2, DownloadTask.Status.QUEUED), task(3, DownloadTask.Status.WAITING_NETWORK))));
        assertEquals(3, back.size());
        for (DownloadTask t : back) {
            assertEquals(DownloadTask.Status.PAUSED, t.status());
            assertTrue("启动后要自动继续", t.interrupted);
            assertNull(t.error());
            assertTrue("有原链接就能继续", t.canResume());
        }
        assertEquals("断点进度要保留，卡片上才能显示停在哪", 0.3f, back.get(0).progress(), 0.001f);
    }

    @Test
    public void errorAndPlatformIdRoundTrip() {
        DownloadTask t = task(4, DownloadTask.Status.FAILED);
        t.error = com.yohu.urldown.download.DownloadError.LINK_EXPIRED;
        t.failure = "链接已过期";
        DownloadTask back = TaskJson.decode(TaskJson.encode(Arrays.asList(t))).get(0);
        assertEquals(com.yohu.urldown.download.DownloadError.LINK_EXPIRED, back.error());
        assertEquals("bilibili", back.platformId);
        assertEquals(DownloadTask.Status.FAILED, back.status());
    }

    @Test
    public void corruptFileYieldsEmptyList() {
        assertTrue(TaskJson.decode("{not json").isEmpty());
        assertTrue(TaskJson.decode("{}").isEmpty());
    }
}
