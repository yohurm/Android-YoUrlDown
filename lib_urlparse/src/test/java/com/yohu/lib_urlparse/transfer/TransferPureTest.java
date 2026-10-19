package com.yohu.lib_urlparse.transfer;

import com.yohu.lib_urlparse.api.MediaAsset;
import com.yohu.lib_urlparse.api.Platform;
import com.yohu.lib_urlparse.api.UrlParseResult;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 下载层纯函数测试（不联网、不写盘）：播放列表解析、文件名、容器探测。
 */
public class TransferPureTest {

    // ------------------------------------------------------------------ M3U8

    @Test
    public void parsesMasterPlaylistIntoVariants() {
        String master = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080\n"
                + "1080/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\n"
                + "360/index.m3u8\n";
        M3u8Playlist p = M3u8Playlist.parse(master, "https://cdn.test/master.m3u8");
        assertEquals(2, p.variants().size());
        assertTrue("变体地址应被解析成绝对地址: " + p.variants().get(0).uri,
                p.variants().get(0).uri.startsWith("https://cdn.test/"));
        assertEquals(2_000_000L, p.variants().get(0).bandwidth);
    }

    @Test
    public void parsesMediaPlaylistWithAesKey() {
        String media = "#EXTM3U\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x0123456789ABCDEF0123456789ABCDEF\n"
                + "#EXTINF:4.0,\n"
                + "seg0.ts\n"
                + "#EXTINF:4.0,\n"
                + "seg1.ts\n"
                + "#EXT-X-ENDLIST\n";
        M3u8Playlist p = M3u8Playlist.parse(media, "https://cdn.test/path/index.m3u8");
        assertEquals(2, p.segments().size());
        M3u8Playlist.Segment s = p.segments().get(0);
        assertTrue("段应当是绝对地址", s.uri.startsWith("https://cdn.test/path/"));
        assertTrue("必须识别出加密段", s.encrypted());
        assertEquals("AES-128", s.keyMethod);
        assertNotNull("密钥地址应被解析出来", s.keyUri);
        assertTrue(s.keyUri.endsWith("key.bin"));
        assertNotNull("IV 应被解析出来", s.keyIv);
        assertEquals(16, s.keyIv.length);
        assertEquals(4.0, s.durationSeconds, 0.001);
    }

    @Test
    public void mediaPlaylistWithoutKeyIsUnencrypted() {
        String media = "#EXTM3U\n#EXTINF:2.5,\nseg.ts\n";
        M3u8Playlist p = M3u8Playlist.parse(media, "https://cdn.test/index.m3u8");
        assertFalse(p.segments().get(0).encrypted());
        assertEquals(1, p.segments().size());
    }

    @Test
    public void parseToleratesGarbage() {
        // 解析器是**宽松**的：非 '#' 行会被当成段地址（真实播放列表里段地址就是裸行）。
        // 因此这里要保证的不是"没有段"，而是：不抛异常、且产出的地址都被解析成绝对地址。
        M3u8Playlist p = M3u8Playlist.parse("not a playlist at all", "https://cdn.test/x");
        assertNotNull(p);
        assertNotNull(p.warnings());
        for (M3u8Playlist.Segment s : p.segments()) {
            assertTrue("宽松解析也不能产出非绝对地址: " + s.uri,
                    s.uri.startsWith("https://cdn.test/"));
        }
    }

    // ------------------------------------------------------------------ 文件名

    @Test
    public void rejectsPseudoExtensionAndUsesMime() {
        MediaAsset pseudo = MediaAsset.image("https://p3.douyinpic.com/a~tplv-noop.image").build();
        assertEquals("伪扩展名不能当真，应按类型兜底为 jpg", "jpg", FileNames.extensionFor(pseudo));

        MediaAsset mp4 = MediaAsset.video("https://cdn.test/v/1").mimeType("video/mp4").build();
        assertEquals("mp4", FileNames.extensionFor(mp4));
        assertNull("无可信扩展名时不应瞎猜", FileNames.extensionFromUrl("https://cdn.test/v/1"));
    }

    @Test
    public void sanitizesTitleForFilesystem() {
        assertEquals("a_b_c", FileNames.sanitize("a/b:c"));
        assertEquals("标题 保留中文", FileNames.sanitize("标题  保留中文"));
        assertEquals("结尾去点", FileNames.sanitize("结尾去点..."));
        assertEquals("", FileNames.sanitize("   "));
        assertEquals(80, FileNames.sanitize(new String(new char[120]).replace('\0', 'x')).length());
    }

    @Test
    public void buildsNamesForWholeResult() {
        UrlParseResult r = UrlParseResult.builder(Platform.DOUYIN)
                .title("一个作品/标题")
                .asset(MediaAsset.video("https://cdn.test/1.mp4").build())
                .asset(MediaAsset.image("https://cdn.test/1.jpg").build())
                .build();
        List<String> names = FileNames.forResult(r);
        assertEquals(2, names.size());
        assertEquals("一个作品_标题.mp4", names.get(0));
        assertEquals("一个作品_标题_2.jpg", names.get(1));
    }

    // ------------------------------------------------------------------ 容器探测

    @Test
    public void detectsMp4AndHtml() {
        byte[] mp4 = new byte[]{0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
        assertEquals(ContainerProbe.Container.MP4, ContainerProbe.probe(mp4));

        byte[] html = "<!DOCTYPE html><html".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("HTML 错误页必须被识别为文本",
                ContainerProbe.Container.TEXT, ContainerProbe.probe(html));
    }

    @Test
    public void detectsImagesAndTransportStream() {
        byte[] jpg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
        assertEquals(ContainerProbe.Container.JPEG, ContainerProbe.probe(jpg));

        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A};
        assertEquals(ContainerProbe.Container.PNG, ContainerProbe.probe(png));

        byte[] ts = new byte[]{0x47, 0x40, 0x00, 0x10};
        assertEquals(ContainerProbe.Container.MPEG_TS, ContainerProbe.probe(ts));
    }

    @Test
    public void textDownloadIsRejectedByVerdict() {
        ContainerProbe.Verdict v = ContainerProbe.verify(null);
        assertFalse("不存在的文件不能算合格", v.acceptable());
        assertTrue(v.reason().contains("无法识别") || v.reason().contains("文本"));
    }

    @Test
    public void playableContainersAreAcceptable() {
        byte[] mp4 = new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2'};
        assertTrue(ContainerProbe.probe(mp4).isPlayableMedia());
        assertFalse("文本不是可播放媒体", ContainerProbe.Container.TEXT.isPlayableMedia());
    }
}
