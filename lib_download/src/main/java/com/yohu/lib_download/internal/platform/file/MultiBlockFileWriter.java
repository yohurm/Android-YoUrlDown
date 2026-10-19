package com.yohu.lib_download.internal.platform.file;

import androidx.annotation.NonNull;

import com.yohu.lib_download.util.IoUtil;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description 多分块随机写入器。多个 block 可按 offset 写入同一临时文件。
 */
public final class MultiBlockFileWriter implements Closeable {

    private final RandomAccessFile raf;

    public MultiBlockFileWriter(@NonNull File file, long totalLength) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create parent dir: " + parent);
        }
        this.raf = new RandomAccessFile(file, "rw");
        if (totalLength > 0L) {
            raf.setLength(totalLength);
        }
    }

    public synchronized void write(long offset,
                                   @NonNull byte[] buffer,
                                   int length) throws IOException {
        IoUtil.writeAt(raf, offset, buffer, length);
    }

    public synchronized void flush() throws IOException {
        raf.getFD().sync();
    }

    @Override
    public synchronized void close() throws IOException {
        raf.close();
    }
}
