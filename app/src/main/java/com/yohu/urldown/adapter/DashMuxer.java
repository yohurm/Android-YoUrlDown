package com.yohu.urldown.adapter;

import com.yohu.lib_urlparse.spi.CancelToken;
import com.yohu.lib_urlparse.transfer.DownloadException;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * 把 DASH 的**分离音视频轨**合成一个可播放文件（MP4）。
 *
 * <p>为什么必须有这一步：B 站等站点用 DASH 分发，视频轨与音频轨是两个独立文件
 * （实测：视频 852×480 的 {@code .m4s} 88.6 MB + 音频 {@code .m4s} 67.8 MB）。
 * 只把两个文件下下来，用户**打不开**——目标要求"校验可播放"，所以必须合并。
 *
 * <p>为什么不用 ffmpeg：Android 自带 {@link MediaExtractor} + {@link MediaMuxer}
 * 就能做这件事（API 18+，本工程 minSdk 21）。少一个几 MB 的原生库、少一处 ABI 风险，
 * 也避免把"能不能播放"寄托在第三方二进制上。样本自己走的是另一条路
 * （APK 内捆绑 ijkplayer/ffmpeg），我们不必照抄。
 *
 * <p>合并策略：**按 PTS 交错写入**。不能先写完视频再写音频——那样虽然 muxer 也接受，
 * 但部分播放器会因长时间无音频样本而卡顿或拒绝播放。
 */
public final class DashMuxer {

    private DashMuxer() {
    }

    /** 合并结果。 */
    public static final class Result {
        public final File file;
        public final int videoSamples;
        public final int audioSamples;
        public final long durationUs;
        public final String videoMime;
        public final String audioMime;

        Result(File file, int videoSamples, int audioSamples, long durationUs,
               String videoMime, String audioMime) {
            this.file = file;
            this.videoSamples = videoSamples;
            this.audioSamples = audioSamples;
            this.durationUs = durationUs;
            this.videoMime = videoMime;
            this.audioMime = audioMime;
        }

        public long durationMs() {
            return durationUs / 1000L;
        }

        @Override
        public String toString() {
            return file.getName() + "  " + videoSamples + " 视频样本 + " + audioSamples
                    + " 音频样本  " + durationMs() + " ms  [" + videoMime + " + " + audioMime + "]";
        }
    }

