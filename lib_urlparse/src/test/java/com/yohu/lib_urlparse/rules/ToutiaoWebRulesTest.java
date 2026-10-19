package com.yohu.lib_urlparse.rules;

import com.yohu.lib_urlparse.rules.ToutiaoWebRules;

import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 头条/西瓜直解规则的测试，其中最重要的一条用**真机取回的真实页面**跑，
 * 而不是我按记忆手写的样例。
 *
 * <p>为什么必须这样：本工程已经吃过一次亏——手写样例让测试全绿，
 * 但真实页面的字段一个都不存在（见 {@code InstagramRulesTest} 的注释）。
 *
 * <p>样本来源（真机、移动 UA）：
 * <ul>
 *   <li>{@code toutiao_mobile_page.html}：
 *       {@code GET https://m.toutiao.com/i7035542323689685535/} → HTTP 200，39,406 字符；</li>
 *   <li>{@code toutiao_vod_response.json}：
 *       {@code GET https://vod.bytedanceapi.com/?<GetPlayInfoToken>} → HTTP 200，3,539 字符。</li>
 * </ul>
 */
public class ToutiaoWebRulesTest {

    // ---------------------------------------------------------------- 链接识别

    @Test
    public void recognizesAllThreeRealUrlForms() {
        // 三种都实测过
        assertEquals("7035542323689685535",
                ToutiaoWebRules.extractItemId("https://m.toutiao.com/i7035542323689685535/"));
        assertEquals("7035542323689685535",
                ToutiaoWebRules.extractItemId("https://www.toutiao.com/video/7035542323689685535/"));
        assertEquals("7035542323689685535",
                ToutiaoWebRules.extractItemId("https://www.ixigua.com/7035542323689685535"));
        // 查询串形态
        assertEquals("7035542323689685535",
                ToutiaoWebRules.extractItemId("https://www.ixigua.com/x?item_id=7035542323689685535"));
    }

    @Test
    public void hostGateIsStrict() {
        assertTrue(ToutiaoWebRules.isToutiaoUrl("https://www.ixigua.com/7035542323689685535"));
        assertTrue(ToutiaoWebRules.isToutiaoUrl("https://m.toutiao.com/i1234567890/"));
        // 不相关站点不能被抢走
        assertTrue(!ToutiaoWebRules.isToutiaoUrl("https://www.bilibili.com/video/BV1HU4y1K7TT"));
        assertTrue(!ToutiaoWebRules.isToutiaoUrl("https://v.douyin.com/8hedewA/"));
        assertTrue(!ToutiaoWebRules.isToutiaoUrl(null));
    }

    @Test
    public void mobilePageIsUsedToAvoidDesktopShell() {
        // 桌面页是 JS-VM 壳 + 安全 SDK；移动页才是服务端渲染
        assertEquals("https://m.toutiao.com/i7035542323689685535/",
                ToutiaoWebRules.mobilePageUrl("7035542323689685535"));
    }

    // ---------------------------------------------------------------- 真实页面

    @Test
    public void realMobilePageYieldsRenderDataAndPlayToken() throws Exception {
        InputStream in = getClass().getClassLoader()
                .getResourceAsStream("toutiao_mobile_page.html");
        org.junit.Assume.assumeTrue("真实样本缺失则跳过", in != null);
        String html = new String(readAll(in), StandardCharsets.UTF_8);

        JSONObject renderData = ToutiaoWebRules.extractRenderData(html);
        assertNotNull("真机页面里必须有 RENDER_DATA", renderData);
        JSONObject info = ToutiaoWebRules.articleInfo(renderData);
        assertNotNull("RENDER_DATA 里必须有 articleInfo", info);

        assertEquals("“直接当场就社会性死亡了！”", info.optString("title"));
        assertEquals("v02004g10000c6hk7sbc77u8onjlhi6g", info.optString("videoId"));
        // 251.426 秒 ⇒ 251000 ms（截断而非四舍五入）
        assertEquals(251000L, ToutiaoWebRules.durationMs(info));
        assertTrue("必须带封面", info.optString("posterUrl", "").startsWith("http"));

        // playUrlList 在移动页上是 null —— 真正的地址要靠 token 去换
        assertNull("移动页不给 playUrlList（这是实测结论）", info.optJSONArray("playUrlList"));

        String token = ToutiaoWebRules.decodePlayAuthToken(info.optString("playAuthTokenV2", null));
        assertNotNull("必须能解出已签名的 GetPlayInfoToken", token);
        assertTrue("token 里应带 GetPlayInfo action", token.startsWith("Action=GetPlayInfo"));
        assertTrue("token 里应带 video_id", token.contains("video_id=v02004g10000c6hk7sbc77u8onjlhi6g"));
        assertTrue("token 里应带签名", token.contains("X-Amz-Signature="));
        assertTrue("拼出的 VOD URL 要落在 vod.bytedanceapi.com",
                ToutiaoWebRules.vodRequestUrl(token).startsWith("https://vod.bytedanceapi.com/?"));
    }

    @Test
    public void realVodResponseYieldsPlayableMp4() throws Exception {
        InputStream in = getClass().getClassLoader()
                .getResourceAsStream("toutiao_vod_response.json");
        org.junit.Assume.assumeTrue("真实样本缺失则跳过", in != null);
        JSONObject vod = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));

        List<ToutiaoWebRules.PlayUrl> plays = ToutiaoWebRules.playUrlsFromVod(vod);
        assertEquals("应恰好一条播放地址", 1, plays.size());
        ToutiaoWebRules.PlayUrl p = plays.get(0);
        assertTrue("必须是 http 直链", p.url.startsWith("https://"));
        assertTrue("必须是 mp4", "mp4".equals(p.format));
        assertEquals(1280, p.width);
        assertEquals(720, p.height);
        assertEquals("720p", p.definition);
        assertTrue("码率应为正", p.bitrate > 0);

        String cover = ToutiaoWebRules.coverUrlFromVod(vod);
        assertNotNull(cover);
        assertTrue(cover.startsWith("https://"));
    }

    @Test
    public void missingRenderDataIsNullNotException() {
        assertNull(ToutiaoWebRules.extractRenderData("<html>没有数据的壳</html>"));
        assertNull(ToutiaoWebRules.extractRenderData(null));
        assertNull(ToutiaoWebRules.decodePlayAuthToken("不是base64"));
        assertNull(ToutiaoWebRules.decodePlayAuthToken(null));
        assertTrue(ToutiaoWebRules.playUrlsFromVod(null).isEmpty());
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
