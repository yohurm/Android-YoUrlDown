package com.yohu.lib_urlparse.core;

import com.yohu.lib_urlparse.api.FailureReason;
import com.yohu.lib_urlparse.api.ParseFailure;
import com.yohu.lib_urlparse.api.Platform;
import com.yohu.lib_urlparse.api.UrlParseRequest;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.lib_urlparse.spi.ParseContext;
import com.yohu.lib_urlparse.spi.ParseProvider;
import com.yohu.lib_urlparse.spi.ProviderPriority;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** {@link ProviderRegistry} 的测试：排序稳定、单个通道异常不拖垮整次解析。 */
public class ProviderRegistryTest {

    /** 只关心声明的假通道。 */
    static final class NamedProvider implements ParseProvider {
        private final String channel;
        private final ProviderPriority priority;
        private final boolean throwsInCanHandle;

        NamedProvider(String channel, ProviderPriority priority) {
            this(channel, priority, false);
        }

        NamedProvider(String channel, ProviderPriority priority, boolean throwsInCanHandle) {
            this.channel = channel;
            this.priority = priority;
            this.throwsInCanHandle = throwsInCanHandle;
        }

        @Override
        public String channel() {
            return channel;
        }

        @Override
        public ProviderPriority priority() {
            return priority;
        }

        @Override
        public boolean canHandle(ParseContext context, UrlParseRequest request) {
            if (throwsInCanHandle) {
                throw new IllegalStateException("canHandle exploded");
            }
            return true;
        }

        @Override
        public UrlParseResult parse(ParseContext context, UrlParseRequest request)
                throws ParseFailure {
            throw new ParseFailure(FailureReason.NO_MEDIA_FOUND, channel, "not implemented in test");
        }
    }

    private static UrlParseRequest request() {
        return UrlParseRequest.builder("https://a.test/x").build();
    }

    private static List<String> channelNames(List<ParseProvider> providers) {
        List<String> names = new ArrayList<>();
        for (ParseProvider p : providers) {
            names.add(p.channel());
        }
        return names;
    }

    @Test
    public void sortsByPriorityThenKeepsRegistrationOrder() {
        ProviderRegistry registry = new ProviderRegistry()
                .register(new NamedProvider("s1", ProviderPriority.SNIFFER))
                .register(new NamedProvider("w1", ProviderPriority.DEDICATED_WEB))
                .register(new NamedProvider("a1", ProviderPriority.DEDICATED_API))
                .register(new NamedProvider("w2", ProviderPriority.DEDICATED_WEB))
                .register(new NamedProvider("c1", ProviderPriority.CLOUD));

        List<String> names = channelNames(registry.candidates(null, request()));
        assertEquals(java.util.Arrays.asList("w1", "w2", "a1", "c1", "s1"), names);
    }

    @Test
    public void sameProviderInstanceRegisteredTwiceIsIgnored() {
        NamedProvider p = new NamedProvider("dup", ProviderPriority.DEDICATED_WEB);
        ProviderRegistry registry = new ProviderRegistry().register(p).register(p);
        assertEquals(1, registry.size());
    }

    @Test
    public void canHandleExceptionSkipsOnlyThatProvider() {
        ProviderRegistry registry = new ProviderRegistry()
                .register(new NamedProvider("bad", ProviderPriority.DEDICATED_WEB, true))
                .register(new NamedProvider("good", ProviderPriority.DEDICATED_API));

        List<ParseProvider> candidates = registry.candidates(null, request());
        assertEquals(1, candidates.size());
        assertEquals("good", candidates.get(0).channel());
    }

    @Test
    public void registryIsReadOnlyFromOutside() {
        ProviderRegistry registry = new ProviderRegistry()
                .register(new NamedProvider("a", ProviderPriority.DEDICATED_WEB));
        List<ParseProvider> view = registry.providers();
        try {
            view.add(new NamedProvider("b", ProviderPriority.DEDICATED_WEB));
            // 若没有抛异常，至少要确认没真的加进去
        } catch (UnsupportedOperationException expected) {
            // 预期：不可变视图
        }
        assertEquals(1, registry.size());
    }

    @Test
    public void platformHintIsCarriedToProviders() {
        final Platform[] seen = new Platform[1];
        ParseProvider capture = new ParseProvider() {
            @Override
            public String channel() {
                return "cap";
            }

            @Override
            public boolean canHandle(ParseContext context, UrlParseRequest request) {
                seen[0] = request.platformHint();
                return true;
            }

            @Override
            public UrlParseResult parse(ParseContext context, UrlParseRequest request)
                    throws ParseFailure {
                throw new ParseFailure(FailureReason.NO_MEDIA_FOUND, "cap", "unused");
            }
        };
        ProviderRegistry registry = new ProviderRegistry().register(capture);
        registry.candidates(null,
                UrlParseRequest.builder("https://x.test/1").platformHint(Platform.DOUYIN).build());
        assertEquals(Platform.DOUYIN, seen[0]);
    }
}
