package com.yohu.lib_urlparse.provider.cloud;

import com.yohu.lib_urlparse.api.FailureReason;
import com.yohu.lib_urlparse.api.MediaAsset;
import com.yohu.lib_urlparse.api.ParseFailure;
import com.yohu.lib_urlparse.api.Platform;
import com.yohu.lib_urlparse.api.UrlParseRequest;
import com.yohu.lib_urlparse.api.UrlParseResult;
import com.yohu.lib_urlparse.rules.CloudApiRules;
import com.yohu.lib_urlparse.sniff.MediaExtensions;
import com.yohu.lib_urlparse.spi.CredentialProvider;
import com.yohu.lib_urlparse.spi.HttpResponse;
import com.yohu.lib_urlparse.spi.ParseContext;
import com.yohu.lib_urlparse.spi.ParseProvider;
import com.yohu.lib_urlparse.spi.ProviderPriority;
import com.yohu.lib_urlparse.util.UrlUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 云解析通道：把链接交给**调用方自备**的解析服务。
 *
 * <p>本库**不内置任何第三方服务的地址或密钥**，这是硬约束：
 * <ul>
 *   <li>端点与 key 只能来自 {@link CredentialProvider}（由宿主配置）；</li>
 *   <li>没有配置时抛 {@link FailureReason#CREDENTIALS_MISSING}，而不是"尝试某个默认地址"。</li>
 * </ul>
 *
 * <p>协议约定：{@code POST endpoint}，body 为
 * {@code {"apiKey": <key>, "data": {"url": <链接>}}}；
 * 响应里取 {@code data.url}（视频）与 {@code data.images[]}（图集），
 * 若字段名不同则退化为"在响应里找第一个视频地址 / 图片地址"。
 *
 * <p>纯 JVM 可测：只依赖 {@link com.yohu.lib_urlparse.spi.HttpClient} 与凭据接口。
 */
public final class CloudApiProvider implements ParseProvider {

    private static final String TAG = "CloudApiProvider";
    private static final String CHANNEL = "cloud.api";

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public ProviderPriority priority() {
        return ProviderPriority.CLOUD;
    }

    @Override
    public boolean canHandle(ParseContext context, UrlParseRequest request) {
        if (request == null || context == null) {
            return false;
        }
        // 只有宿主配置了端点时才认领：没配置就等于"这条通道不存在"，
        // 而不是每次解析都记一条 CREDENTIALS_MISSING 的噪声。
        String endpoint = credentials(context).endpoint(CHANNEL).orElse(null);
        return endpoint != null && !endpoint.trim().isEmpty()
                && UrlUtils.extractFirstUrl(request.rawInput()) != null;
    }

    @Override
    public UrlParseResult parse(ParseContext context, UrlParseRequest request) throws ParseFailure {
        CredentialProvider creds = credentials(context);
        String endpoint = creds.endpoint(CHANNEL).orElse(null);
        String apiKey = creds.apiKey(CHANNEL).orElse(null);
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new ParseFailure(FailureReason.CREDENTIALS_MISSING, channel(),
                    "未配置云解析端点（本库不内置任何第三方服务地址）");
        }
        String url = UrlUtils.extractFirstUrl(request.rawInput());
        if (url == null) {
            throw new ParseFailure(FailureReason.UNRECOGNISED_INPUT, channel(),
                    "输入里没有 URL");
        }

        String body = buildPayload(apiKey, url);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("Accept", "application/json");
        headers.put("User-Agent", "YoUrlDown/1.0");
        if (request.timeoutMs() > 0) {
            headers.put("X-Timeout-Ms", String.valueOf(request.timeoutMs()));
        }

        String response;
        try {
            HttpResponse resp = context.http().postJson(endpoint, body, headers);
            response = resp.body() == null ? "" : resp.body().trim();
            context.logger().info(TAG, "POST " + endpoint + " -> HTTP " + resp.status()
                    + " 长度=" + response.length());
        } catch (Exception e) {
            throw new ParseFailure(FailureReason.NETWORK, channel(), "云解析请求失败: " + e, e);
        }
        if (response.isEmpty()) {
            throw new ParseFailure(FailureReason.UNEXPECTED_RESPONSE, channel(),
                    CloudApiRules.MSG_EMPTY_RESPONSE);
        }

        UrlParseResult.Builder out = UrlParseResult.builder(Platform.UNKNOWN)
                .channel(channel())
                .sourceUrl(endpoint)
                .workId(null)
                .extra("cloud.endpoint", endpoint);
        int videos = 0;
        int images = 0;

        JSONObject json = null;
        try {
            json = new JSONObject(response);
        } catch (org.json.JSONException e) {
            context.logger().warn(TAG, "响应不是 JSON，改为在文本中找地址");
        }

        if (json != null) {
            String covered = CloudApiRules.platformIdOfHost(UrlUtils.host(url));
            if (covered != null) {
                out.extra("cloud.coveredPlatform", covered);
            }
            String title = json.optString("title", null);
            JSONObject data = json.optJSONObject("data");
            if (data == null) {
                data = json;
            }
            if (title == null) {
                title = data.optString("title", null);
            }
            if (title != null && !title.isEmpty()) {
                out.title(title);
            }
            // 错误约定：msg 前缀 + 具体原因
            String msg = json.optString("msg", null);
            if (msg != null && msg.startsWith(CloudApiRules.MSG_PARSE_FAILED)) {
                throw new ParseFailure(FailureReason.UNEXPECTED_RESPONSE, channel(),
                        "云服务返回失败: " + msg);
            }
            String video = firstString(data, "url", "video", "videoUrl", "playUrl");
            if (video != null) {
                out.asset(MediaAsset.video(video).mimeType("video/mp4").build());
                videos++;
            }
            JSONArray pics = data.optJSONArray("images");
            if (pics == null) {
                pics = data.optJSONArray("pics");
            }
            if (pics != null) {
                for (int i = 0; i < pics.length(); i++) {
                    String img = pics.optString(i, null);
                    if (img != null && img.startsWith("http")) {
                        out.asset(MediaAsset.image(img).build());
                        images++;
                    }
                }
            }
        }

        if (videos == 0 && images == 0) {
            // 兜底：在响应文本里按扩展名找地址（兼容字段名不一致的服务端）
            for (String candidate : UrlUtils.extractAllUrls(response)) {
                if (MediaExtensions.looksLikeVideo(candidate)) {
                    out.asset(MediaAsset.video(candidate).mimeType("video/mp4").build());
                    videos++;
                } else if (MediaExtensions.looksLikeImage(candidate)) {
                    out.asset(MediaAsset.image(candidate).build());
                    images++;
                }
                if (videos + images >= 20) {
                    break;
                }
            }
        }

        out.extra("cloud.videos", String.valueOf(videos));
        out.extra("cloud.images", String.valueOf(images));
        UrlParseResult result = out.build();
        if (result.assets().isEmpty()) {
            throw new ParseFailure(FailureReason.NO_MEDIA_FOUND, channel(),
                    "云服务响应里没有媒体地址（"
                            + (videos == 0 && images == 0 ? "已尝试字段与文本兜底" : "") + "）");
        }
        return result;
    }

    private static CredentialProvider credentials(ParseContext context) {
        CredentialProvider c = context == null ? null : context.options().credentials();
        return c == null ? CredentialProvider.NONE : c;
    }

    /** 按约定的 payload 形态构造请求体。 */
    static String buildPayload(String apiKey, String url) {
        JSONObject data = new JSONObject();
        JSONObject root = new JSONObject();
        try {
            data.put("url", url);
            root.put("data", data);
            if (apiKey != null && !apiKey.isEmpty()) {
                root.put("apiKey", apiKey);
            }
        } catch (org.json.JSONException e) {
            // 字段都是简单字符串，不会失败
        }
        return root.toString();
    }

    private static String firstString(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, null);
            if (v != null && v.startsWith("http")) {
                return v;
            }
        }
        return null;
    }
}
