package com.yohu.lib_urlparse.core;

import com.yohu.lib_urlparse.api.FailureReason;
import com.yohu.lib_urlparse.api.ParseFailure;
import com.yohu.lib_urlparse.api.ParseTrace;
import com.yohu.lib_urlparse.api.Platform;
import com.yohu.lib_urlparse.api.UrlParseRequest;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.lib_urlparse.spi.EngineLogger;
import com.yohu.lib_urlparse.spi.EngineOptions;
import com.yohu.lib_urlparse.spi.HttpClient;
import com.yohu.lib_urlparse.spi.ParseContext;
import com.yohu.lib_urlparse.spi.ParseProvider;

import java.util.List;
import java.util.Map;

/**
 * 调度器：按优先级逐个尝试通道，并把每一次尝试记进 {@link ParseTrace}。
 *
 * <p>失败语义（刻意与"第一个失败就退出"不同）：
 * <ul>
 *   <li>除 {@link FailureReason#CANCELLED} 外，**都继续试下一个通道**。理由：一个通道失败
 *       往往只说明"这条路不通"（例如云通道没配凭据、某接口要验证码），别的通道可能通。</li>
 *   <li>全部失败后，抛出**优先级最高**的那个失败（它最接近调用方本来想走的路），
 *       而不是最后一个——否则会被最后的兜底通道掩盖真实原因。</li>
 *   <li>一个候选都没有时，抛 {@link FailureReason#UNRECOGNISED_INPUT}。</li>
 * </ul>
 *
 * <p>本类不创建线程、不做 IO（IO 由通道自己做）。
 */
public final class ParseDispatcher {

    private final ProviderRegistry registry;
    private final EngineOptions options;
    private final EngineLogger logger;
    private final HttpClient http;
    private final Map<Class<?>, Object> services;

    public ParseDispatcher(ProviderRegistry registry, EngineOptions options, EngineLogger logger,
                           HttpClient http, Map<Class<?>, Object> services) {
        this.registry = registry;
        this.options = options;
        this.logger = logger == null ? EngineLogger.NONE : logger;
        this.http = http;
        this.services = services;
    }

    /**
     * 执行一次解析。
     *
     * @param request      请求
     * @param resolvedUrl  已展开的最终 URL（可为 {@code null}）
     * @param platformHint 平台提示
     * @return 成功结果（其 trace 含完整尝试链）
     * @throws ParseFailure 全部候选失败
     */
    public UrlParseResult dispatch(UrlParseRequest request, String resolvedUrl, Platform platformHint)
            throws ParseFailure {
        ParseTrace.Builder trace = ParseTrace.builder().resolvedUrl(resolvedUrl);
        EngineContext ctx = new EngineContext(resolvedUrl, platformHint);

        List<ParseProvider> candidates = registry.candidates(ctx, request);
        if (candidates.isEmpty()) {
            throw new ParseFailure(FailureReason.UNRECOGNISED_INPUT, "",
                    "no provider accepted the input: " + abbreviate(request.rawInput()));
        }

        ParseFailure firstFailure = null;
        String firstFailureChannel = null;

        for (ParseProvider provider : candidates) {
            if (options.cancelToken().isCancelled()) {
                throw new ParseFailure(FailureReason.CANCELLED, provider.channel(),
                        "cancelled by caller");
            }
            String channel = provider.channel();
            long start = System.currentTimeMillis();
            try {
                logger.debug("dispatch", "trying channel: " + channel);
                UrlParseResult result = provider.parse(ctx, request);
                long elapsed = System.currentTimeMillis() - start;
                if (result == null) {
                    // 通道返回 null 属于实现缺陷，按失败处理而不是让调用方拿到 null。
                    throw new ParseFailure(FailureReason.INTERNAL, channel,
                            "provider returned null result");
                }
                trace.attempt(channel, ParseTrace.Verdict.OK, null, elapsed, null);
                logger.info("dispatch", channel + " -> ok in " + elapsed + "ms");
                return attachTrace(result, trace.build());
            } catch (ParseFailure f) {
                long elapsed = System.currentTimeMillis() - start;
                trace.attempt(channel, ParseTrace.Verdict.FAILED, f.reason(), elapsed,
                        abbreviate(f.getMessage()));
                logger.warn("dispatch", channel + " -> " + f.reason() + ": " + f.getMessage());
                if (f.reason() == FailureReason.CANCELLED) {
                    throw f;
                }
                if (firstFailure == null) {
                    firstFailure = f;
                    firstFailureChannel = channel;
                }
            } catch (RuntimeException e) {
                long elapsed = System.currentTimeMillis() - start;
                trace.attempt(channel, ParseTrace.Verdict.FAILED, FailureReason.INTERNAL, elapsed,
                        abbreviate(String.valueOf(e)));
                logger.warn("dispatch", channel + " -> internal error: " + e);
                if (firstFailure == null) {
                    firstFailure = new ParseFailure(FailureReason.INTERNAL, channel,
                            "provider crashed: " + e, e);
                    firstFailureChannel = channel;
                }
            }
        }

        ParseFailure f = firstFailure == null
                ? new ParseFailure(FailureReason.NO_MEDIA_FOUND, "",
                        "all providers declined without a reason")
                : firstFailure;
        logger.warn("dispatch", "all " + candidates.size() + " channel(s) failed; first failure at "
                + firstFailureChannel + ": " + f.reason());
        throw f;
    }

    private static UrlParseResult attachTrace(UrlParseResult result, ParseTrace trace) {
        // 结果不可变；用 builder 复制一份并挂上 trace。
        UrlParseResult.Builder b = UrlParseResult.builder(result.platform())
                .channel(result.channel())
                .workId(result.workId())
                .title(result.title())
                .sourceUrl(result.sourceUrl())
                .author(result.author())
                .assets(result.assets())
                .trace(trace);
        for (Map.Entry<String, String> e : result.extras().entrySet()) {
            b.extra(e.getKey(), e.getValue());
        }
        return b.build();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String flat = s.replace('\n', ' ');
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
    }

    /** 通道可见的上下文实现。 */
    private final class EngineContext implements ParseContext {
        private final String resolvedUrl;
        private final Platform hint;

        EngineContext(String resolvedUrl, Platform hint) {
            this.resolvedUrl = resolvedUrl;
            this.hint = hint;
        }

        @Override
        public HttpClient http() {
            return http;
        }

        @Override
        public EngineLogger logger() {
            return logger;
        }

        @Override
        public String resolvedUrl() {
            return resolvedUrl;
        }

        @Override
        public EngineOptions options() {
            return options;
        }

        @Override
        public Platform platformHint() {
            return hint;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T service(Class<T> type) {
            Object impl = services == null ? null : services.get(type);
            return impl == null ? null : (T) impl;
        }
    }
}
