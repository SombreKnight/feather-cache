package io.github.sombreknight.feather.cache.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    private static final String REDIS_URL = defaultRedisUrl();

    /** REDIS_TEST_URL 显式配置优先，默认回退本地 6379（避免集成测试被静默跳过） */
    private static String defaultRedisUrl() {
        String env = System.getenv("REDIS_TEST_URL");
        return env != null && !env.trim().isEmpty() ? env : "redis://localhost:6379";
    }

    private static FeatherRedisConnectionFactory connectionFactory;
    private static FeatherRedisClient client;

    @BeforeAll
    static void init() {
        assumeTrue(REDIS_URL != null && !REDIS_URL.trim().isEmpty(),
                "未配置 REDIS_TEST_URL，跳过 Redis 集成测试（CI 服务容器会自动启用）");

        URI uri = URI.create(REDIS_URL);
        connectionFactory = new FeatherRedisConnectionFactory(RedisConnectionConfig.builder()
                .host(uri.getHost())
                .port(uri.getPort())
                .build());
        client = new FeatherRedisClient(connectionFactory);
    }

    @AfterAll
    static void destroy() {
        if (connectionFactory != null) {
            connectionFactory.close();
        }
    }

    @BeforeEach
    void cleanUp() {
        assumeTrue(REDIS_URL != null && !REDIS_URL.trim().isEmpty(), "跳过");
        connectionFactory.flushAll();
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

    // ---------------------------------------------------------------- 亚秒级 TTL（P0 缺陷回归）

    /**
     * P0 缺陷回归：TTL &lt;1s 时 SET 必须走 PX 毫秒。
     * 修复前（1.0.0 发布产物）用 EX + toSeconds()，500ms 取整为 0 → Redis 拒绝 ERR invalid expire time。
     */
    @Test
    void subSecondTtlOnSetAcceptedAndKeyExpires() throws InterruptedException {
        client.set("k-sub", "v", Duration.ofMillis(500)); // 修复前此处抛 FeatherCacheException

        assertThat(client.get("k-sub")).isEqualTo("v");
        Thread.sleep(800);
        assertThat(client.get("k-sub")).isNull(); // 500ms 后应已过期
    }

    /**
     * P0 缺陷回归：锁加锁路径 SET NX + 亚秒级 TTL（PX）。
     */
    @Test
    void subSecondTtlOnSetIfAbsentLockExpires() throws InterruptedException {
        assertThat(client.setIfAbsent("lock-sub", "a", Duration.ofMillis(500))).isTrue();
        assertThat(client.get("lock-sub")).isEqualTo("a");
        Thread.sleep(800);
        assertThat(client.get("lock-sub")).isNull(); // 锁 500ms 后自动释放
    }

    /**
     * P0 缺陷回归：expire 走 PEXPIRE 毫秒，亚秒级续期生效。
     */
    @Test
    void subSecondTtlOnExpireCommandExpires() throws InterruptedException {
        client.set("k-sub-exp", "v", Duration.ofSeconds(60));

        assertThat(client.expire("k-sub-exp", Duration.ofMillis(500))).isTrue();
        Thread.sleep(800);
        assertThat(client.get("k-sub-exp")).isNull();
    }

    /**
     * 精度回归：1.5s TTL 实际生效约 1.5s（PX 毫秒），而非被 toSeconds() 静默截断为 1s。
     */
    @Test
    void millisecondPrecisionTtlAboveOneSecond() throws InterruptedException {
        client.set("k-precision", "v", Duration.ofMillis(1500));

        // 1.2s 后（>1s，<1.5s）key 必须仍存在：证明 TTL 是 1.5s 而非 1s
        Thread.sleep(1200);
        assertThat(client.get("k-precision")).isEqualTo("v");
        // 再过 700ms（共 1.9s > 1.5s）应已过期
        Thread.sleep(700);
        assertThat(client.get("k-precision")).isNull();
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
