package com.yohu.urldown;

import android.app.Application;
import android.util.Log;

import com.yohu.urldown.download.AssetDownloader;
import com.yohu.urldown.task.DownloadRunner;
import com.yohu.urldown.task.TaskStore;

/**
 * 进程入口：下载相关的单例在这里初始化一次。
 *
 * <p>放在 Application 而不是 Activity：系统可能只拉起前台服务所在的进程，
 * 任务列表和下载引擎得先于任何界面就绪。顺序固定——引擎 → 任务列表 → 调度器
 * （调度器启动时会继续上次被打断的任务，需要前两者都可用）。
 */
public class UrlDownApp extends Application {

    private static final String TAG = "YoUrlDown";

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            AssetDownloader.init(this, DownloadRunner.downloadDir(this));
        } catch (Throwable t) {
            // 引擎不可用时任务会以失败结束，界面照常可用。
            Log.w(TAG, "下载引擎初始化失败: " + t);
        }
        TaskStore.get().init(this);
        DownloadRunner.init(this);
    }
}
