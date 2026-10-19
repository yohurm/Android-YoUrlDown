package com.yohu.urldown;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.yohu.lib_urlparse.api.MediaAsset;
import com.yohu.lib_urlparse.api.ParseFailure;
import com.yohu.lib_urlparse.api.UrlParseRequest;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.urldown.databinding.ActivityMainBinding;
import com.yohu.urldown.download.DownloadPipeline;

import java.io.File;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 首页：粘贴链接 → 解析 → 展示结果。
 *
 * <p>解析在**后台线程**执行（网络与 WebView 等待都不能占主线程），结果回主线程渲染。
 * 失败时展示引擎给出的 reason 与人话提示，不伪造"解析成功"。
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile UrlParseResult lastResult;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 下载引擎必须在用之前初始化；此前漏了这一步，导致点下载直接失败。
        try {
            com.yohu.urldown.download.AssetDownloader.init(this,
                    new java.io.File(getExternalFilesDir(null), "downloads"));
        } catch (Throwable t) {
            android.util.Log.w("YoUrlDown", "下载引擎初始化失败: " + t);
        }

        binding.actionParse.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String input = binding.input.getText() == null
                        ? "" : binding.input.getText().toString().trim();
                if (input.isEmpty()) {
                    Toast.makeText(MainActivity.this, "请先粘贴链接或分享文本",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                startParse(input);
            }
        });

        binding.actionDownload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadLast();
            }
        });

        // 支持外部带入链接（adb / 分享），便于自动化真机验证：
        //   adb shell am start -n com.yohu.urldown/.MainActivity -e url "<链接>"
        String extra = getIntent() == null ? null : getIntent().getStringExtra("url");
        if (extra != null && !extra.trim().isEmpty()) {
            binding.input.setText(extra);
            startParse(extra.trim());
        }
    }

    private void startParse(final String input) {
        binding.actionParse.setEnabled(false);
        binding.output.setText("解析中…\n输入长度: " + input.length());
        executor.execute(new Runnable() {
            @Override
            public void run() {
                String text;
                UrlParseResult parsed = null;
                try {
                    parsed = EngineBootstrap.engine(MainActivity.this)
                            .parse(UrlParseRequest.builder(input).build());
                    text = render(parsed);
                } catch (ParseFailure f) {
                    text = renderFailure(f);
                } catch (Throwable t) {
                    text = "解析异常: " + t;
                }
                final String finalText = text;
                final UrlParseResult finalResult = parsed;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        binding.output.setText(finalText);
                        binding.actionParse.setEnabled(true);
                        lastResult = finalResult;
                        binding.actionDownload.setEnabled(finalResult != null
                                && !finalResult.downloadable().isEmpty());
                    }
                });
            }
        });
    }

    private void downloadLast() {
        final UrlParseResult r = lastResult;
        if (r == null || r.downloadable().isEmpty()) {
            Toast.makeText(this, "没有可下载的媒体", Toast.LENGTH_SHORT).show();
            return;
        }
        binding.actionDownload.setEnabled(false);
        binding.output.setText("下载中…\n" + r.title());
        executor.execute(new Runnable() {
            @Override
            public void run() {
                String text;
                try {
                    File dir = new File(getExternalFilesDir(null), "downloads");
                    DownloadPipeline.Report report = DownloadPipeline.download(r, dir);
                    text = (report.success ? "下载完成\n\n" : "下载未成功\n\n") + report.text();
                } catch (Throwable t) {
                    text = "下载异常: " + t;
                }
                final String finalText2 = text;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        binding.output.setText(finalText2);
                        binding.actionDownload.setEnabled(true);
                    }
                });
            }
        });
    }

    private static String render(UrlParseResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("平台: ").append(r.platform().displayName()).append('\n');
        sb.append("通道: ").append(r.channel()).append('\n');
        sb.append("作品ID: ").append(nullToDash(r.workId())).append('\n');
        sb.append("标题: ").append(nullToDash(r.title())).append('\n');
        if (r.author().nickname() != null) {
            sb.append("作者: ").append(r.author().nickname()).append('\n');
        }
        List<MediaAsset> videos = r.assets(MediaAsset.Kind.VIDEO);
        List<MediaAsset> images = r.assets(MediaAsset.Kind.IMAGE);
        List<MediaAsset> audios = r.assets(MediaAsset.Kind.AUDIO);
        sb.append("媒体: 视频 ").append(videos.size())
                .append(" / 图片 ").append(images.size())
                .append(" / 音频 ").append(audios.size()).append('\n');
        for (MediaAsset a : r.downloadable()) {
            sb.append('\n').append(a.kind()).append(": ").append(a.url()).append('\n');
            if (a.width() > 0 && a.height() > 0) {
                sb.append("  尺寸 ").append(a.width()).append('x').append(a.height());
            }
            if (a.durationMs() > 0) {
                sb.append("  时长 ").append(a.durationMs() / 1000).append('s');
            }
            if (!a.alternates().isEmpty()) {
                sb.append("  备用地址 ").append(a.alternates().size()).append(" 条");
            }
            sb.append('\n');
        }
        sb.append("\n尝试链:\n")
                .append(r.trace() == null ? "(无)" : r.trace().summarize());
        return sb.toString();
    }

    private static String renderFailure(ParseFailure f) {
        return "解析失败\n"
                + "原因: " + f.reason() + "\n"
                + "通道: " + (f.channel().isEmpty() ? "(无通道认领)" : f.channel()) + "\n"
                + "可重试: " + (f.retryable() ? "是" : "否") + "\n"
                + "提示: " + f.userHint() + "\n"
                + "详情: " + f.getMessage();
    }

    private static String nullToDash(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
