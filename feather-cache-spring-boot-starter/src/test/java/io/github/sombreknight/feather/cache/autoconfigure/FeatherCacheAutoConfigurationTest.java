package io.github.sombreknight.feather.cache.autoconfigure;

import io.github.sombreknight.feather.cache.cache.FeatherCache;
import io.github.sombreknight.feather.cache.cache.impl.LocalCacheClient;
import io.github.sombreknight.feather.cache.cache.impl.RedisCacheClient;
import io.github.sombreknight.feather.cache.lock.DistributedLockService;
import io.github.sombreknight.feather.cache.redis.FeatherRedisClient;
import io.github.sombreknight.feather.cache.redis.RedisConnectionConfig;
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
                "feather.cache.redis.host=redis-host",
                "feather.cache.redis.port=6380",
                "feather.cache.redis.database=2",
                "feather.cache.redis.mode=standalone",
                "feather.cache.local.max-size=100",
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
        assertThat(properties.getRedis().getHost()).isEqualTo("redis-host");
        assertThat(properties.getRedis().getPort()).isEqualTo(6380);
        assertThat(properties.getRedis().getDatabase()).isEqualTo(2);
        assertThat(properties.getRedis().getMode()).isEqualTo(RedisConnectionConfig.Mode.STANDALONE);
        assertThat(properties.getLocal().getMaxSize()).isEqualTo(100);
        assertThat(properties.getLock().getDefaultWait()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.getLock().getDefaultLockDuration()).isEqualTo(Duration.ofSeconds(15));
        assertThat(properties.getLock().isEnableWatchDog()).isFalse();
    }

    @Test
    void redisDefaultsAreStandaloneLocalhost() {
        // 未配置时的默认值：standalone + localhost:6379 + db0
        FeatherCacheProperties defaults = new FeatherCacheProperties();
        assertThat(defaults.getRedis().getMode()).isEqualTo(RedisConnectionConfig.Mode.STANDALONE);
        assertThat(defaults.getRedis().getHost()).isEqualTo("localhost");
        assertThat(defaults.getRedis().getPort()).isEqualTo(6379);
        assertThat(defaults.getRedis().getDatabase()).isZero();
        assertThat(defaults.getRedis().isSsl()).isFalse();
    }
}
