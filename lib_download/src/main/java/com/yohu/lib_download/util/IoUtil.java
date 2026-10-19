package com.yohu.lib_download.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * lib_common 共享 IO 工具。任何子系统都可引用，但仅限 internal 实现。
 */
public final class IoUtil {

    private IoUtil() {
    }

    public static void closeQuietly(@Nullable Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    public static boolean ensureDir(@NonNull File dir) {
        return dir.exists() ? dir.isDirectory() : dir.mkdirs();
    }

    public static boolean deleteQuietly(@Nullable File file) {
        return file == null || !file.exists() || file.delete();
    }

    public static void writeAt(@NonNull RandomAccessFile raf,
                               long offset,
                               @NonNull byte[] buffer,
                               int length) throws IOException {
        raf.seek(offset);
        raf.write(buffer, 0, length);
    }

    public static boolean rename(@NonNull File source, @NonNull File target) {
        File parent = target.getParentFile();
        if (parent != null && !ensureDir(parent)) {
            return false;
        }
        File backup = null;
        if (target.exists()) {
            backup = new File(parent != null ? parent : new File("."),
                    target.getName() + ".bak-" + System.currentTimeMillis());
            if (!target.renameTo(backup)) {
                return false;
            }
        }
        if (!source.renameTo(target)) {
            if (backup != null) {
                //noinspection ResultOfMethodCallIgnored
                backup.renameTo(target);
            }
            return false;
        }
        if (backup != null) {
            //noinspection ResultOfMethodCallIgnored
            backup.delete();
        }
        return true;
    }

    /**
     * 先写临时文件、再替换目标：失败时尽量恢复旧文件。
     *
     * @return 替换成功返回 true；失败时删除临时文件并返回 false。
     */
    public static boolean moveReplace(@NonNull File source, @NonNull File target) {
        boolean moved = rename(source, target);
        if (!moved) {
            deleteQuietly(source);
        }
        return moved;
    }
}
