package com.yohu.urldown.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 失败分类：状态码 / 引擎异常描述 → 用户看得懂、调度器知道怎么处理的类别。 */
public class DownloadErrorTest {

    @Test
    public void errorKinds() {
        assertEquals(DownloadError.LINK_EXPIRED, DownloadError.ofHttpStatus(403));
        assertEquals(DownloadError.SERVER, DownloadError.ofHttpStatus(503));
        assertEquals(DownloadError.HTTP, DownloadError.ofHttpStatus(416));
        assertTrue(DownloadError.NETWORK.waitsForNetwork());
        assertTrue(DownloadError.LINK_EXPIRED.refreshesLink());
        assertFalse(DownloadError.STORAGE.retryable());
        assertEquals(410, DownloadError.httpStatusOf(
                new RuntimeException("wrap", new java.io.IOException("HTTP error: 410 Gone"))));
        assertEquals(-1, DownloadError.httpStatusOf(new java.io.IOException("reset")));
    }
}
