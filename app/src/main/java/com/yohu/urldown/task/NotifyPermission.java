package com.yohu.urldown.task;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;

/**
 * 下载进度通知的运行时权限（Android 13+）。
 *
 * <p>只在用户第一次点“下载全部”时问一次：此时用户知道为什么要通知。拒绝后不再追问，
 * 下载照常进行，只是没有通知栏进度；以后可在系统设置里打开。
 */
public final class NotifyPermission {

    private static final String PREFS = "notify_permission";
    private static final String KEY_ASKED = "asked";
    private static final int REQUEST_CODE = 0x4e01;

    private NotifyPermission() {
    }

    public static void askOnce(@NonNull Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_ASKED, false)) {
            return;
        }
        prefs.edit().putBoolean(KEY_ASKED, true).apply();
        activity.requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_CODE);
    }
}
