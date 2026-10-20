package com.yohu.urldown.util;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * 体积、速度、时长的固定格式。不用系统 {@code Formatter} / {@code DateUtils}：
 * 它们跟随 JVM 语言，会冒出 "MB" 小数位不一、"0 minutes" 这类混搭。
 */
public final class Bytes {

    private Bytes() {
    }

    /** 1023 B / 12 KB / 3.4 MB / 1.25 GB。MB 留一位小数，GB 两位。 */
    @NonNull
    public static String size(long b) {
        if (b < 1024) {
            return Math.max(0, b) + " B";
        }
        if (b < 1024 * 1024) {
            return (b / 1024) + " KB";
        }
        if (b < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", b / 1048576.0);
        }
        return String.format(Locale.ROOT, "%.2f GB", b / 1073741824.0);
    }

    /** “3.2 / 7.4 MB”：单位相同时只写一次。 */
    @NonNull
    public static String pair(long done, long total) {
        String a = size(done);
        String b = size(total);
        int sa = a.lastIndexOf(' ');
        int sb = b.lastIndexOf(' ');
        if (sa > 0 && sb > 0 && a.substring(sa).equals(b.substring(sb))) {
            return a.substring(0, sa) + " / " + b;
        }
        return a + " / " + b;
    }

    @NonNull
    public static String speed(long bps) {
        return size(bps) + "/s";
    }

    /** 剩余时长：“45 秒” / “3 分钟” / “1 小时 20 分”。 */
    @NonNull
    public static String remaining(long ms) {
        long s = Math.max(1, (ms + 999) / 1000);
        if (s < 60) {
            return s + " 秒";
        }
        long m = (s + 30) / 60;
        if (m < 60) {
            return m + " 分钟";
        }
        long h = m / 60;
        long rest = m % 60;
        return rest == 0 ? h + " 小时" : h + " 小时 " + rest + " 分";
    }
}
