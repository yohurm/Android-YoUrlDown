package com.yohu.lib_urlparse.rules;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 跨平台规则的冒烟测试（纯函数，无网络、无固件）。
 *
 * <p>为什么需要它：11 组规则里，只有头条那组能配到不依赖 provider 的真实固件测试；
 * 其余各组的旧测试都绑着 provider（B4/B5 才落地）。在那之前，这里先用**纯函数断言**
 * 把各平台"识别输入 / 抽 ID / 拒绝非本平台输入"的契约钉住——这些是最容易被移植改错的地方。
 *
 * <p>每条断言都来自真实链接形态（来源：旧报告 11 与真机抓取记录）。
 */
public class RulesSmokeTest {

    // ------------------------------------------------------------------ 抖音

    @Test
    public void douyinExtractsIdFromRealShareForms() {
        // 直链形态：/video/、/note/、share 页
        assertEquals("7014363562642623777",
                AwemeIdExtractor.extractAwemeId("https://www.douyin.com/video/7014363562642623777"));
        assertEquals("7014363562642623777", AwemeIdExtractor.extractAwemeId(
                "https://www.douyin.com/note/7014363562642623777"));
        // 查询串形态
        assertEquals("7234567890123456789",
                AwemeIdExtractor.extractAwemeId("https://example.test/x?aweme_id=7234567890123456789"));
        // 短链里**没有**数字 ID：必须先展开短链（这是 provider 的职责，不是 extractor 的）。
        // 这条断言把该边界钉住，避免有人误以为 extractor 能处理短链。
        assertNull("短链不应被 extractor 直接解析出 ID",
                AwemeIdExtractor.extractAwemeId("https://v.douyin.com/8hedewA/"));
    }

    @Test
    public void douyinHostAndShortLinkMarkers() {
        assertTrue(DouyinRules.isDouyinHost("v.douyin.com"));
        assertTrue(DouyinRules.isDouyinHost("www.douyin.com"));
        assertFalse(DouyinRules.isDouyinHost("notdouyin.com"));
        assertTrue(DouyinRules.containsShortLinkMarker("x/v.douyin.com/y"));
        assertTrue(DouyinRules.containsShortLinkMarker("x/iesdouyin.com/y"));
        assertFalse(DouyinRules.containsShortLinkMarker("x/example.com/y"));
    }

    // ------------------------------------------------------------------ B站

    @Test
    public void bilibiliExtractsBvidFromRealUrls() {
        assertEquals("BV1HU4y1K7TT", BilibiliRules.extractBvid(
                "https://www.bilibili.com/video/BV1HU4y1K7TT"));
        assertEquals("BV1HU4y1K7TT", BilibiliRules.extractBvid("BV1HU4y1K7TT"));
        // 带查询串也要能抽出来
        assertEquals("BV1HU4y1K7TT", BilibiliRules.extractBvid(
                "https://www.bilibili.com/video/BV1HU4y1K7TT/?spm_id_from=xxx&vd_source=yyy"));
        assertNull(BilibiliRules.extractBvid("https://www.bilibili.com/video/av12345"));
    }

    @Test
    public void bilibiliHostsCoverShortDomain() {
        assertTrue(BilibiliRules.isBilibiliHost("www.bilibili.com"));
        assertTrue(BilibiliRules.isBilibiliHost("b23.tv"));
        assertFalse(BilibiliRules.isBilibiliHost("example.com"));
    }

    // ------------------------------------------------------------------ 微博

    @Test
    public void weiboExtractsStatusIdFromAllRealForms() {
        // 移动端
        assertEquals("FBqgOmDxO", WeiboRules.extractStatusId(
                "https://m.weibo.cn/status/FBqgOmDxO"));
        // 桌面端（带用户号）
        assertEquals("FBqgOmDxO", WeiboRules.extractStatusId(
                "https://weibo.com/1234567890/FBqgOmDxO"));
        assertNull(WeiboRules.extractStatusId("https://weibo.com/"));
    }

