package com.yohu.lib_urlparse.spi;

/**
 * 通道优先级。
 *
 * <p>显式优先级是为了修掉一类真实缺陷：旧实现只依赖"注册顺序 + canHandle"，
 * 结果通用兜底通道有时抢在专用通道前面，把本可直接解析的链接拖到慢路径甚至失败。
 *
 * <p>数值越小越先尝试。
 */
public enum ProviderPriority {

    /** 专用通道、基于公开页面/接口直接解析（最可靠，先试）。 */
    DEDICATED_WEB(10),

    /** 专用通道、基于公开 API（可能需要签名/会话，次之）。 */
    DEDICATED_API(20),

    /** 需要调用方自备端点或凭据的云通道（有配置才可能成功）。 */
    CLOUD(30),

    /** 通用兜底（WebView 嗅探），最慢、最不可控，永远最后。 */
    SNIFFER(90);

    private final int rank;

    ProviderPriority(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
}
