package com.yohu.lib_urlparse.core;

import com.yohu.lib_urlparse.api.UrlParseRequest;
import com.yohu.lib_urlparse.spi.ParseProvider;
import com.yohu.lib_urlparse.spi.ParseContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 通道注册表：收集候选并按优先级排序。
 *
 * <p>排序规则：优先级 rank 升序；同优先级按**注册顺序**（保持稳定，避免同一批通道
 * 每次运行的顺序都不同——那会让"今天能解析、明天不能"变成随机现象）。
 *
 * <p>本类不创建线程、不做 IO；{@code canHandle} 的纯判断约定由通道实现保证。
 */
public final class ProviderRegistry {

    private final List<ParseProvider> providers = new ArrayList<>();

    /** 注册一个通道。同一实例重复注册会被忽略。 */
    public synchronized ProviderRegistry register(ParseProvider provider) {
        if (provider != null && provider.channel() != null && !providers.contains(provider)) {
            providers.add(provider);
        }
        return this;
    }

    /** 已注册通道（按注册顺序，只读）。 */
    public synchronized List<ParseProvider> providers() {
        return Collections.unmodifiableList(new ArrayList<>(providers));
    }

    /** 规模。 */
    public synchronized int size() {
        return providers.size();
    }

    /**
     * 本次请求的候选通道，按优先级排序。
     *
     * @param context     通道判断可用的上下文（不保证 resolvedUrl 已就绪）
     * @param request     请求
     * @param platformHint 平台提示
     */
    public synchronized List<ParseProvider> candidates(ParseContext context,
                                                       UrlParseRequest request) {
        List<ParseProvider> out = new ArrayList<>();
        for (ParseProvider p : providers) {
            boolean accept;
            try {
                accept = p.canHandle(context, request);
            } catch (RuntimeException e) {
                // 单个通道的判断异常不应让整次解析失败：跳过它，继续找别的通道。
                accept = false;
            }
            if (accept) {
                out.add(p);
            }
        }
        out.sort((a, b) -> {
            int byRank = Integer.compare(a.priority().rank(), b.priority().rank());
            if (byRank != 0) {
                return byRank;
            }
            // 同优先级保持注册顺序（List.sort 是稳定排序，这里显式返回 0 表达意图）
            return 0;
        });
        return out;
    }
}
