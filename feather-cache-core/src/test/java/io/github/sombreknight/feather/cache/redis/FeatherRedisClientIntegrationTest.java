package io.github.sombreknight.feather.cache.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Redis 集成测试。
 *
 * <p>需要环境变量 {@code REDIS_TEST_URL}（如 {@code redis://localhost:6379}）；
 * 未配置（本地开发）自动跳过，CI 通过服务容器启用（同 feather-orm 的 PG_TEST_URL 策略）。</p>
 */
class FeatherRedisClientIntegrationTest {

    private static final String REDIS_URL = System.getenv("REDIS_TEST_URL");

    private static StringRedisTemplate redisTemplate;
    private static FeatherRedisClient client;

    @BeforeAll
    static void init() {
        assumeTrue(REDIS_URL != null && !REDIS_URL.trim().isEmpty(),
                "未配置 REDIS_TEST_URL，跳过 Redis 集成测试（CI 服务容器会自动启用）");

        URI uri = URI.create(REDIS_URL);
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(uri.getHost(), uri.getPort());
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(factory);
        redisTemplate.afterPropertiesSet();

        client = new FeatherRedisClient(redisTemplate);
    }

    @AfterAll
    static void destroy() {
        if (redisTemplate != null) {
            redisTemplate.getConnectionFactory().getConnection().close();
        }
    }

    @BeforeEach
    void cleanUp() {
        assumeTrue(REDIS_URL != null && !REDIS_URL.trim().isEmpty(), "跳过");
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void setAndGet() {
        client.set("k1", "v1", Duration.ofSeconds(60));

        assertThat(client.get("k1")).isEqualTo("v1");
        assertThat(client.get("missing")).isNull();
    }

    @Test
    void setOverwrites() {
        client.set("k1", "v1", Duration.ofSeconds(60));
        client.set("k1", "v2", Duration.ofSeconds(60));

        assertThat(client.get("k1")).isEqualTo("v2");
    }

    @Test
    void setIfAbsentAtomic() {
        assertThat(client.setIfAbsent("lock1", "a", Duration.ofSeconds(60))).isTrue();
        // 已存在时不能再写入
        assertThat(client.setIfAbsent("lock1", "b", Duration.ofSeconds(60))).isFalse();
        assertThat(client.get("lock1")).isEqualTo("a");
    }

    @Test
    void expireAndTtl() {
        client.set("k1", "v1", Duration.ofSeconds(100));

        assertThat(client.ttl("k1")).isGreaterThan(0).isLessThanOrEqualTo(100);
        assertThat(client.expire("k1", Duration.ofSeconds(5))).isTrue();
        assertThat(client.ttl("k1")).isLessThanOrEqualTo(5);
    }

    @Test
    void deleteRemovesKey() {
        client.set("k1", "v1", Duration.ofSeconds(60));

        assertThat(client.delete("k1")).isTrue();
        assertThat(client.get("k1")).isNull();
    }

    @Test
    void mgetReturnsValuesInKeyOrderWithNullsForMissing() {
        client.set("k1", "v1", Duration.ofSeconds(60));
        client.set("k3", "v3", Duration.ofSeconds(60));

        List<String> values = client.mget(Arrays.asList("k1", "k2", "k3"));

        assertThat(values).containsExactly("v1", null, "v3");
    }

    @Test
    void mgetEmptyList() {
        assertThat(client.mget(List.of())).isEmpty();
    }

    @Test
    void hashOperations() {
        client.hSet("h1", "f1", "a");
        client.hSet("h1", "f2", "b");

        assertThat(client.hGet("h1", "f1")).isEqualTo("a");
        assertThat(client.hMGet("h1", List.of("f1", "missing", "f2"))).containsExactly("a", null, "b");
        assertThat(client.hDelete("h1", "f1")).isTrue();
        assertThat(client.hGet("h1", "f1")).isNull();
    }

    @Test
    void incrByReturnsIncrementedValue() {
        assertThat(client.incrBy("counter", 5)).isEqualTo(5);
        assertThat(client.incrBy("counter", 3)).isEqualTo(8);
    }
}
