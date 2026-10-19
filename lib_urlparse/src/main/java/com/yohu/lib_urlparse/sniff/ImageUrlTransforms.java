package com.yohu.lib_urlparse.sniff;

import java.util.Locale;

/**
 * 图片去水印地址变换（本工程新增，【推断】性质）。
 *
 * <p><b>为什么放在工程层</b>：已还原的嗅探规则只找视频流、刻意排除图片（报告 08 §8.6），
 * 因此"图集图片怎么处理"本来就不在还原范围内，属于本工程自行决定的部分。
 *
 * <p><b>判据与做法</b>：抖音图集图片地址形如
 * {@code …~tplv-dy-lqen-new-water:1056:1920:xxx:q80.webp}，其中 {@code -water} 段是水印变体。
 * 去掉该段即请求无水印版本——这与视频的 {@code playwm→play} 是同一类社区长期观察到的行为。
 *
 * <p><b>必须保留原始地址</b>：这是推断，官方随时可能改语义。因此调用方要把
 * 原始（带水印）地址留作 {@code alternate}——推断失效时用户至少还能下到带水印的图，
 * 而不是拿到一个 404。
 */
public final class ImageUrlTransforms {

    private ImageUrlTransforms() {
    }

    /**
     * 图片去水印变换**是否启用**——实测结论为否，故默认关闭。
     *
     * <p><b>实测（2026-10，真机）</b>：把
     * {@code …~tplv-dy-lqen-new-water:1056:1920:…:q80.webp} 改成
     * {@code …~tplv-dy-lqen-new:1056:1920:…:q80.webp} 后请求，CDN 返回
     * <b>HTTP 403</b>（真机日志：{@code dl error: HTTP_STATUS unexpected HTTP status 403 for
     * https://p3-sign.douyinpic.com/…~tplv-dy-lqen-new:1056:1920:…}）。
     *
     * <p>也就是说：这个"社区经验"在**图片**上不成立（视频的 {@code playwm→play} 是另一回事，
     * 尚未实测）。因此这里保留实现但**默认关闭**，并把否定结论记录在此——
     * 目的是让别人不必再盲试一遍，而不是留一段永远不跑的代码装作能力。
     */
    public static final boolean IMAGE_WATERMARK_TRANSFORM_ENABLED = false;

    /** 抖音图片 CDN 主机特征。 */
    private static final String[] DOUYIN_IMAGE_HOSTS = {
            "douyinpic.com", "douyinpic.cn", "pstatp.com", "byteimg.com", "ibyteimg.com"
    };

    /** 水印变体标记。 */
    private static final String WM_SEGMENT = "tplv-dy-lqen-new-water";
    private static final String WM_SEGMENT_PLAIN = "tplv-dy-lqen-new";

    /**
     * 该地址是否带抖音图片水印变体标记。
     */
    public static boolean isWatermarked(String url) {
        if (url == null) {
            return false;
        }
        String u = url.toLowerCase(Locale.ROOT);
        return u.contains(WM_SEGMENT) || u.contains("-water:") || u.contains("~water");
    }

    /** 该主机是否属于抖音图片 CDN。 */
    public static boolean isDouyinImageHost(String url) {
        if (url == null) {
            return false;
        }
        String u = url.toLowerCase(Locale.ROOT);
        for (String h : DOUYIN_IMAGE_HOSTS) {
            if (u.contains(h)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 尝试给出无水印地址。
     *
     * @return 无水印地址；不适用（非抖音图床 / 本来就没有水印段）时返回 <b>null</b>，
     *         以便调用方区分"不该变换"与"变换后仍是原地址"
     */
    public static String noWatermark(String url) {
        if (!IMAGE_WATERMARK_TRANSFORM_ENABLED) {
            // 见常量注释：实测该变换会被 CDN 以 403 拒绝，故默认不启用。
            return null;
        }
        return transformUnchecked(url);
    }

    /**
     * 只做字符串变换、**不受开关约束**。
     *
     * <p>存在的理由：让"变换的字符串机制"保持被测试覆盖，同时生产路径仍受
     * {@link #IMAGE_WATERMARK_TRANSFORM_ENABLED} 保护。将来若确认某种变体可行，
     * 复核时也应先在这里验证字符串结果，再决定是否打开开关。
     */
    public static String transformUnchecked(String url) {
        if (!isWatermarked(url) || !isDouyinImageHost(url)) {
            return null;
        }
        String out = url.replace(WM_SEGMENT, WM_SEGMENT_PLAIN)
                .replace("-water:", ":")
                .replace("~water", "~");
        return out.equals(url) ? null : out;
    }
}
