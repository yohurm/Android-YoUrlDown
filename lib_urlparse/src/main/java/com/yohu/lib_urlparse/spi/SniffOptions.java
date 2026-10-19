package com.yohu.lib_urlparse.spi;

/** 页面嗅探选项。 */
public final class SniffOptions {

    private final boolean galleryMode;
    private final int maxRequests;
    private final boolean allowImages;

    private SniffOptions(Builder b) {
        this.galleryMode = b.galleryMode;
        this.maxRequests = b.maxRequests;
        this.allowImages = b.allowImages;
    }

    /** 默认：视频优先，允许图片。 */
    public static SniffOptions defaults() {
        return new Builder().build();
    }

    /**
     * 图集模式：显式打开图片采集。
     *
     * <p>为什么需要单独一个模式：默认策略会排除图片（很多站点的封面/头像也是图片，
     * 不加区分会把它们当成作品）。但图集类作品的主体恰好是图片，此时必须打开。
     */
    public static SniffOptions gallery() {
        return new Builder().galleryMode(true).allowImages(true).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 是否图集模式。 */
    public boolean galleryMode() {
        return galleryMode;
    }

    /** 最多观察多少个网络请求（防止 SPA 无限请求）。 */
    public int maxRequests() {
        return maxRequests;
    }

    /** 是否允许把图片当作媒体。 */
    public boolean allowImages() {
        return allowImages;
    }

    /** 构造器。 */
    public static final class Builder {
        private boolean galleryMode;
        private int maxRequests = 120;
        private boolean allowImages = true;

        public Builder galleryMode(boolean v) {
            this.galleryMode = v;
            return this;
        }

        public Builder maxRequests(int v) {
            this.maxRequests = v > 0 ? v : 120;
            return this;
        }

        public Builder allowImages(boolean v) {
            this.allowImages = v;
            return this;
        }

        public SniffOptions build() {
            return new SniffOptions(this);
        }
    }
}
