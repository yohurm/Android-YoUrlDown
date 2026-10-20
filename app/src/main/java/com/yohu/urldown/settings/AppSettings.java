package com.yohu.urldown.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;

import com.yohu.youi.api.theme.ColorMode;
import com.yohu.youi.api.theme.YoTheme;

/**
 * 应用设置。只存真正生效的项，没有接上功能的开关不放进来。
 */
public final class AppSettings {

    private static final String PREFS = "app_settings";
    private static final String KEY_COLOR_MODE = "color_mode";
    private static final String KEY_CLIPBOARD = "clipboard_detect";
    private static final String KEY_WIFI_ONLY = "download_wifi_only";
    private static final String KEY_PARALLEL = "download_parallel";

    /** 同时下载数的可选范围。 */
    public static final int MIN_PARALLEL = 1;
    public static final int MAX_PARALLEL = 3;
    public static final int DEFAULT_PARALLEL = 2;

    private final SharedPreferences prefs;

    private AppSettings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @NonNull
    public static AppSettings of(@NonNull Context context) {
        return new AppSettings(context);
    }

    @NonNull
    public ColorMode colorMode() {
        String name = prefs.getString(KEY_COLOR_MODE, ColorMode.FOLLOW_SYSTEM.name());
        try {
            return ColorMode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return ColorMode.FOLLOW_SYSTEM;
        }
    }

    public void setColorMode(@NonNull ColorMode mode) {
        prefs.edit().putString(KEY_COLOR_MODE, mode.name()).apply();
        applyColorMode(mode);
    }

    /** 打开应用时是否读取剪贴板里的链接填入输入框。 */
    public boolean clipboardDetect() {
        return prefs.getBoolean(KEY_CLIPBOARD, true);
    }

    public void setClipboardDetect(boolean enabled) {
        prefs.edit().putBoolean(KEY_CLIPBOARD, enabled).apply();
    }

    /** 只在不计费网络（Wi‑Fi 等）下载；移动网络下任务等待。 */
    public boolean wifiOnly() {
        return prefs.getBoolean(KEY_WIFI_ONLY, false);
    }

    public void setWifiOnly(boolean enabled) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, enabled).apply();
    }

    /** 同时下载几个任务（1–3）。 */
    public int maxParallel() {
        int n = prefs.getInt(KEY_PARALLEL, DEFAULT_PARALLEL);
        return Math.max(MIN_PARALLEL, Math.min(MAX_PARALLEL, n));
    }

    public void setMaxParallel(int n) {
        prefs.edit().putInt(KEY_PARALLEL, Math.max(MIN_PARALLEL, Math.min(MAX_PARALLEL, n))).apply();
    }

    /**
     * 同时设置 Kit 和 AppCompat 的深浅色：Kit 决定组件取色，AppCompat 决定窗口配置。
     * 两者不一致时，系统栏和组件会一深一浅。AppCompat 在模式变化时会自行重建 Activity。
     */
    public static void applyColorMode(@NonNull ColorMode mode) {
        YoTheme.setColorMode(mode);
        int night = switch (mode) {
            case LIGHT -> AppCompatDelegate.MODE_NIGHT_NO;
            case DARK -> AppCompatDelegate.MODE_NIGHT_YES;
            case FOLLOW_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        };
        if (AppCompatDelegate.getDefaultNightMode() != night) {
            AppCompatDelegate.setDefaultNightMode(night);
        }
    }
}
