package io.github.sombreknight.feather.cache.autoconfigure;

import io.github.sombreknight.feather.cache.cache.FeatherCache;
import io.github.sombreknight.feather.cache.cache.impl.LocalCacheClient;
import io.github.sombreknight.feather.cache.cache.impl.RedisCacheClient;
import io.github.sombreknight.feather.cache.lock.DistributedLockService;
import io.github.sombreknight.feather.cache.redis.FeatherRedisClient;
import io.github.sombreknight.feather.cache.support.NamingStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动配置装配测试：验证所有 bean 就绪、属性绑定生效、配置传导到组件。
 */
@SpringBootTest(classes = FeatherCacheAutoConfigurationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.application.name=test-service",
                "feather.cache.local.max-size=100",
                "feather.cache.local.ttl=1s",
                "feather.cache.cache.single-flight-permits=3",
                "feather.cache.lock.default-wait=2s",
                "feather.cache.lock.default-lock-duration=15s",
                "feather.cache.lock.enable-watch-dog=false"
        })
class FeatherCacheAutoConfigurationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestConfig {
    }

    @Autowired
    private NamingStrategy namingStrategy;
    @Autowired
    private FeatherRedisClient redisClient;
    @Autowired
    private LocalCacheClient localCacheClient;
    @Autowired
    private RedisCacheClient redisCacheClient;
    @Autowired
    private FeatherCache featherCache;
    @Autowired
    private DistributedLockService lockService;
    @Autowired
    private FeatherCacheProperties properties;

    @Test
    void allBeansAreWired() {
        assertThat(namingStrategy).isNotNull();
        assertThat(redisClient).isNotNull();
        assertThat(localCacheClient).isNotNull();
        assertThat(redisCacheClient).isNotNull();
        assertThat(featherCache).isNotNull();
        assertThat(lockService).isNotNull();
    }

    @Test
    void namingStrategyFallsBackToSpringApplicationName() {
        assertThat(namingStrategy.cacheKey("k")).isEqualTo("feather:test-service:cache:k");
        assertThat(namingStrategy.lockKey("k")).isEqualTo("feather:test-service:lock:k");
    }

    @Test
    void propertiesAreBound() {
        assertThat(properties.getLocal().getMaxSize()).isEqualTo(100);
        assertThat(properties.getLocal().getTtl()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.getCache().getSingleFlightPermits()).isEqualTo(3);
        assertThat(properties.getLock().getDefaultWait()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.getLock().getDefaultLockDuration()).isEqualTo(Duration.ofSeconds(15));
        assertThat(properties.getLock().isEnableWatchDog()).isFalse();
    }

    @Test
    void localCacheTtlPropagatesFromProperties() throws InterruptedException {
        // feather.cache.local.ttl=1s 已传导到 LocalCacheClient
        localCacheClient.set("k", "v", Duration.ofMinutes(1));
        assertThat(localCacheClient.get("k")).isEqualTo("v");

        Thread.sleep(1200);
        assertThat(localCacheClient.get("k")).isNull();
    }
}
