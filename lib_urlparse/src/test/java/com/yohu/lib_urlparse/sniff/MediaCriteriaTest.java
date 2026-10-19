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
        assertTrue(MediaExtensions.looksLikeMedia("https://a.test/v/x.mp4"));
    }

    @Test
    public void pseudoExtensionIsNotAnImage() {
        // 抖音封面地址常带 ~tplv-noop.image 之类的"伪扩展名"，它不是真图片扩展名
        assertFalse(MediaExtensions.looksLikeImage("https://p3.douyinpic.com/a~tplv-noop.image"));
        assertFalse(MediaExtensions.looksLikeMedia("https://p3.douyinpic.com/a~tplv-noop.image"));
        // 注意：looksLikeNonMedia 只认已知的非媒体扩展名（.ico/.css/.js 之类），
        // 伪扩展名不在其中——这里只要求它不被误判成图片/媒体即可。
        assertFalse(MediaExtensions.looksLikeNonMedia("https://p3.douyinpic.com/a~tplv-noop.image"));
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
        assertTrue(MediaExtensions.hasExtension("https://a.test/v/x.MP4"));
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

    // ------------------------------------------------------------------ UA 选择

    @Test
    public void userAgentPicksMobileForMobileHosts() {
        String uaMobile = PageUserAgents.forUrl("https://m.toutiao.com/i123/");
        String uaDesktop = PageUserAgents.forUrl("https://www.bilibili.com/video/BV1");
        assertTrue("移动域应给移动 UA", uaMobile != null && uaMobile.contains("Android"));
        assertTrue("桌面域应给桌面 UA", uaDesktop != null && !uaDesktop.equals(uaMobile));
    }

    @Test
    public void recognizesWeChatUa() {
        assertTrue(PageUserAgents.isWeChatUa(
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 MicroMessenger/8.0"));
        assertFalse(PageUserAgents.isWeChatUa("Mozilla/5.0 Chrome/139.0"));
        assertFalse(PageUserAgents.isWeChatUa(null));
    }

    // ------------------------------------------------------------------ 媒体 URL 判定

    @Test
    public void snifferCapturesPlainMp4() {
        MediaUrlSniffer sniffer = new MediaUrlSniffer();
        MediaUrlSniffer.Decision d = sniffer.filter("https://a.test/v/x.mp4", "video/mp4", 1024L);
        assertTrue("干净的 mp4 应被收录", d.captured());
        assertEquals("https://a.test/v/x.mp4", d.url());
    }

    @Test
    public void snifferRejectsBlacklistedUrls() {
        MediaUrlSniffer sniffer = new MediaUrlSniffer();
        String blacklisted = MediaUrlSniffer.BLACKLIST_URLS.get(0);
        MediaUrlSniffer.Decision d = sniffer.filter("https://a.test/" + blacklisted,
                "application/vnd.apple.mpegurl", 1024L);
        assertFalse("黑名单里的地址必须被过滤", d.captured());
    }

    @Test
    public void snifferRejectsIconAndTinyResources() {
        MediaUrlSniffer sniffer = new MediaUrlSniffer();
        // 图标不是媒体
        assertFalse(sniffer.filter("https://a.test/favicon.ico", "image/x-icon", 1024L).captured());
        // 长度阈值只用于"类型不明确"的响应（例如 application/octet-stream）：
        // 显式声明 video/mp4 的响应即使很小也照收，避免把真实短片误杀。
        assertTrue("显式 video/mp4 不应被长度阈值误杀",
                sniffer.filter("https://a.test/v/tiny.mp4", "video/mp4", 1024L).captured());
        assertFalse("类型不明确且过小的响应应被拒绝",
                sniffer.filter("https://cdn.test/blob/large", "application/octet-stream", 1024L)
                        .captured());
    }

    @Test
    public void snifferBlacklistsAreNotAccidentallyEmpty() {
        // 这两个表是"从真实页面里总结出来的"，空了就说明移植时丢了数据
        assertTrue("URL 黑名单不应为空", MediaUrlSniffer.BLACKLIST_URLS.size() >= 3);
        assertTrue("扩展名黑名单不应为空", MediaUrlSniffer.BLACKLIST_LABELS.size() >= 20);
    }
}
