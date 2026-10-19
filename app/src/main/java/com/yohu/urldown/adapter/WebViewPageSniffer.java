package com.yohu.urldown.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.yohu.lib_urlparse.sniff.MediaExtensions;
import com.yohu.lib_urlparse.spi.PageSniffer;
import com.yohu.lib_urlparse.spi.SniffOptions;
import com.yohu.lib_urlparse.spi.SniffResult;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * {@link PageSniffer} 的 WebView 实现：加载页面并**观察**媒体请求。
 *
 * <p>只在 {@code shouldInterceptRequest} 里记录，永远返回 {@code null}（不拦截、不改请求）——
 * 我们只想知道页面请求了什么，不能影响页面行为，否则拿到的结果不真实。
 *
 * <p>同样的线程模型：核心在后台线程调用，WebView 必须在主线程；用 latch 让后台等，
 * 主线程不阻塞。
 */
public final class WebViewPageSniffer implements PageSniffer {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/139.0.0.0 Mobile Safari/537.36";

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile WebView webView;

    public WebViewPageSniffer(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public SniffResult sniff(String pageUrl, long timeoutMs, SniffOptions options)
            throws Exception {
        final SniffOptions opts = options == null ? SniffOptions.defaults() : options;
        final Map<String, SniffResult.Candidate> seen = new LinkedHashMap<>();
        final CountDownLatch loaded = new CountDownLatch(1);
        final long deadline = System.currentTimeMillis() + Math.max(2_000L, timeoutMs);

        main.post(new Runnable() {
            @Override
            public void run() {
                WebView wv = new WebView(appContext);
                wv.getSettings().setJavaScriptEnabled(true);
                wv.getSettings().setDomStorageEnabled(true);
                wv.getSettings().setUserAgentString(UA);
                wv.setWebViewClient(new WebViewClient() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView view,
                                                                      WebResourceRequest request) {
                        if (request != null && request.getUrl() != null) {
                            String url = request.getUrl().toString();
                            String mime = request.getRequestHeaders() == null
                                    ? null : request.getRequestHeaders().get("Accept");
                            if (isCandidate(url, mime, opts)) {
                                seen.put(url, new SniffResult.Candidate(url, mime, null, -1L));
                            }
                        }
                        // 永不拦截：只观察，不改变页面行为
                        return null;
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        loaded.countDown();
                    }
                });
                webView = wv;
                wv.loadUrl(pageUrl);
            }
        });

        try {
            // 页面加载完成只是起点：媒体请求常在加载后若干百毫秒才发出，需要再等一个观察窗口。
            loaded.await(Math.max(1_000L, timeoutMs), TimeUnit.MILLISECONDS);
            long settle = Math.max(500L, Math.min(3_000L, deadline - System.currentTimeMillis()));
            Thread.sleep(settle);
        } finally {
            collected = seen;
        }

        SniffResult.Builder out = SniffResult.builder()
                .finalUrl(pageUrl)
                .requestCount(seen.size())
                .timedOut(System.currentTimeMillis() >= deadline)
                .candidates(new java.util.ArrayList<>(seen.values()));
        return out.build();
    }

    /** 只为满足"release 后不再引用旧结果"的语义而保留的字段（不参与业务判断）。 */
    private volatile Map<String, SniffResult.Candidate> collected;

    private static boolean isCandidate(String url, String acceptHint, SniffOptions opts) {
        if (!url.startsWith("http")) {
            return false;
        }
        if (MediaExtensions.looksLikePlaylist(url) || MediaExtensions.looksLikeVideo(url)) {
            return true;
        }
        if (MediaExtensions.looksLikeAudio(url)) {
            return true;
        }
        if (opts.allowImages() || opts.galleryMode()) {
            return MediaExtensions.looksLikeImage(url);
        }
        return false;
    }

    @Override
    public void release() {
        final WebView wv = webView;
        webView = null;
        if (wv != null) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        wv.stopLoading();
                        wv.loadUrl("about:blank");
                        wv.destroy();
                    } catch (Throwable ignored) {
                        // 销毁失败不影响业务
                    }
                }
            });
        }
    }
}
