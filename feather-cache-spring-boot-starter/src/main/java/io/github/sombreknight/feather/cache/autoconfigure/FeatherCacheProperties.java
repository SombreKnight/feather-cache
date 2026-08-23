package io.github.sombreknight.feather.cache.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * feather-cache 配置项（前缀 {@code feather.cache}）。
 *
 * <p>连接层不在此配置——Redis 连接完全复用 {@code spring.data.redis.*}。</p>
 *
 * @author sombreknight
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "feather.cache")
public class FeatherCacheProperties {

    /**
     * 是否启用 feather-cache（默认启用）。
     */
    private boolean enabled = true;

    /**
     * 全局 key 命名空间（默认取 spring.application.name，用于 {@code feather:{app}:*} key 前缀）。
     */
    private String namespace;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }
}
