package com.yohu.urldown.adapter;

import com.yohu.lib_urlparse.spi.CancelToken;
import com.yohu.lib_urlparse.spi.MediaMuxer;

import java.io.File;

/**
 * {@link MediaMuxer} 的 Android 实现：把 DASH 分离的视频轨与音频轨合成为一个 mp4。
 *
 * <p>为什么必须做这一步：B 站等站点的 DASH 地址是**音视频分离**的（两个 {@code .m4s}），
 * 只下载不合并，用户拿到的是"有画面没声音"和"有声音没画面"两个文件——技术上"下载成功"，
 * 实际上是不可用的。
 *
 * <p>实现委托 {@link DashMuxer}（MediaExtractor + MediaMuxer，无第三方依赖）。
 */
public final class AndroidMediaMuxer implements MediaMuxer {

    @Override
    public Result mux(File video, File audio, File output) throws Exception {
        DashMuxer.Result r = DashMuxer.mux(video, audio, output, CancelToken.none());
        if (r == null || r.file == null || !r.file.isFile()) {
            throw new java.io.IOException("合并失败：未产出文件");
        }
        return new Result(r.file, r.file.length(),
                "video=" + r.videoSamples + ", audio=" + r.audioSamples
                        + ", " + r.durationMs() + "ms");
    }
}
