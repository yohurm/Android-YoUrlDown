package com.yohu.urldown.ui.widget;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.yohu.lib_urlparse.api.Platform;
import com.yohu.urldown.R;

/**
 * 平台标识图标：品牌色圆角方块 + 白色图形（{@code res/drawable/ic_platform_*.xml}，
 * 由 {@code tools/icons/gen_platform_icons.py} 生成）。
 *
 * <p>任务卡片、解析结果、主页平台列表共用，保证同一平台到处长得一样。
 */
public final class PlatformMark {

    private PlatformMark() {
    }

    /**
     * 按平台 ID 取图标；ID 缺失（旧版本保存的任务）时按展示名再找一次，都找不到给通用链接图标。
     */
    @DrawableRes
    public static int iconOf(@Nullable String platformId, @Nullable String displayName) {
        Platform p = Platform.fromId(platformId);
        if (p == Platform.UNKNOWN && displayName != null) {
            for (Platform candidate : Platform.values()) {
                if (candidate.displayName().equals(displayName)) {
                    p = candidate;
                    break;
                }
            }
        }
        return iconOf(p);
    }

    @DrawableRes
    public static int iconOf(@NonNull Platform p) {
        return switch (p) {
            case DOUYIN -> R.drawable.ic_platform_douyin;
            case WECHAT_CHANNELS -> R.drawable.ic_platform_wxsph;
            case WECHAT_MP -> R.drawable.ic_platform_wxmp;
            case TOUTIAO -> R.drawable.ic_platform_toutiao;
            case QUTOUTIAO -> R.drawable.ic_platform_qutoutiao;
            case XIGUA -> R.drawable.ic_platform_ixigua;
            case KUAISHOU -> R.drawable.ic_platform_kuaishou;
            case WEISHI -> R.drawable.ic_platform_weishi;
            case WEIBO -> R.drawable.ic_platform_weibo;
            case BILIBILI -> R.drawable.ic_platform_bilibili;
            case XIAOHONGSHU -> R.drawable.ic_platform_xiaohongshu;
            case PIPIXIA -> R.drawable.ic_platform_pipixia;
            case INSTAGRAM -> R.drawable.ic_platform_instagram;
            case YOUTUBE -> R.drawable.ic_platform_youtube;
            case UNKNOWN -> R.drawable.ic_platform_unknown;
        };
    }
}