    // ------------------------------------------------------------------ 小红书

    @Test
    public void xiaohongshuExtractsNoteId() {
        String noteId = "6a8d6df50000000021026ecc";
        assertEquals(noteId, XiaohongshuRules.extractNoteId(
                "https://www.xiaohongshu.com/discovery/item/" + noteId));
        assertEquals(noteId, XiaohongshuRules.extractNoteId(
                "https://www.xiaohongshu.com/explore/" + noteId + "?xsec_token=ABCDEFGH"));
        assertTrue(XiaohongshuRules.isXhsHost("www.xiaohongshu.com"));
        assertTrue(XiaohongshuRules.isXhsHost("xhslink.com"));
        assertFalse(XiaohongshuRules.isXhsHost("example.com"));
    }

    @Test
    public void xiaohongshuParsesInitialStateObject() {
        // 笔记数据在 window.__INITIAL_STATE__ 里；抽取必须能处理"赋值后紧跟 ';'"的形态
        String html = "<html><script>window.__INITIAL_STATE__={\"note\":{\"noteDetailMap\":{}}};</script></html>";
        String js = XiaohongshuRules.extractObjectText(html, "__INITIAL_STATE__");
        assertNotNull("应当抽出 __INITIAL_STATE__ 的对象文本", js);
        assertTrue("抽出的应是 JSON 对象", js.trim().startsWith("{"));
        assertNull("不存在的变量应返回 null",
                XiaohongshuRules.extractObjectText(html, "__NEXT_DATA__"));
    }

    // ------------------------------------------------------------------ 微信公众号

    @Test
    public void wechatHostDetection() {
        assertTrue(WeChatRules.isWeChatHost("mp.weixin.qq.com"));
        assertTrue(WeChatRules.isWeChatHost("weixin.qq.com"));
        assertFalse(WeChatRules.isWeChatHost("example.com"));
    }

    // ------------------------------------------------------------------ Instagram

    @Test
    public void instagramExtractsShortcodeFromPostReelAndTv() {
        // 三种真实作品地址形态（旧测试里也是这三种）
        assertEquals("Ca3V3zGIZ08", InstagramRules.extractShortcode(
                "https://www.instagram.com/p/Ca3V3zGIZ08/"));
        assertEquals("CxYz123_-ab", InstagramRules.extractShortcode(
                "https://www.instagram.com/reel/CxYz123_-ab/"));
        assertEquals("CabcDEF1234", InstagramRules.extractShortcode(
                "https://instagram.com/tv/CabcDEF1234/"));
        // 裸 shortcode 与主页地址都不是作品地址
        assertNull("裸 shortcode 不应被当成 URL",
                InstagramRules.extractShortcode("Ca3V3zGIZ08"));
        assertNull(InstagramRules.extractShortcode("https://www.instagram.com/username/"));
        assertTrue(InstagramRules.isInstagramHost("www.instagram.com"));
        assertNotNull(InstagramRules.embedUrl("Ca3V3zGIZ08"));
    }

    // ------------------------------------------------------------------ 头条 / 西瓜

    @Test
    public void toutiaoRecognizesBothBrands() {
        assertTrue(ToutiaoWebRules.isToutiaoUrl("https://m.toutiao.com/i7035542323689685535/"));
        assertTrue(ToutiaoWebRules.isToutiaoUrl("https://www.ixigua.com/7035542323689685535"));
        assertFalse(ToutiaoWebRules.isToutiaoUrl("https://example.com/x"));
    }

    // ------------------------------------------------------------------ 云通道（调用方自备端点）

    @Test
    public void cloudApiKnowsSampleHostsButHasNoCredentials() {
        // 只验证"能识别出平台"，不验证任何端点/凭据——本库刻意不内置
        assertNotNull(CloudApiRules.platformIdOfHost("v.kuaishou.com"));
        assertNotNull(CloudApiRules.platformIdOfHost("www.ixigua.com"));
        assertNull(CloudApiRules.platformIdOfHost("example.com"));
    }
}
