package com.yohu.lib_urlparse.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一次成功解析的产物。 */
public final class UrlParseResult {

    private final Platform platform;
    private final String channel;
    private final String workId;
    private final String title;
    private final String sourceUrl;
    private final Author author;
    private final List<MediaAsset> assets;
    private final Map<String, String> extras;
    private final ParseTrace trace;

    private UrlParseResult(Builder b) {
        this.platform = b.platform;
        this.channel = b.channel;
        this.workId = b.workId;
        this.title = b.title;
        this.sourceUrl = b.sourceUrl;
        this.author = b.author == null ? Author.empty() : b.author;
        this.assets = Collections.unmodifiableList(new ArrayList<>(b.assets));
        this.extras = Collections.unmodifiableMap(new LinkedHashMap<>(b.extras));
        this.trace = b.trace;
    }

    public static Builder builder(Platform platform) {
        return new Builder(platform);
    }

    public Platform platform() {
        return platform;
    }

    /** 产出结果的通道名，例如 {@code bilibili.api}。 */
    public String channel() {
        return channel;
    }

    /** 作品 ID；可能为 {@code null}。 */
    public String workId() {
        return workId;
    }

    public String title() {
        return title;
    }

    /** 原始来源 URL（短链展开后的）。 */
    public String sourceUrl() {
        return sourceUrl;
    }

    public Author author() {
        return author;
    }

    /** 全部媒体条目（含封面）。 */
    public List<MediaAsset> assets() {
        return assets;
    }

    /** 按类型过滤。 */
    public List<MediaAsset> assets(MediaAsset.Kind kind) {
        List<MediaAsset> out = new ArrayList<>();
        for (MediaAsset a : assets) {
            if (a.kind() == kind) {
                out.add(a);
            }
        }
        return out;
    }

    /** 可下载的媒体（排除封面）。 */
    public List<MediaAsset> downloadable() {
        List<MediaAsset> out = new ArrayList<>();
        for (MediaAsset a : assets) {
            if (a.kind() != MediaAsset.Kind.COVER) {
                out.add(a);
            }
        }
        return out;
    }

    /** 附加字段（通道特有信息，供调试与展示）。 */
    public Map<String, String> extras() {
        return extras;
    }

    /** 本次解析的尝试链。 */
    public ParseTrace trace() {
        return trace;
    }

    @Override
    public String toString() {
        return platform.id() + '/' + channel + " assets=" + assets.size()
                + (workId == null ? "" : " workId=" + workId);
    }

    /** 构造器。 */
    public static final class Builder {
        private final Platform platform;
        private String channel = "";
        private String workId;
        private String title;
        private String sourceUrl;
        private Author author;
        private final List<MediaAsset> assets = new ArrayList<>();
        private final Map<String, String> extras = new LinkedHashMap<>();
        private ParseTrace trace;

        Builder(Platform platform) {
            this.platform = platform == null ? Platform.UNKNOWN : platform;
        }

        public Builder channel(String v) {
            this.channel = v == null ? "" : v;
            return this;
        }

        public Builder workId(String v) {
            this.workId = v;
            return this;
        }

        public Builder title(String v) {
            this.title = v;
            return this;
        }

        public Builder sourceUrl(String v) {
            this.sourceUrl = v;
            return this;
        }

        public Builder author(Author v) {
            this.author = v;
            return this;
        }

        public Builder asset(MediaAsset v) {
            if (v != null) {
                this.assets.add(v);
            }
            return this;
        }

        public Builder assets(List<MediaAsset> v) {
            if (v != null) {
                this.assets.addAll(v);
            }
            return this;
        }

        public Builder extra(String key, String value) {
            if (key != null && value != null) {
                this.extras.put(key, value);
            }
            return this;
        }

        public Builder trace(ParseTrace v) {
            this.trace = v;
            return this;
        }

        public UrlParseResult build() {
            return new UrlParseResult(this);
        }
    }
}
