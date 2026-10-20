package com.yohu.urldown.download.hls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

/**
 * HLS 下载：解密、fMP4 初始化段、断点续传（播放列表签名变了也能接上）、直播流拒绝。
 * 本地起 MockWebServer，不访问外网。
 */
public class HlsDownloaderTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final byte[] KEY = "0123456789abcdef".getBytes();
    private static final long FIRST_SEQUENCE = 7;

    private MockWebServer server;
    private final List<String> paths = Collections.synchronizedList(new ArrayList<>());
    private final byte[][] plain = {
            "segment-zero-".getBytes(), "segment-one--".getBytes(), "segment-two--".getBytes()
    };
    private String playlist;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                int q = path.indexOf('?');
                String bare = q >= 0 ? path.substring(0, q) : path;
                paths.add(bare);
                try {
                    switch (bare) {
                        case "/v/index.m3u8":
                            return new MockResponse().setBody(playlist);
                        case "/v/key.bin":
                            return new MockResponse().setBody(new Buffer().write(KEY));
                        case "/v/init.mp4":
                            return new MockResponse().setBody("INIT");
                        default:
                            if (bare.startsWith("/v/seg")) {
                                int i = Integer.parseInt(bare.substring(6, 7));
                                byte[] body = playlist.contains("EXT-X-KEY")
                                        ? encrypt(plain[i], FIRST_SEQUENCE + i) : plain[i];
                                return new MockResponse().setBody(new Buffer().write(body));
                            }
                            return new MockResponse().setResponseCode(404);
                    }
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500);
                }
            }
        });
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    private static byte[] encrypt(byte[] data, long sequence) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"),
                new IvParameterSpec(M3u8Playlist.sequenceIv(sequence)));
        return c.doFinal(data);
    }

    private String url(String query) {
        return server.url("/v/index.m3u8" + query).toString();
    }

    private byte[] expected(String prefix) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(prefix.getBytes());
        for (byte[] p : plain) {
            out.write(p);
        }
        return out.toByteArray();
    }

    private static String media(boolean encrypted, boolean fmp4, boolean endList) {
        StringBuilder b = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:4\n")
                .append("#EXT-X-MEDIA-SEQUENCE:").append(FIRST_SEQUENCE).append('\n');
        if (fmp4) {
            b.append("#EXT-X-MAP:URI=\"init.mp4\"\n");
        }
        if (encrypted) {
            // 不给 IV：按 RFC 8216 用分片序号（MEDIA-SEQUENCE + 下标）做 IV
            b.append("#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n");
        }
        for (int i = 0; i < 3; i++) {
            b.append("#EXTINF:4.0,\nseg").append(i).append(".ts\n");
        }
        if (endList) {
            b.append("#EXT-X-ENDLIST\n");
        }
        return b.toString();
    }

    @Test
    public void decryptsWithSequenceIv() throws Exception {
        playlist = media(true, false, true);
        File target = new File(tmp.getRoot(), "a.ts");
        HlsDownloader.download(url(""), Collections.emptyMap(), target, (b, d, t, s) -> { }, () -> false);
        assertArrayEquals(expected(""), Files.readAllBytes(target.toPath()));
        assertFalse("完成后不留临时文件", new File(target.getPath() + ".part").exists());
        assertFalse(new File(target.getPath() + ".part.idx").exists());
    }

    @Test
    public void writesInitSegmentFirstForFmp4() throws Exception {
        playlist = media(false, true, true);
        File target = new File(tmp.getRoot(), "b.mp4");
        HlsDownloader.download(url(""), Collections.emptyMap(), target, (b, d, t, s) -> { }, () -> false);
        assertArrayEquals(expected("INIT"), Files.readAllBytes(target.toPath()));
    }

    @Test
    public void resumesAfterCancelEvenWhenSignatureChanges() throws Exception {
        playlist = media(true, true, true);
        File target = new File(tmp.getRoot(), "c.mp4");
        AtomicBoolean stop = new AtomicBoolean();
        try {
            HlsDownloader.download(url("?sig=1"), Collections.emptyMap(), target,
                    (b, done, t, s) -> stop.set(done >= 1), stop::get);
            fail("应在第一个分片后停下");
        } catch (HlsDownloader.HlsException e) {
            assertEquals(HlsDownloader.Kind.CANCELED, e.kind);
        }
        assertTrue("暂停要保留已下的分片", new File(target.getPath() + ".part").isFile());
        assertFalse(target.exists());

        paths.clear();
        // 重新解析后签名参数变了，内容不变：应接着第 2 个分片下
        HlsDownloader.download(url("?sig=2"), Collections.emptyMap(), target, (b, d, t, s) -> { }, () -> false);
        assertArrayEquals(expected("INIT"), Files.readAllBytes(target.toPath()));
        assertFalse("续传不应重下初始化段", paths.contains("/v/init.mp4"));
        assertFalse("续传不应重下已完成的分片", paths.contains("/v/seg0.ts"));
        assertTrue(paths.contains("/v/seg1.ts"));
        assertTrue(paths.contains("/v/seg2.ts"));
    }

    @Test
    public void discardPartialRemovesCheckpoint() throws Exception {
        playlist = media(false, false, true);
        File target = new File(tmp.getRoot(), "d.ts");
        AtomicBoolean stop = new AtomicBoolean();
        try {
            HlsDownloader.download(url(""), Collections.emptyMap(), target,
                    (b, done, t, s) -> stop.set(true), stop::get);
            fail();
        } catch (HlsDownloader.HlsException expected) {
            // 取消
        }
        HlsDownloader.discardPartial(target);
        assertFalse(new File(target.getPath() + ".part").exists());
        assertFalse(new File(target.getPath() + ".part.idx").exists());
    }

    @Test
    public void rejectsLivePlaylist() {
        playlist = media(false, false, false);
        try {
            HlsDownloader.download(url(""), Collections.emptyMap(), new File(tmp.getRoot(), "e.ts"),
                    (b, d, t, s) -> { }, () -> false);
            fail("直播流没有终点，不能下载");
        } catch (HlsDownloader.HlsException e) {
            assertEquals(HlsDownloader.Kind.FORMAT, e.kind);
        }
    }

    @Test
    public void missingSegmentIsHttpError() {
        playlist = "#EXTM3U\n#EXTINF:4.0,\nmissing.ts\n#EXT-X-ENDLIST\n";
        try {
            HlsDownloader.download(url(""), Collections.emptyMap(), new File(tmp.getRoot(), "f.ts"),
                    (b, d, t, s) -> { }, () -> false);
            fail();
        } catch (HlsDownloader.HlsException e) {
            assertEquals(HlsDownloader.Kind.HTTP, e.kind);
            assertEquals(404, e.httpStatus);
        }
    }

    @Test
    public void checkpointKeyIgnoresQueryAndFragment() {
        assertEquals("https://a/b.m3u8", HlsDownloader.Checkpoint.keyOf("https://a/b.m3u8?x=1#t"));
        assertEquals("https://a/b.m3u8", HlsDownloader.Checkpoint.keyOf("https://a/b.m3u8#t?x"));
    }

    @Test
    public void parsesMapAndMediaSequence() {
        M3u8Playlist p = M3u8Playlist.parse(media(true, true, true), "https://cdn.test/v/index.m3u8");
        assertEquals(FIRST_SEQUENCE, p.mediaSequence());
        assertTrue(p.initSegment().uri.endsWith("/v/init.mp4"));
        assertEquals(3, p.segments().size());
        assertTrue(p.hasEndList());
        byte[] iv = M3u8Playlist.sequenceIv(258);
        assertEquals(16, iv.length);
        assertEquals("序号按大端写在 IV 末尾", 1, iv[14]);
        assertEquals(2, iv[15]);
    }
}
