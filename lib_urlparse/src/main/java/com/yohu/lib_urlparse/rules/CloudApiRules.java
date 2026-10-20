package com.yohu.lib_urlparse.rules;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 云解析通道的规则：分享域 → 平台 id 的映射，以及失败提示语。
 *
 * <p>端点、字段名、鉴权都由宿主注入（见 {@code CloudApiProvider}），这里只放与服务无关的部分。
 */
public final class CloudApiRules {

    private CloudApiRules() {
    }

    /** 已知分享域 → 平台 id（判等或后缀匹配）。 */
    private static final Map<String, String> HOSTS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("v.kuaishou.com", "kuaishou");
        m.put("bilibili.com", "bilibili");
        m.put("youtu.be", "youtube");
        m.put("ixigua.com", "ixigua");
        m.put("toutiao.com", "toutiao");
        HOSTS = Collections.unmodifiableMap(m);
    }

    /** 服务端返回失败时，提示语的前缀。 */
    public static final String MSG_PARSE_FAILED = "解析失败，失败原因：";

    /** 服务端返回为空。 */
    public static final String MSG_EMPTY_RESPONSE = "解析服务返回为空";

    /**
     * 主机对应的平台 id。
     *
     * @return 平台 id；未登记返回 null
     */
    public static String platformIdOfHost(String host) {
        if (host == null) {
            return null;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : HOSTS.entrySet()) {
            String registered = e.getKey();
            if (h.equals(registered) || h.endsWith("." + registered)) {
                return e.getValue();
            }
        }
        return null;
    }
}
