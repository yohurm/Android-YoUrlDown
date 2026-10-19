package com.yohu.lib_download.internal.platform.file;

import com.yohu.lib_download.YoDownload;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.yohu.lib_download.internal.util.DownloadUtil;
import com.yohu.lib_download.util.IoUtil;

import java.io.File;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description 目标文件 / 临时文件决策策略。
 */
public class DownloadFileStrategy {

    private final File defaultDownloadDir;
    private final FilenameStrategy filenameStrategy;

    public DownloadFileStrategy(@NonNull File defaultDownloadDir) {
        this(defaultDownloadDir, new FilenameStrategy());
    }

    public DownloadFileStrategy(@NonNull File defaultDownloadDir,
                                @NonNull FilenameStrategy filenameStrategy) {
        this.defaultDownloadDir = defaultDownloadDir;
        this.filenameStrategy = filenameStrategy;
    }

    @NonNull
    public File destinationFile(@NonNull YoDownload.Request request) {
        if (request.destinationFile() != null) {
            return request.destinationFile();
        }
        File dir = request.destinationDir() != null ? request.destinationDir() : defaultDownloadDir;
        String filename = filenameStrategy.filename(request);
        return new File(dir, filename);
    }

    @NonNull
    public File tempFile(@NonNull File destinationFile) {
        return DownloadUtil.tempFile(destinationFile);
    }

    public boolean prepareDestination(@NonNull File destinationFile,
                                      boolean replaceExisting) {
        File parent = destinationFile.getParentFile();
        if (parent != null && !IoUtil.ensureDir(parent)) {
            return false;
        }
        if (destinationFile.exists()) {
            return replaceExisting;
        }
        return true;
    }

    public boolean complete(@NonNull File tempFile, @NonNull File destinationFile) {
        return IoUtil.rename(tempFile, destinationFile);
    }
}
