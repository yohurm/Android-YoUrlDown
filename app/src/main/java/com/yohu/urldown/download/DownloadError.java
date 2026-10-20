package com.yohu.urldown.download;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.yohu.lib_download.YoDownload;
import com.yohu.urldown.R;
import com.yohu.urldown.download.hls.HlsDownloader;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 下载失败的类型化原因。
 *
 * <p>上层只按这里的分类做决定，不去匹配异常文本：
 * <ul>
 *   <li>{@link #waitsForNetwork()}：断网导致，恢复网络后自动续传；</li>
 *   <li>{@link #refreshesLink()}：媒体直链过期，重新解析原链接后再试；</li>
 *   <li>{@link #retryable()}：用户点“重试”有意义；</li>
 *   <li>{@link #message()}：给用户看的一句话。</li>
 * </ul>
 */
public enum DownloadError {
    /** 连不上 / 读超时 / 中途断开。 */
    NETWORK(R.string.download_error_network, true),
    /** 设置了仅 Wi‑Fi 下载，当前不是 Wi‑Fi（由任务调度判定）。 */
    WIFI_REQUIRED(R.string.download_error_wifi, true),
    /** 403 / 404 / 410：直链过期或需要新的签名。 */
    LINK_EXPIRED(R.string.download_error_link_expired, true),
    /** 5xx：对方服务器出错。 */
    SERVER(R.string.download_error_server, true),
    /** 其余 HTTP 拒绝。 */
    HTTP(R.string.download_error_http, true),
    /** 空间不足或目录不可写。 */
    STORAGE(R.string.download_error_storage, false),
    /** 读写文件出错。 */
    IO(R.string.download_error_io, true),
    /** 下载到的内容不是可播放的媒体（错误页、文本）。 */
    NOT_MEDIA(R.string.download_error_not_media, true),
    /** 流格式不支持（直播流、未知加密方式）。 */
    UNSUPPORTED(R.string.download_error_unsupported, false),
    /** 音视频合并失败（分离轨已保留）。 */
    MERGE(R.string.download_error_merge, true),
    /** 解析结果里没有可下载的媒体。 */
    NO_MEDIA(R.string.download_error_no_media, false),
    /** 重新解析原链接失败。 */
    PARSE(R.string.download_error_parse, true),
    /** 长时间没有任何数据。 */
    STALLED(R.string.download_error_stalled, true),
    UNKNOWN(R.string.download_error_unknown, true);

    private static final Pattern HTTP_CODE = Pattern.compile("HTTP error: (\\d{3})");

    @StringRes
    private final int message;
    private final boolean retryable;

    DownloadError(@StringRes int message, boolean retryable) {
        this.message = message;
        this.retryable = retryable;
    }

    @StringRes
    public int message() {
        return message;
    }

    public boolean retryable() {
        return retryable;
    }

    /** 断网 / 等 Wi‑Fi：交给网络监听在恢复后自动续传，而不是直接判失败。 */
    public boolean waitsForNetwork() {
        return this == NETWORK || this == WIFI_REQUIRED || this == STALLED;
    }

    /** 直链过期：先重新解析原链接拿新地址，再续传。 */
    public boolean refreshesLink() {
        return this == LINK_EXPIRED;
    }

    /** 按 HTTP 状态码分类。 */
    @NonNull
    public static DownloadError ofHttpStatus(int status) {
        if (status == 401 || status == 403 || status == 404 || status == 410) {
            return LINK_EXPIRED;
        }
        if (status >= 500 && status <= 599) {
            return SERVER;
        }
        return HTTP;
    }

    /** 下载引擎的错误码 → 分类。HTTP 错误从引擎的异常描述里取状态码。 */
    @NonNull
    public static DownloadError ofEngine(@Nullable YoDownload.Error error, @Nullable Throwable cause) {
        if (error == null) {
            return UNKNOWN;
        }
        return switch (error) {
            case NETWORK -> NETWORK;
            case HTTP -> ofHttpStatus(httpStatusOf(cause));
            case STORAGE -> STORAGE;
            case IO, FILE_BUSY, SAME_TASK_BUSY -> IO;
            case INVALID_REQUEST -> LINK_EXPIRED;
            default -> UNKNOWN;
        };
    }

    /** HLS 下载器的失败 → 分类。 */
    @NonNull
    public static DownloadError ofHls(@NonNull HlsDownloader.HlsException e) {
        return switch (e.kind) {
            case NETWORK -> NETWORK;
            case HTTP -> ofHttpStatus(e.httpStatus);
            case FORMAT -> UNSUPPORTED;
            case STORAGE -> STORAGE;
            case CANCELED -> UNKNOWN;
        };
    }

    static int httpStatusOf(@Nullable Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            Matcher m = HTTP_CODE.matcher(String.valueOf(t.getMessage()));
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        return -1;
    }
}
