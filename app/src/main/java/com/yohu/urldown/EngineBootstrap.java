package com.yohu.urldown;

import android.content.Context;

import com.yohu.lib_urlparse.api.UrlParseEngine;
import com.yohu.lib_urlparse.fetch.DefaultHttpClient;
import com.yohu.lib_urlparse.provider.bilibili.BilibiliProvider;
import com.yohu.lib_urlparse.provider.douyin.DouyinWebProvider;
import com.yohu.lib_urlparse.provider.douyin.DouyinWebviewProvider;
import com.yohu.lib_urlparse.provider.instagram.InstagramEmbedProvider;
import com.yohu.lib_urlparse.provider.sniffer.SnifferProvider;
import com.yohu.lib_urlparse.provider.toutiao.ToutiaoWebProvider;
import com.yohu.lib_urlparse.provider.weibo.WeiboProvider;
import com.yohu.lib_urlparse.provider.xiaohongshu.XiaohongshuProvider;
import com.yohu.lib_urlparse.spi.JsEvaluator;
import com.yohu.lib_urlparse.spi.PageSniffer;

import com.yohu.urldown.adapter.WebViewJsEvaluator;
import com.yohu.urldown.adapter.WebViewPageSniffer;

/**
 * 引擎装配：把纯 JVM 的核心与 Android 侧的三个实现接起来。
 *
 * <p>这是整个工程**唯一**知道"哪些 provider 存在、WebView 从哪来"的地方。
 * 核心与通道都不依赖 Android，因此装配逻辑坏掉时，只有这里需要改。
 *
 * <p>注册顺序有意义：同优先级的通道按注册顺序尝试，所以
 * {@code douyin.webpage} 必须排在 {@code douyin.webview.js} 之前——
 * 网页直解更快，不需要创建 WebView。
 */
public final class EngineBootstrap {

    private static volatile UrlParseEngine shared;

    private EngineBootstrap() {
    }

    /** 进程内共享一个引擎（HTTP Cookie 罐与连接复用都随引擎走）。 */
    public static UrlParseEngine engine(Context context) {
        UrlParseEngine e = shared;
        if (e != null) {
            return e;
        }
        synchronized (EngineBootstrap.class) {
            if (shared == null) {
                shared = build(context.getApplicationContext());
            }
            return shared;
        }
    }

    private static UrlParseEngine build(Context appContext) {
        return UrlParseEngine.builder()
                .httpClient(new DefaultHttpClient())
                // 专用直解通道：先网页后 webview，最后才是通用嗅探兜底
                .provider(new BilibiliProvider())
                .provider(new WeiboProvider())
                .provider(new XiaohongshuProvider())
                .provider(new ToutiaoWebProvider())
                .provider(new DouyinWebProvider())
                .provider(new DouyinWebviewProvider())
                .provider(new InstagramEmbedProvider())
                .provider(new SnifferProvider())
                .service(JsEvaluator.class, new WebViewJsEvaluator(appContext))
                .service(PageSniffer.class, new WebViewPageSniffer(appContext))
                .timeoutMs(30_000L)
                .build();
    }
}
