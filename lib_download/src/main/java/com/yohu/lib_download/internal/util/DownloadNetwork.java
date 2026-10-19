package com.yohu.lib_download.internal.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;

/**
 * 下载网络状态工具：为 NetworkType 约束提供统一判定。
 */
public final class DownloadNetwork {

    private DownloadNetwork() {
    }

    public static boolean isOnWifi(@NonNull Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        Network active = cm.getActiveNetwork();
        if (active == null) {
            return false;
        }
        NetworkCapabilities capabilities = cm.getNetworkCapabilities(active);
        return capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }
}
