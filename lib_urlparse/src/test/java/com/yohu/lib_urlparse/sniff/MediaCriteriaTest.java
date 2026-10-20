package com.yohu.lib_urlparse.sniff;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 媒体判据测试。
 *
 * <p>这些判据是**经验型代码**：每一条都对应一次真实误判或漏判（例如把
 * {@code ~tplv-noop.image} 这种"看起来是图片、其实是占位符"的地址当成封面）。
 * 因此它们必须被钉住，否则移植或重构时会悄悄退化。
 */
public class MediaCriteriaTest {

    // ------------------------------------------------------------------ 扩展名判据

    @Test
    public void recognizesVideoAudioImageByExtension() {
        assertTrue(MediaExtensions.looksLikeVideo("https://a.test/v/x.mp4"));
        assertTrue(MediaExtensions.looksLikeVideo("https://a.test/v/x.m3u8"));
        assertTrue(MediaExtensions.looksLikeAudio("https://a.test/a/x.m4a"));
        assertTrue(MediaExtensions.looksLikeImage("https://a.test/i/x.webp"));
        assertTrue(MediaExtensions.looksLikeImage("https://a.test/i/x.jpeg"));
    }

    @Test
    public void pseudoExtensionIsNotAnImage() {
        // 抖音封面地址常带 ~tplv-noop.image 之类的"伪扩展名"，它不是真图片扩展名
        assertFalse(MediaExtensions.looksLikeImage("https://p3.douyinpic.com/a~tplv-noop.image"));
        assertFalse(MediaExtensions.looksLikeVideo("https://p3.douyinpic.com/a~tplv-noop.image"));
    }

    @Test
    public void playlistDetection() {
        assertTrue(MediaExtensions.looksLikePlaylist("https://a.test/x.m3u8?a=1"));
        assertFalse(MediaExtensions.looksLikePlaylist("https://a.test/x.mp4"));
    }

    @Test
    public void extensionIgnoresQueryAndFragment() {
        // pathOf 返回的是"去掉查询串与片段后的 URL"，不是扩展名
        assertEquals("https://a.test/v/x.mp4",
                MediaExtensions.pathOf("https://a.test/v/x.mp4?token=abc#t"));
        assertTrue(MediaExtensions.looksLikeVideo("https://a.test/v/x.MP4"));
    }

    // ------------------------------------------------------------------ 拦截页判据

    @Test
    public void detectsRealBlockPageTitles() {
        // 真机实测拿到的就是这个标题
        assertTrue(BlockPageDetector.isBlockTitle("網頁無法使用"));
        assertTrue(BlockPageDetector.isBlockTitle("网页无法使用"));
        assertTrue(BlockPageDetector.isBlockTitle("请完成验证"));
        assertTrue(BlockPageDetector.isBlockTitle("Access Denied"));
    }

    @Test
    public void doesNotKillNormalTitles() {
        assertFalse(BlockPageDetector.isBlockTitle(null));
        assertFalse(BlockPageDetector.isBlockTitle("趣头条 - 让阅读更具价值"));
        assertFalse(BlockPageDetector.isBlockTitle("能力觉醒！颠覆四皇的极恶时代！"));
    }

    @Test
    public void blockPageNeedsNoVideoSignal() {
        // 拦截标题 + 只有图片 → 判为拦截页（这正是那次假阳性的形态）
        assertTrue(BlockPageDetector.isBlockPage("網頁無法使用", false));
        // 拦截标题 + 有视频 → 不拦（宁可漏判，不可误杀）
        assertFalse(BlockPageDetector.isBlockPage("網頁無法使用", true));
        assertFalse(BlockPageDetector.isBlockPage("正常作品", false));
    }
}
