package com.yohu.lib_urlparse.spi;

import com.yohu.lib_urlparse.api.UrlParseRequest;

/**
 * 一个解析通道。新增平台 = 新增一个实现，不需要改引擎。
 *
 * <p>约定：
 * <ul>
 *   <li>{@link #canHandle} 必须是**纯判断**：不发网络请求、不启发式试错。
 *       一旦认领，引擎就会真的调用 {@link #parse}。</li>
 *   <li>{@link #parse} 失败必须抛 {@link com.yohu.lib_urlparse.api.ParseFailure}，
 *       并且 reason 要选准（它决定引擎是否换下一个通道）。</li>
 *   <li>实现必须**无状态或线程安全**：同一实例可能被并发调用。</li>
 * </ul>
 */
public interface ParseProvider {

    /** 通道名（稳定标识，用于日志与 trace），例如 {@code bilibili.api}。 */
    String channel();

    /** 优先级：数值小的先试。默认走专用 Web 通道。 */
    default ProviderPriority priority() {
        return ProviderPriority.DEDICATED_WEB;
    }

    /** 是否认领这次请求。纯判断，不做 IO。 */
    boolean canHandle(ParseContext context, UrlParseRequest request);

    /** 执行解析。 */
    com.yohu.lib_urlparse.api.UrlParseResult parse(ParseContext context, UrlParseRequest request)
            throws com.yohu.lib_urlparse.api.ParseFailure;
}
