package com.yohu.lib_download.internal.engine;

import com.yohu.lib_download.YoDownload;

import androidx.annotation.NonNull;

import com.yohu.lib_download.internal.impl.DownloadConfigImpl;
import com.yohu.lib_download.model.BlockInfo;
import com.yohu.lib_download.internal.platform.connection.DownloadConnection;
import com.yohu.lib_download.internal.platform.connection.DownloadResponse;
import com.yohu.lib_download.internal.exception.ResumeUnsupportedException;
import com.yohu.lib_download.internal.platform.file.MultiBlockFileWriter;
import com.yohu.lib_download.internal.util.DownloadUtil;
import com.yohu.lib_download.util.IoUtil;
import com.yohu.lib_download.log.YoLog;

import java.io.IOException;
import java.io.InputStream;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description 单个 Range 分块下载执行器。
 */
public final class DownloadBlockWorker implements Runnable {

    private static final String TAG = "DownloadBlockWorker";

    public interface ProgressReporter {
        void onBlockBytes(@NonNull BlockInfo block, int bytes) throws IOException;
    }

    private final DownloadConfigImpl config;
    private final YoDownload.Request request;
    private final DownloadCall call;
    private final BlockInfo block;
    private final MultiBlockFileWriter writer;
    private final ProgressReporter reporter;
    private volatile Throwable error;

    public DownloadBlockWorker(@NonNull DownloadConfigImpl config,
                               @NonNull YoDownload.Request request,
                               @NonNull DownloadCall call,
                               @NonNull BlockInfo block,
                               @NonNull MultiBlockFileWriter writer,
                               @NonNull ProgressReporter reporter) {
        this.config = config;
        this.request = request;
        this.call = call;
        this.block = block;
        this.writer = writer;
        this.reporter = reporter;
    }

    @Override
    public void run() {
        // 重试统一由任务级 runTaskWithRetry 负责，避免 block 级与任务级重试嵌套放大。
        runOnce();
    }

    private boolean runOnce() {
        DownloadConnection connection = null;
        InputStream in = null;
        try {
            error = null;
            long offset = block.currentOffset();
            if (offset > block.endOffset()) {
                return true;
            }
            connection = config.connectionFactory().create(
                    request.url(), config.connectTimeoutMs(), config.readTimeoutMs());
            connection.addHeaders(request.headers());
            connection.setRange(offset, block.endOffset());
            DownloadResponse response = connection.connect();
            if (!DownloadUtil.isRangeResponse(response.responseCode())) {
                throw new ResumeUnsupportedException("Server ignored range request");
            }
            in = connection.inputStream();
            byte[] buffer = new byte[config.bufferSize()];
            int read;
            while (!call.shouldStop() && (read = in.read(buffer)) != -1) {
                writer.write(offset, buffer, read);
                offset += read;
                block.currentOffset(offset);
                reporter.onBlockBytes(block, read);
            }
            return error == null;
        } catch (Throwable e) {
            error = e;
            YoLog.w(TAG, "分块 " + block.blockIndex() + " 下载失败", e);
            return false;
        } finally {
            IoUtil.closeQuietly(in);
            IoUtil.closeQuietly(connection);
        }
    }

    public Throwable error() {
        return error;
    }
}
