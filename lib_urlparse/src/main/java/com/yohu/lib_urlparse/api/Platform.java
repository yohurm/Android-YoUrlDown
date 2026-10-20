package com.yohu.lib_urlparse.api;

import java.util.Locale;

/**
 * 支持的平台。
 *
 * <p>{@link #id()} 是**稳定不变的 ASCII 标识**，会被用作下载目录名、日志字段与诊断文件名；
 * {@link #displayName()} 只用于界面展示，可以随时改中文而不影响任何落盘产物。
 * 这条约定是从一次事故里来的：早期用显示名当目录名，结果同一平台的产物被拆进两个目录。
 */
public enum Platform {

    DOUYIN("douyin", "抖音"),
    WECHAT_CHANNELS("wxSph", "微信视频号"),
    WECHAT_MP("wxMp", "微信公众号"),
    TOUTIAO("toutiao", "今日头条"),
    QUTOUTIAO("qutoutiao", "趣头条"),
    XIGUA("ixigua", "西瓜视频"),
    KUAISHOU("kuaishou", "快手"),
    WEISHI("weishi", "微视"),
    WEIBO("weibo", "微博"),
    BILIBILI("bilibili", "哔哩哔哩"),
    XIAOHONGSHU("xiaohongshu", "小红书"),
    PIPIXIA("pipixia", "皮皮虾"),
    INSTAGRAM("instagram", "Instagram"),
    YOUTUBE("youtube", "YouTube"),
    UNKNOWN("unknown", "未知");

    private final String id;
    private final String displayName;

    Platform(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** 稳定标识（ASCII，用于路径与日志）。 */
    public String id() {
        return id;
    }

    /** 展示名。 */
    public String displayName() {
        return displayName;
    }

    /** 按 {@link #id()} 查找；未知返回 {@link #UNKNOWN}。 */
    public static Platform fromId(String id) {
        if (id != null) {
            String key = id.trim().toLowerCase(Locale.ROOT);
            for (Platform p : values()) {
                if (p.id.toLowerCase(Locale.ROOT).equals(key)) {
                    return p;
                }
            }
        }
        return UNKNOWN;
    }
}
