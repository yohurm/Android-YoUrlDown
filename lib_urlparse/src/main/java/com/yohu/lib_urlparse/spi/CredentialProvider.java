package com.yohu.lib_urlparse.spi;

import java.util.Optional;

/**
 * 调用方凭据提供者。
 *
 * <p><b>硬约束</b>：本库**不内置**任何第三方服务的端点、密钥或 Cookie。
 * 需要凭据的通道（例如"自备云函数端点"）一律通过这个接口注入；未注入时通道返回
 * {@code CREDENTIALS_MISSING} 并给出配置指引，而不是静默失败。
 */
public interface CredentialProvider {

    /** 什么都不提供（默认实现）。 */
    CredentialProvider NONE = new CredentialProvider() {
        @Override
        public Optional<String> endpoint(String channel) {
            return Optional.empty();
        }

        @Override
        public Optional<String> apiKey(String channel) {
            return Optional.empty();
        }

        @Override
        public Optional<String> cookie(String domain) {
            return Optional.empty();
        }
    };

    /** 某通道的端点（URL）；未配置返回空。 */
    Optional<String> endpoint(String channel);

    /** 某通道的密钥；未配置返回空。 */
    Optional<String> apiKey(String channel);

    /** 某域名的 Cookie（例如用户自己已登录的会话）；未配置返回空。 */
    default Optional<String> cookie(String domain) {
        return Optional.empty();
    }
}
