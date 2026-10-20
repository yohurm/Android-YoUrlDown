package com.yohu.urldown.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.yohu.lib_urlparse.api.MediaAsset;
import com.yohu.lib_urlparse.api.Platform;
import com.yohu.lib_urlparse.api.UrlParseResult;

import org.junit.Test;

import java.util.EnumSet;

/** 任务状态机：合法迁移表、分组判断、暂停时保留进度、剩余时间。 */
public class DownloadTaskStateTest {

    private static DownloadTask task() {
        UrlParseResult r = UrlParseResult.builder(Platform.DOUYIN)
                .title("t")
                .sourceUrl("https://v.douyin.com/x/")
                .asset(MediaAsset.video("https://cdn/v.mp4").build())
                .build();
        return new DownloadTask(1, r, 0L);
    }

    @Test
    public void transitionsFollowTable() {
        DownloadTask.Status Q = DownloadTask.Status.QUEUED;
        DownloadTask.Status R = DownloadTask.Status.RUNNING;
        DownloadTask.Status P = DownloadTask.Status.PAUSED;
        DownloadTask.Status W = DownloadTask.Status.WAITING_NETWORK;
        DownloadTask.Status S = DownloadTask.Status.SUCCESS;
        DownloadTask.Status F = DownloadTask.Status.FAILED;
        DownloadTask.Status C = DownloadTask.Status.CANCELED;
        assertTrue(Q.canMoveTo(R));
        assertTrue(R.canMoveTo(P));
        assertTrue(R.canMoveTo(W));
        assertTrue(P.canMoveTo(Q));
        assertTrue(W.canMoveTo(Q));
        assertTrue(F.canMoveTo(Q));
        assertTrue(C.canMoveTo(Q));
        assertFalse("暂停不能直接跳到下载中，要经过排队", P.canMoveTo(R));
        assertFalse("完成是终点", S.canMoveTo(Q));
        assertFalse(Q.canMoveTo(S));
        for (DownloadTask.Status s : DownloadTask.Status.values()) {
            assertFalse("不能原地迁移: " + s, s.canMoveTo(s));
        }
    }

    @Test
    public void groupsAreConsistent() {
        assertEquals(EnumSet.of(DownloadTask.Status.SUCCESS, DownloadTask.Status.FAILED,
                DownloadTask.Status.CANCELED), finished());
        assertTrue(DownloadTask.Status.PAUSED.isUnfinished());
        assertFalse("暂停不占下载名额", DownloadTask.Status.PAUSED.isActive());
        assertTrue(DownloadTask.Status.WAITING_NETWORK.isActive());
    }

    private static EnumSet<DownloadTask.Status> finished() {
        EnumSet<DownloadTask.Status> set = EnumSet.noneOf(DownloadTask.Status.class);
        for (DownloadTask.Status s : DownloadTask.Status.values()) {
            if (s.isFinished()) {
                set.add(s);
            }
        }
        return set;
    }

    @Test
    public void moveToRejectsIllegal() {
        DownloadTask t = task();
        assertTrue(t.moveTo(DownloadTask.Status.RUNNING));
        assertTrue(t.moveTo(DownloadTask.Status.SUCCESS));
        assertFalse(t.moveTo(DownloadTask.Status.QUEUED));
        assertEquals(DownloadTask.Status.SUCCESS, t.status());
    }

    @Test
    public void pausedKeepsProgressAndCanResume() {
        DownloadTask t = task();
        t.moveTo(DownloadTask.Status.RUNNING);
        t.stage = DownloadTask.Stage.DOWNLOADING;
        t.fileIndex = 1;
        t.fileCount = 2;
        t.fileDone = 50;
        t.fileTotal = 100;
        assertEquals(0.25f, t.progress(), 0.001f);
        t.moveTo(DownloadTask.Status.PAUSED);
        assertEquals("暂停后进度条停在原处", 0.25f, t.progress(), 0.001f);
        assertTrue(t.canResume());
        assertFalse(t.canPause());
        assertFalse(t.canRetry());
    }

    @Test
    public void etaOnlyWhileDownloading() {
        DownloadTask t = task();
        t.moveTo(DownloadTask.Status.RUNNING);
        t.fileDone = 1_000;
        t.fileTotal = 11_000;
        t.speedBps = 1_000;
        assertEquals(10_000, t.etaMs());
        t.speedBps = 0;
        assertEquals(-1, t.etaMs());
    }
}
