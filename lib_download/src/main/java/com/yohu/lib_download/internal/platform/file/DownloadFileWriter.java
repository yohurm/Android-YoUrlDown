package com.yohu.lib_download.internal.platform.file;

import androidx.annotation.NonNull;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description 顺序下载文件写入器。
 */
public final class DownloadFileWriter implements Closeable {

    private final OutputStream out;
    private long position;

    public DownloadFileWriter(@NonNull File file, boolean append) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create parent dir: " + parent);
        }
        this.position = append && file.exists() ? file.length() : 0L;
        this.out = new BufferedOutputStream(new FileOutputStream(file, append));
    }

    public synchronized long write(@NonNull byte[] buffer, int length) throws IOException {
        out.write(buffer, 0, length);
        position += length;
        return position;
    }

    public synchronized long position() {
        return position;
    }

    public synchronized void flush() throws IOException {
        out.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        out.close();
    }
}
