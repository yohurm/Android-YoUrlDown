package com.yohu.lib_urlparse.transfer;

/** 下载状态。 */
public enum DownloadState {
    /** 尚未开始。 */
    PENDING,
    /** 正在传输。 */
    RUNNING,
    /** 完成且文件已就位（临时文件已改名）。 */
    COMPLETED,
    /** 失败；已下载的部分通常保留为 {@code .part}，可续传。 */
    FAILED,
    /** 被调用方取消；已下载部分保留，可续传。 */
    CANCELLED
}
