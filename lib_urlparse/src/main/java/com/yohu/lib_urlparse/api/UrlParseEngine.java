package com.yohu.lib_urlparse.api;

import com.yohu.lib_urlparse.core.ParseDispatcher;
import com.yohu.lib_urlparse.core.ProviderRegistry;
import com.yohu.lib_urlparse.core.RequestResolver;
import com.yohu.lib_urlparse.spi.CredentialProvider;
import com.yohu.lib_urlparse.spi.EngineLogger;
import com.yohu.lib_urlparse.spi.EngineOptions;
import com.yohu.lib_urlparse.spi.HttpClient;
import com.yohu.lib_urlparse.spi.ParseProvider;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 解析引擎门面。
 *
 * <p>这是库的**唯一入口**。调用方只需要关心三件事：装配（builder）、解析（parse）、
 * 看尝试链（{@link UrlParseResult#trace()} / {@link #lastTrace()}）。
 *
 * <p>线程模型：所有方法**同步、在调用线程执行**，引擎内部不创建线程。
 * 有线程亲和性的注入服务（{@link com.yohu.lib_urlparse.spi.JsEvaluator}、
 * {@link com.yohu.lib_urlparse.spi.PageSniffer}）由调用方在自己线程上创建与调用。
 */
public final class UrlParseEngine {

    private final ProviderRegistry registry;
    private final EngineOptions options;
    private final EngineLogger logger;
    private final HttpClient http;
    private final Map<Class<?>, Object> services;

    private volatile ParseTrace lastTrace;

    private UrlParseEngine(Builder b) {
        this.registry = b.registry;
        this.options = b.options.build();
        this.logger = b.logger == null ? EngineLogger.NONE : b.logger;
        this.http = b.http;
        this.services = new LinkedHashMap<>(b.services);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 注册表（便于按需增删通道）。 */
    public ProviderRegistry registry() {
        return registry;
    }

    /**
     * 解析。
     *
     * @throws ParseFailure 失败（携带 reason / channel / userHint）
     */
    public UrlParseResult parse(UrlParseRequest request) throws ParseFailure {
        if (request == null) {
            throw new ParseFailure(FailureReason.INTERNAL, "", "request is null");
        }
        String resolvedUrl = RequestResolver.extractUrl(request.rawInput());

        EngineOptions effective = options;
        if (request.timeoutMs() > 0 && request.timeoutMs() != options.timeoutMs()) {
            effective = EngineOptions.builder()
                    .timeoutMs(request.timeoutMs())
                    .userAgentOverride(options.userAgentOverride())
                    .credentials(options.credentials())
                    .cancelToken(options.cancelToken())
                    .build();
        }

        ParseDispatcher dispatcher = new ParseDispatcher(
                registry, effective, logger, http, services);
        try {
            UrlParseResult result = dispatcher.dispatch(
                    request, resolvedUrl, request.platformHint());
            lastTrace = result.trace();
            return result;
        } catch (ParseFailure f) {
            lastTrace = ParseTrace.builder().resolvedUrl(resolvedUrl)
                    .attempt(f.channel().isEmpty() ? "(none)" : f.channel(),
                            ParseTrace.Verdict.FAILED, f.reason(), 0L,
                            f.getMessage())
                    .build();
            throw f;
        }
    }

    /** 最近一次解析的尝试链（成功或失败都有）。 */
    public ParseTrace lastTrace() {
        return lastTrace;
    }

    /** 构造器。 */
    public static final class Builder {
        private final ProviderRegistry registry = new ProviderRegistry();
        private final EngineOptions.Builder options = EngineOptions.builder();
        private EngineLogger logger = EngineLogger.NONE;
        private HttpClient http;
        private final Map<Class<?>, Object> services = new LinkedHashMap<>();

        public Builder registry(ProviderRegistry v) {
            if (v != null) {
                for (ParseProvider p : v.providers()) {
                    this.registry.register(p);
                }
            }
            return this;
        }

        /** 注册一个通道。 */
        public Builder provider(ParseProvider v) {
            this.registry.register(v);
            return this;
        }

        /** 注入 HTTP 客户端。 */
        public Builder httpClient(HttpClient v) {
            this.http = v;
            return this;
        }

        public Builder logger(EngineLogger v) {
            this.logger = v == null ? EngineLogger.NONE : v;
            return this;
        }

        public Builder timeoutMs(long v) {
            this.options.timeoutMs(v);
            return this;
        }

        public Builder userAgent(String v) {
            this.options.userAgentOverride(v);
            return this;
        }

        public Builder credentials(CredentialProvider v) {
            this.options.credentials(v);
            return this;
        }

        public Builder cancelToken(com.yohu.lib_urlparse.spi.CancelToken v) {
            this.options.cancelToken(v);
            return this;
        }

        /** 注入服务实现（{@code JsEvaluator} / {@code PageSniffer} / {@code MediaMuxer}）。 */
        public <T> Builder service(Class<T> type, T impl) {
            if (type != null) {
                if (impl == null) {
                    services.remove(type);
                } else {
                    services.put(type, impl);
                }
            }
            return this;
        }

        public UrlParseEngine build() {
            if (http == null) {
                throw new IllegalStateException(
                        "HttpClient is required: pass one via Builder.httpClient(...)");
            }
            return new UrlParseEngine(this);
        }
    }
}
