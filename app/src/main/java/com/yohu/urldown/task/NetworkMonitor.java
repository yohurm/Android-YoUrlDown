package com.yohu.urldown.task;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 默认网络的在线 / 计费状态，变化时在主线程回调。
 *
 * <p>“仅 Wi‑Fi”按<b>不计费</b>判断（{@code NET_CAPABILITY_NOT_METERED}）：
 * 用户关心的是“会不会花流量”，有线网络、不计费的热点也算。
 * 状态拿不到（例如系统服务不可用）时按在线、不计费处理，宁可多试一次也不卡住队列。
 */
final class NetworkMonitor {

    interface Listener {
        @MainThread
        void onNetworkChanged();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable
    private final ConnectivityManager cm;
    private volatile boolean online = true;
    private volatile boolean unmetered = true;

    NetworkMonitor(@NonNull Context context, @NonNull Listener listener) {
        cm = (ConnectivityManager) context.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return;
        }
        read(cm.getActiveNetwork() == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork()),
                cm.getActiveNetwork() != null);
        try {
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(@NonNull Network network,
                                                  @NonNull NetworkCapabilities caps) {
                    update(caps, true, listener);
                }

                @Override
                public void onLost(@NonNull Network network) {
                    update(null, false, listener);
                }
            });
        } catch (RuntimeException ignored) {
            // 回调数量超限等极端情况：保持初始状态，断网时由下载失败兜底
        }
    }

    boolean isOnline() {
        return online;
    }

    boolean isUnmetered() {
        return unmetered;
    }

    private void update(@Nullable NetworkCapabilities caps, boolean present, Listener listener) {
        boolean wasOnline = online;
        boolean wasUnmetered = unmetered;
        read(caps, present);
        if (wasOnline != online || wasUnmetered != unmetered) {
            main.post(listener::onNetworkChanged);
        }
    }

    private void read(@Nullable NetworkCapabilities caps, boolean present) {
        if (!present) {
            online = false;
            unmetered = false;
            return;
        }
        if (caps == null) {
            online = true;
            unmetered = true;
            return;
        }
        online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }
}
