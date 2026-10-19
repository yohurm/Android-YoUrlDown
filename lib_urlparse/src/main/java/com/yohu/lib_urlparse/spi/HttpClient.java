package com.yohu.lib_urlparse.spi;

/**
 * HTTP 抽象。
 *
 * <p>为什么要有这层接口：默认实现是手写的 {@code java.net} 客户端（零依赖），
 * 但单测需要能替换成假实现来构造各种响应；宿主也可能想接自己的客户端。
 *
 * <p><b>线程</b>：实现必须是**线程安全**的；同一实例会被多个通道并发使用。
 * <b>取消</b>：实现应尽量响应 {@link CancelToken}（至少在每个 IO 循环检查一次）。
 */
public interface HttpClient {

    /** 执行请求。失败抛 {@link HttpException}，不返回 {@code null}。 */
    HttpResponse execute(HttpRequest request) throws HttpException;

    /** 便捷：GET，无自定义头。 */
    default HttpResponse get(String url) throws HttpException {
        return execute(HttpRequest.get(url).build());
    }

    /** 便捷：GET + 自定义头。 */
    default HttpResponse get(String url, java.util.Map<String, String> headers) throws HttpException {
        return execute(HttpRequest.get(url).headers(headers).build());
    }

    /** 便捷：POST 表单。 */
    default HttpResponse postForm(String url, String formBody,
                                  java.util.Map<String, String> headers) throws HttpException {
        return execute(HttpRequest.post(url)
                .contentType("application/x-www-form-urlencoded; charset=UTF-8")
                .body(formBody)
                .headers(headers)
                .build());
    }

    /** 便捷：POST JSON。 */
    default HttpResponse postJson(String url, String json,
                                  java.util.Map<String, String> headers) throws HttpException {
        return execute(HttpRequest.post(url)
                .contentType("application/json; charset=UTF-8")
                .body(json)
                .headers(headers)
                .build());
    }

    /** 释放底层资源（连接池等）。默认无操作。 */
    default void close() {
        // no-op
    }
}