    /**
     * 合并两轨。
     *
     * @param video 含视频轨的容器（DASH 的 {@code .m4s} 或普通 mp4）
     * @param audio 含音频轨的容器
     * @param out   输出文件；已存在会被覆盖
     * @throws DownloadException 无法解析轨道或写入失败时抛出，{@link DownloadException.Reason#MUX_FAILED}
     */
    public static Result mux(File video, File audio, File out, CancelToken token)
            throws DownloadException {
        if (video == null || !video.isFile()) {
            throw muxError("视频轨文件不存在: " + video);
        }
        if (audio == null || !audio.isFile()) {
            throw muxError("音频轨文件不存在: " + audio);
        }
        if (out == null) {
            throw muxError("输出文件为空");
        }

        MediaExtractor videoEx = new MediaExtractor();
        MediaExtractor audioEx = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        File outFile = out;
        try {
            videoEx.setDataSource(video.getAbsolutePath());
            audioEx.setDataSource(audio.getAbsolutePath());

            int vTrack = firstTrackOf(videoEx, "video/");
            int aTrack = firstTrackOf(audioEx, "audio/");
            if (vTrack < 0) {
                throw muxError("视频文件里没有视频轨: " + video.getName()
                        + "（实际轨道: " + describeTracks(videoEx) + "）");
            }
            if (aTrack < 0) {
                throw muxError("音频文件里没有音频轨: " + audio.getName()
                        + "（实际轨道: " + describeTracks(audioEx) + "）");
            }
            videoEx.selectTrack(vTrack);
            audioEx.selectTrack(aTrack);
            MediaFormat vFmt = videoEx.getTrackFormat(vTrack);
            MediaFormat aFmt = audioEx.getTrackFormat(aTrack);

            muxer = new MediaMuxer(out.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vOut = muxer.addTrack(vFmt);
            int aOut = muxer.addTrack(aFmt);
            muxer.start();
            started = true;

            byte[] vBuf = new byte[sampleBufferSize(vFmt)];
            byte[] aBuf = new byte[sampleBufferSize(aFmt)];
            MediaCodec.BufferInfo vInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo aInfo = new MediaCodec.BufferInfo();

            // 各预读一个样本，然后按 PTS 谁小谁先写
            boolean vDone = !readSample(videoEx, vBuf, vInfo);
            boolean aDone = !readSample(audioEx, aBuf, aInfo);
            int vCount = 0;
            int aCount = 0;
            long lastPtsUs = 0;

            while (!vDone || !aDone) {
                if (token != null && token.isCancelled()) {
                    throw new DownloadException(DownloadException.Reason.CANCELLED,
                            "mux cancelled");
                }
                boolean writeVideo;
                if (vDone) {
                    writeVideo = false;
                } else if (aDone) {
                    writeVideo = true;
                } else {
                    writeVideo = vInfo.presentationTimeUs <= aInfo.presentationTimeUs;
                }
                if (writeVideo) {
                    muxer.writeSampleData(vOut, ByteBuffer.wrap(vBuf, 0, vInfo.size), vInfo);
                    lastPtsUs = Math.max(lastPtsUs, vInfo.presentationTimeUs);
                    vCount++;
                    vDone = !readSample(videoEx, vBuf, vInfo);
                } else {
                    muxer.writeSampleData(aOut, ByteBuffer.wrap(aBuf, 0, aInfo.size), aInfo);
                    lastPtsUs = Math.max(lastPtsUs, aInfo.presentationTimeUs);
                    aCount++;
                    aDone = !readSample(audioEx, aBuf, aInfo);
                }
            }

            muxer.stop();
            started = false;

            String vMime = vFmt.getString(MediaFormat.KEY_MIME);
            String aMime = aFmt.getString(MediaFormat.KEY_MIME);
            if (!out.isFile() || out.length() == 0) {
                throw muxError("muxer 结束后输出文件为空: " + out);
            }
            return new Result(outFile, vCount, aCount, lastPtsUs, vMime, aMime);
        } catch (IOException e) {
            discardPartial(outFile);
            throw new DownloadException(DownloadException.Reason.MUX_FAILED,
                    "合并失败(IO): " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // MediaMuxer/MediaExtractor 在格式不受支持时抛的是 RuntimeException（含 IllegalState）
            discardPartial(outFile);
            throw new DownloadException(DownloadException.Reason.MUX_FAILED,
                    "合并失败(" + e.getClass().getSimpleName() + "): " + e.getMessage(), e);
        } catch (DownloadException e) {
            discardPartial(outFile);
            throw e;
        } finally {
            if (muxer != null) {
                if (started) {
                    try {
                        muxer.stop();
                    } catch (RuntimeException ignored) {
                        // 已经在异常路径上，stop 失败没有补救手段
                    }
                }
                try {
                    muxer.release();
                } catch (RuntimeException ignored) {
                    // 释放失败无补救手段
                }
            }
            videoEx.release();
            audioEx.release();
        }
    }

    // ------------------------------------------------------------------ 策略

    /**
     * 判断这批下载结果是否属于"DASH 分离音视频、需要合并"的形态。
     *
     * <p>抽成纯函数是为了能在 JVM 上单测——真正的 mux 只能在设备上跑
     * （{@link MediaMuxer} 在 android.jar 里是抛 {@code Stub!} 的空实现）。
     *
     * <p>判据刻意保守：**恰好一条视频轨 + 恰好一条音频轨**才合并。
     * 若有多条视频（多 P、多清晰度）就不猜，交给调用方逐个处理——
     * 猜错会把用户的多个视频揉成一个坏文件。
     *
     * @return 要合并时返回 {@code [videoIdx, audioIdx]}，否则 null
     */
    public static int[] decideMux(List<File> files, List<String> mimes) {
        if (files == null || mimes == null || files.size() != mimes.size()) {
            return null;
        }
        int v = -1;
        int a = -1;
        int vCount = 0;
        int aCount = 0;
        for (int i = 0; i < files.size(); i++) {
            String m = mimes.get(i) == null ? "" : mimes.get(i).toLowerCase(java.util.Locale.ROOT);
            if (m.startsWith("video/")) {
                vCount++;
                v = i;
            } else if (m.startsWith("audio/")) {
                aCount++;
                a = i;
            }
        }
        if (vCount == 1 && aCount == 1) {
            return new int[]{v, a};
        }
        return null;
    }

    /** 合并输出名：把视频文件名的 {@code .m4s} 换成 {@code .mp4}。 */
    public static String muxedName(String videoFileName) {
        if (videoFileName == null) {
            return "muxed.mp4";
        }
        int dot = videoFileName.lastIndexOf('.');
        String base = dot > 0 ? videoFileName.substring(0, dot) : videoFileName;
        // 去掉下载时加的"轨道序号"尾巴（_2 / _music_3），让成品名更像作品名
        base = base.replaceAll("_(music_)?\\d+$", "");
        return base + ".mp4";
    }

    // ------------------------------------------------------------------ 内部

    private static DownloadException muxError(String message) {
        return new DownloadException(DownloadException.Reason.MUX_FAILED, message);
    }

    /**
     * 删除**合并失败留下的半截文件**。
     *
     * <p>这不是洁癖：合并是**流式**写出的，中途失败时磁盘上会留下一个体积可观、
     * 扩展名还是 {@code .mp4} 的残缺文件（实测跑到一半时就有 25 MB）。
     * 若不删，用户与后续流程都会把它当成成品——比明确失败更糟。
     * 原始分轨文件不动，仍可重试或人工处理。
     */
    private static void discardPartial(File out) {
        if (out == null) {
            return;
        }
        try {
            if (out.isFile() && !out.delete()) {
                // 删不掉就改名，至少不会再被误认为成品
                out.renameTo(new File(out.getAbsolutePath() + ".partial"));
            }
        } catch (RuntimeException ignored) {
            // 清理失败没有补救手段
        }
    }

    private static int firstTrackOf(MediaExtractor ex, String mimePrefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) {
                return i;
            }
        }
        return -1;
    }

    private static String describeTracks(MediaExtractor ex) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ex.getTrackCount(); i++) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME));
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    private static int sampleBufferSize(MediaFormat fmt) {
        int max = 512 * 1024;
        try {
            if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                max = Math.max(max, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
            }
        } catch (RuntimeException ignored) {
            // 有些 extractor 不提供该键
        }
        return max;
    }

    /** 读一个样本；返回 false 表示读到结尾。 */
    private static boolean readSample(MediaExtractor ex, byte[] buf, MediaCodec.BufferInfo info) {
        int size = ex.readSampleData(ByteBuffer.wrap(buf), 0);
        if (size < 0) {
            return false;
        }
        info.offset = 0;
        info.size = size;
        info.presentationTimeUs = ex.getSampleTime();
        info.flags = ex.getSampleFlags();
        ex.advance();
        return true;
    }
}

