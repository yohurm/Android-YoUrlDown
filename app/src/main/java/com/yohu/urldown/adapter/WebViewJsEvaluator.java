package com.yohu.urldown.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.yohu.lib_urlparse.spi.JsEvaluator;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link JsEvaluator} 的 WebView 实现。
 *
 * <p>线程模型：核心在**后台线程**调用本类，而 WebView 只能在主线程创建与使用。
 * 因此这里把每一步都 post 到主线程，再用 {@link CountDownLatch} 阻塞后台线程等待结果——
 * 阻塞的是调用方线程，不是主线程。
 *
 * <p>每次求值都新建一个 WebView、用完销毁：抖音这类页面会不断发请求，
 * 复用实例很容易把上一次的页面状态带进来，导致"结果偶尔才对"。
 * 代价是慢一点，但可预测。
 */
public final class WebViewJsEvaluator implements JsEvaluator {

    /** 桌面版 UA 更容易拿到完整页面数据。 */
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/139.0.0.0 Mobile Safari/537.36";

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile WebView webView;
    private volatile boolean released;

    public WebViewJsEvaluator(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public String loadAndEvaluate(String pageUrl, String script, long timeoutMs)
            throws Exception {
        if (released) {
            throw new IllegalStateException("evaluator already released");
        }
        final CountDownLatch loaded = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                WebView wv = new WebView(appContext);
                wv.getSettings().setJavaScriptEnabled(true);
                wv.getSettings().setDomStorageEnabled(true);
                wv.getSettings().setUserAgentString(UA);
                wv.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        loaded.countDown();
                    }
                });
                webView = wv;
                wv.loadUrl(pageUrl);
            }
        });

        if (!loaded.await(Math.max(1_000L, timeoutMs), TimeUnit.MILLISECONDS)) {
            throw new IOException("页面加载超时: " + pageUrl);
        }

        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>();
        final AtomicReference<String> error = new AtomicReference<>();
        final WebView target = webView;
        if (target == null) {
            throw new IOException("WebView 创建失败");
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    target.evaluateJavascript(script, value -> {
                        result.set(value);
                        done.countDown();
                    });
                } catch (Throwable t) {
                    error.set(String.valueOf(t));
                    done.countDown();
                }
            }
        });

        if (!done.await(Math.max(1_000L, timeoutMs), TimeUnit.MILLISECONDS)) {
            throw new IOException("脚本求值超时");
        }
        if (error.get() != null) {
            throw new IOException("脚本求值失败: " + error.get());
        }
        return unwrapJsonString(result.get());
    }

    @Override
    public String evaluate(String script, long timeoutMs) throws Exception {
        // 没有页面上下文时无法求值：如实失败，而不是返回一个空串让上层误判"成功了但没有数据"。
        throw new IOException("WebView 求值需要页面上下文，请使用 loadAndEvaluate");
    }

    @Override
    public void release() {
        released = true;
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

    /**
     * {@code evaluateJavascript} 会把字符串结果再包一层 JSON 字符串（带引号与转义）。
     * 这里剥掉外层引号并还原转义，让上层拿到原始文本。
     */
    static String unwrapJsonString(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if ("null".equals(s)) {
            return null;
        }
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 1; i < s.length() - 1; i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length() - 1) {
                    char n = s.charAt(++i);
                    switch (n) {
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case 'u':
                            if (i + 4 < s.length() - 1) {
                                try {
                                    sb.append((char) Integer.parseInt(
                                            s.substring(i + 1, i + 5), 16));
                                    i += 4;
                                } catch (NumberFormatException e) {
                                    sb.append(n);
                                }
                            } else {
                                sb.append(n);
                            }
                            break;
                        default:
                            sb.append(n);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        return s;
    }
}
