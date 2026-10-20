package com.yohu.urldown.task;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.yohu.urldown.MainActivity;
import com.yohu.urldown.R;
import com.yohu.urldown.util.Bytes;

import java.util.List;

/**
 * 下载期间的前台服务（{@code dataSync}）：让系统在应用退到后台时不回收下载进程，
 * 并在通知栏显示一条<b>聚合</b>进度（N 项 · 总进度 · 当前速度），附“全部暂停”。
 *
 * <p>生命周期完全由任务状态驱动：有排队 / 运行 / 等网络的任务就在，全部结束或暂停就停，
 * 不需要页面手动启停。每个任务不单独发通知，避免通知栏刷屏。
 */
public final class DownloadService extends Service {

    static final String CHANNEL_ID = "downloads";
    private static final int NOTIFICATION_ID = 0x4e10;
    private static final String ACTION_PAUSE_ALL = "com.yohu.urldown.action.PAUSE_ALL";
    /** 通知刷新间隔：系统对频繁更新会限流，1 秒足够。 */
    private static final long UPDATE_INTERVAL_MS = 1_000L;

    @Nullable
    private static DownloadService instance;
    private static boolean starting;

    private long lastUpdateAt;

    /** 按当前任务状态启动、刷新或停止服务。 */
    @MainThread
    static void sync(@NonNull Context context) {
        boolean needed = hasWork();
        DownloadService service = instance;
        if (service != null) {
            if (needed) {
                service.update(true);
            } else {
                service.stop();
            }
            return;
        }
        if (!needed || starting) {
            return;
        }
        starting = true;
        try {
            ContextCompat.startForegroundService(context,
                    new Intent(context, DownloadService.class));
        } catch (RuntimeException e) {
            // 后台不允许启动前台服务（Android 12+）：下载照常进行，只是没有常驻通知
            starting = false;
        }
    }

    /** 进度变化：节流刷新通知。 */
    @MainThread
    static void onProgress() {
        DownloadService service = instance;
        if (service != null) {
            service.update(false);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        starting = false;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.notify_channel_downloads), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } catch (RuntimeException e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_PAUSE_ALL.equals(intent.getAction())) {
            DownloadRunner.pauseAll();
        }
        if (!hasWork()) {
            stop();
        }
        return START_NOT_STICKY;
    }

    /** Android 15+：dataSync 前台服务的累计时长到上限。下载继续，只是不再常驻前台。 */
    @Override
    public void onTimeout(int startId, int fgsType) {
        stop();
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
        if (instance == this) {
            instance = null;
        }
    }

    private void update(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastUpdateAt < UPDATE_INTERVAL_MS) {
            return;
        }
        lastUpdateAt = now;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            try {
                nm.notify(NOTIFICATION_ID, build());
            } catch (SecurityException ignored) {
                // 没有通知权限：前台服务仍在，只是不显示
            }
        }
    }

    @NonNull
    private Notification build() {
        Summary s = Summary.of(TaskStore.get().all());
        String title;
        if (s.running > 0) {
            title = getString(R.string.notify_downloading, s.running + s.queued);
        } else if (s.waiting > 0) {
            title = getString(R.string.notify_waiting_network, s.waiting);
        } else {
            title = getString(R.string.notify_queued, s.queued);
        }
        StringBuilder text = new StringBuilder();
        if (s.current != null) {
            text.append(s.current.title);
        }
        if (s.speedBps > 0) {
            text.append(text.length() > 0 ? " · " : "").append(Bytes.speed(s.speedBps));
        }
        Intent open = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_TASKS)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pauseAll = PendingIntent.getService(this, 1,
                new Intent(this, DownloadService.class).setAction(ACTION_PAUSE_ALL),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notify_download)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(R.drawable.ic_action_pause, getString(R.string.notify_pause_all), pauseAll);
        if (s.running > 0) {
            if (s.progress >= 0) {
                b.setProgress(1000, Math.round(s.progress * 1000), false);
                b.setSubText(Math.round(s.progress * 100) + "%");
            } else {
                b.setProgress(0, 0, true);
            }
        }
        return b.build();
    }

    private static boolean hasWork() {
        for (DownloadTask t : TaskStore.get().all()) {
            if (t.status().isActive()) {
                return true;
            }
        }
        return false;
    }

    /** 通知用的聚合数据。 */
    static final class Summary {
        int running;
        int queued;
        int waiting;
        long speedBps;
        /** 运行中任务的平均进度；都算不出时为 -1。 */
        float progress = -1f;
        @Nullable
        DownloadTask current;

        static Summary of(@NonNull List<DownloadTask> tasks) {
            Summary s = new Summary();
            float sum = 0f;
            int known = 0;
            for (DownloadTask t : tasks) {
                switch (t.status()) {
                    case RUNNING -> {
                        s.running++;
                        s.speedBps += t.speedBps();
                        if (s.current == null) {
                            s.current = t;
                        }
                        float p = t.progress();
                        if (p >= 0) {
                            sum += p;
                            known++;
                        }
                    }
                    case QUEUED -> s.queued++;
                    case WAITING_NETWORK -> s.waiting++;
                    default -> {
                    }
                }
            }
            if (known > 0) {
                s.progress = sum / known;
            }
            return s;
        }
    }
}
