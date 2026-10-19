package com.yohu.lib_urlparse.spi;

import java.io.File;

/**
 * 音视频合并（DASH：视频流与音频流分开下载后合成一个文件）。
 *
 * <p>为什么外置：Android 上唯一可靠的实现是 {@code MediaExtractor} + {@code MediaMuxer}，
 * 那是平台 API；放进引擎会让引擎无法在纯 JVM 上跑。
 *
 * <p>实现要求：
 * <ul>
 *   <li>成功时必须产出 {@code output} 且可被系统媒体框架识别</li>
 *   <li>失败时不得留下半个输出文件</li>
 *   <li>不修改输入文件</li>
 * </ul>
 */
public interface MediaMuxer {

    /**
     * 把视频流与音频流合并为一个文件。
     *
     * @param video  视频轨道文件
     * @param audio  音频轨道文件
     * @param output 输出文件（不存在）
     * @return 合并结果
     * @throws Exception 合并失败
     */
    Result mux(File video, File audio, File output) throws Exception;

    /** 合并结果。 */
    final class Result {
        private final File output;
        private final long bytes;
        private final String note;

        public Result(File output, long bytes, String note) {
            this.output = output;
            this.bytes = bytes;
            this.note = note;
        }

        public File output() {
            return output;
        }

        public long bytes() {
            return bytes;
        }

        /** 补充说明（例如"音轨缺失，已单独输出"）。 */
        public String note() {
            return note;
        }

        @Override
        public String toString() {
            return "MuxResult{" + output.getName() + ", " + bytes + "B, note=" + note + '}';
        }
    }
}
