package io.github.sombreknight.feather.cache.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 命名策略单测：key 三段式（app/scope/key）拼装与规范化。
 */
class NamingStrategyTest {

    @Test
    void buildCacheKeyWithAppAndScope() {
        NamingStrategy strategy = new NamingStrategy("order-service");

        assertThat(strategy.cacheKey("user:123")).isEqualTo("feather:order-service:cache:user:123");
        assertThat(strategy.lockKey("pay:456")).isEqualTo("feather:order-service:lock:pay:456");
        assertThat(strategy.sentinelKey("user:123")).isEqualTo("feather:order-service:sentinel:user:123");
    }

    @Test
    void blankAppFallsBackToDefault() {
        NamingStrategy strategy = new NamingStrategy("  ");

        assertThat(strategy.cacheKey("k")).isEqualTo("feather:default:cache:k");
    }

    @Test
    void nullAppFallsBackToDefault() {
        NamingStrategy strategy = new NamingStrategy(null);

        assertThat(strategy.cacheKey("k")).isEqualTo("feather:default:cache:k");
    }

    @Test
    void blankKeyRejected() {
        NamingStrategy strategy = new NamingStrategy("app");

        assertThatThrownBy(() -> strategy.cacheKey("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key 不能为空");
        assertThatThrownBy(() -> strategy.cacheKey(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizeTrimsAndCompactsColons() {
        assertThat(NamingStrategy.normalizeSegment("  user:123  ")).isEqualTo("user:123");
        assertThat(NamingStrategy.normalizeSegment("a::b")).isEqualTo("a:b");
        assertThat(NamingStrategy.normalizeSegment(":a:b:")).isEqualTo("a:b");
        assertThat(NamingStrategy.normalizeSegment(null)).isEmpty();
    }

    @Test
    void keyWithInternalColonPreserved() {
        NamingStrategy strategy = new NamingStrategy("app");

        // 业务 key 段内部的单个冒号保留（Redis 分段习惯）
        assertThat(strategy.cacheKey("order:123")).isEqualTo("feather:app:cache:order:123");
    }
}
