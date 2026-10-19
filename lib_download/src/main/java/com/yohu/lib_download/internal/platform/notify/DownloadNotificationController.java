package com.yohu.lib_download.internal.platform.notify;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.annotation.SuppressLint;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.yohu.lib_download.YoDownload;
import com.yohu.lib_download.log.YoLog;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link YoDownload.Notification} 内部实现。
 */
public final class DownloadNotificationController implements YoDownload.Notification {

    private static final String TAG = "DownloadNotificationController";

    private static final DownloadNotificationController INSTANCE =
            new DownloadNotificationController();

    private static final String CHANNEL_ID = "app_download";
    private static volatile String activeChannelId = CHANNEL_ID;
    private static final AtomicInteger ID_SEQUENCE = new AtomicInteger(0x1000);
    private static final Map<String, Integer> NOTIFICATION_IDS = new ConcurrentHashMap<>();

    private DownloadNotificationController() {
    }

    @NonNull
    public static DownloadNotificationController get() {
        return INSTANCE;
    }

    @Override
    public void attach(@NonNull Context context) {
        attach(context, CHANNEL_ID, "下载进度");
    }

    /**
     * 使用自定义渠道 id/名称接入通知，避免与宿主既有渠道冲突。
     */
    public void attach(@NonNull Context context,
                       @NonNull String channelId,
                       @NonNull CharSequence channelName) {
        Context app = context.getApplicationContext();
        activeChannelId = channelId;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    channelName,
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            NotificationManager manager =
                    (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
        YoLog.d(TAG, "通知渠道已绑定: " + channelId);
    }

    @Override
    public void showStarted(@NonNull Context context,
                            @NonNull String taskId,
                            @NonNull String title) {
        show(context, taskId, title, 0);
    }

    @Override
    public void showProgress(@NonNull Context context,
                             @NonNull String taskId,
                             @NonNull String title,
                             int percent) {
        show(context, taskId, title, percent);
    }

    @Override
    public void cancel(@NonNull Context context, @NonNull String taskId) {
        Integer id = NOTIFICATION_IDS.remove(taskId);
        if (id != null) {
            NotificationManagerCompat.from(context.getApplicationContext()).cancel(id);
        }
    }

    @SuppressLint("MissingPermission")
    private static void show(@NonNull Context context,
                             @NonNull String taskId,
                             @NonNull String title,
                             int percent) {
        Context app = context.getApplicationContext();
        // POST_NOTIFICATIONS 由宿主声明；未授权时静默跳过，避免无意义通知。
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) {
            return;
        }
        int id = NOTIFICATION_IDS.computeIfAbsent(
                taskId, key -> ID_SEQUENCE.incrementAndGet());
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(app, activeChannelId)
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setContentTitle("下载 " + title)
                        .setContentText("进度：" + percent + "%")
                        .setOnlyAlertOnce(true)
                        .setOngoing(percent < 100)
                        .setProgress(100, percent, percent <= 0);
        try {
            NotificationManagerCompat.from(app).notify(id, builder.build());
        } catch (RuntimeException e) {
            YoLog.w(TAG, "显示通知失败: " + taskId, e);
        }
    }
}
