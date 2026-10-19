package com.yohu.lib_urlparse.api;

/** 作者信息。所有字段都可能缺失（很多页面不返回作者）。 */
public final class Author {

    private final String nickname;
    private final String uid;
    private final String avatarUrl;

    private Author(Builder b) {
        this.nickname = b.nickname;
        this.uid = b.uid;
        this.avatarUrl = b.avatarUrl;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 空的作者对象（各字段均为 {@code null}）。 */
    public static Author empty() {
        return new Builder().build();
    }

    public String nickname() {
        return nickname;
    }

    public String uid() {
        return uid;
    }

    public String avatarUrl() {
        return avatarUrl;
    }

    /** 是否没有任何有效信息。 */
    public boolean isEmpty() {
        return nickname == null && uid == null && avatarUrl == null;
    }

    @Override
    public String toString() {
        return "Author{nickname=" + nickname + ", uid=" + uid + '}';
    }

    /** 构造器。 */
    public static final class Builder {
        private String nickname;
        private String uid;
        private String avatarUrl;

        public Builder nickname(String v) {
            this.nickname = v;
            return this;
        }

        public Builder uid(String v) {
            this.uid = v;
            return this;
        }

        public Builder avatarUrl(String v) {
            this.avatarUrl = v;
            return this;
        }

        public Author build() {
            return new Author(this);
        }
    }
}
