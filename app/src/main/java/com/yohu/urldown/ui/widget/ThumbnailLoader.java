package com.yohu.urldown.ui.widget;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 任务缩略图加载：优先用本地已下载的文件（视频取帧 / 图片缩放），
 * 没有本地文件时再拉封面图；都拿不到就保持首字占位。
 *
 * <p>只做内存 LRU（约 4MB），按任务键缓存；失败的键记下来不重复请求。
 * 两条后台线程，网络慢时不会拖住本地取帧。
 */
public final class ThumbnailLoader {

    private static final int TARGET_PX = 144;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "yo-thumb");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final Set<String> MISSES = new HashSet<>();
    private static final Set<String> LOADING = new HashSet<>();

    private ThumbnailLoader() {
    }

    /** 在主线程调用。命中缓存立即设置，否则后台加载完再回填（控件换绑了就丢弃）。 */
    public static void load(@NonNull TaskThumb view, @NonNull String key, @Nullable File local,
                            @Nullable String coverUrl, @NonNull Map<String, String> headers) {
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            view.setBitmap(key, cached);
            return;
        }
        if (MISSES.contains(key) || LOADING.contains(key)) {
            return;
        }
        if (local == null && (coverUrl == null || coverUrl.isEmpty())) {
            return;
        }
        LOADING.add(key);
        POOL.execute(() -> {
            Bitmap bmp = null;
            if (local != null && local.isFile()) {
                bmp = fromFile(local);
            }
            if (bmp == null && coverUrl != null && !coverUrl.isEmpty()) {
                bmp = fromUrl(coverUrl, headers);
            }
            Bitmap result = bmp;
            MAIN.post(() -> {
                LOADING.remove(key);
                if (result == null) {
                    MISSES.add(key);
                    return;
                }
                CACHE.put(key, result);
                if (key.equals(view.key())) {
                    view.setBitmap(key, result);
                }
            });
        });
    }

    /** 文件变了（比如重试成功）时清掉旧记录。 */
    public static void forget(@NonNull String key) {
        CACHE.remove(key);
        MISSES.remove(key);
    }

    @Nullable
    private static Bitmap fromFile(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        Size size = new Size(TARGET_PX, TARGET_PX);
        try {
            if (name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".webm")
                    || name.endsWith(".mkv") || name.endsWith(".m4v")) {
                return ThumbnailUtils.createVideoThumbnail(file, size, null);
            }
            if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                    || name.endsWith(".webp") || name.endsWith(".heic") || name.endsWith(".gif")) {
                return ThumbnailUtils.createImageThumbnail(file, size, null);
            }
        } catch (Exception | OutOfMemoryError ignored) {
            // 坏文件 / 不支持的编码：退回封面或首字
        }
        return null;
    }

    /** 最多读 cap 字节（封面图不该更大；超出就截断，解码会失败并退回首字）。 */
    private static byte[] readCapped(InputStream in, int cap) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n;
        while (out.size() < cap && (n = in.read(buf, 0, Math.min(buf.length, cap - out.size()))) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    @Nullable
    private static Bitmap fromUrl(String url, Map<String, String> headers) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(10_000);
            conn.setInstanceFollowRedirects(true);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                conn.setRequestProperty(h.getKey(), h.getValue());
            }
            if (conn.getResponseCode() / 100 != 2) {
                return null;
            }
            byte[] bytes;
            try (InputStream in = conn.getInputStream()) {
                bytes = readCapped(in, 4 * 1024 * 1024);
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= TARGET_PX && bounds.outHeight / (sample * 2) >= TARGET_PX) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
