package com.yohu.lib_urlparse.transfer;

/**
 * 下载回调。所有方法都在**下载线程**上调用，实现里若要更新 UI 请自行切线程。
 *
 * <p>默认实现全部为空，调用方只覆写关心的那几个。
 */
public interface DownloadListener {

    /** 已建立连接、拿到响应头。{@code totalBytes} 未知时为 -1。 */
    void onStart(String url, long totalBytes);

    /** 进度更新（实现应按需节流，例如每 200 ms 或每 1% 一次）。 */
    void onProgress(DownloadProgress progress);

    /** 成功：文件已在 {@code result.file()} 就位。 */
    void onComplete(DownloadResult result);

    /** 失败或取消。{@code e.resumable()} 表示留下了可续传的 .part。 */
    void onError(DownloadException e);

    /** 什么都不做的实现，便于只关心返回值时使用。 */
    DownloadListener NOOP = new DownloadListener() {
        @Override
        public void onStart(String url, long totalBytes) {
        }

        @Override
        public void onProgress(DownloadProgress progress) {
        }

        @Override
        public void onComplete(DownloadResult result) {
        }

        @Override
        public void onError(DownloadException e) {
        }
    };
}
