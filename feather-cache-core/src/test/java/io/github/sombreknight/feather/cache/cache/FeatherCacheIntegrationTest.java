package io.github.sombreknight.feather.cache.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.sombreknight.feather.cache.cache.impl.FeatherCacheImpl;
import io.github.sombreknight.feather.cache.cache.impl.LocalCacheClient;
import io.github.sombreknight.feather.cache.cache.impl.RedisCacheClient;
import io.github.sombreknight.feather.cache.exception.FeatherCacheException;
import io.github.sombreknight.feather.cache.redis.FeatherRedisClient;
import io.github.sombreknight.feather.cache.support.JsonCodec;
import io.github.sombreknight.feather.cache.support.NamingStrategy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 多级缓存集成测试（真实 Redis）。
 *
 * <p>需要环境变量 {@code REDIS_TEST_URL}（如 {@code redis://localhost:6379}），未配置自动跳过。</p>
 */
class FeatherCacheIntegrationTest {

    private static final TypeReference<Order> ORDER_TYPE = new TypeReference<>() {};

    private static final String REDIS_URL = System.getenv("REDIS_TEST_URL");

    private static StringRedisTemplate redisTemplate;
    private static FeatherCache cache;

    private final AtomicInteger loaderCalls = new AtomicInteger();
    private final AtomicInteger multiLoaderCalls = new AtomicInteger();

    @BeforeAll
    static void init() {
        assumeTrue(REDIS_URL != null && !REDIS_URL.trim().isEmpty(),
                "未配置 REDIS_TEST_URL，跳过缓存集成测试");

        URI uri = URI.create(REDIS_URL);
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(uri.getHost(), uri.getPort()));
        factory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(factory);
        redisTemplate.afterPropertiesSet();

        cache = new FeatherCacheImpl(
                new NamingStrategy("test-app"),
                new LocalCacheClient(),
                new RedisCacheClient(new FeatherRedisClient(redisTemplate)),
                new JsonCodec());
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
        loaderCalls.set(0);
        multiLoaderCalls.set(0);
    }

    // ---------------------------------------------------------------- 基础

    @Test
    void getMissLoadsAndBackfills() {
        Order order = cache.get("order:1", ORDER_TYPE,
                key -> new Order(key, "miss-1"));

        assertThat(order).isEqualTo(new Order("order:1", "miss-1"));
        // 二次读取命中缓存，不再回源
        Order cached = cache.get("order:1", ORDER_TYPE,
                key -> { loaderCalls.incrementAndGet(); return null; });
        assertThat(cached).isEqualTo(new Order("order:1", "miss-1"));
        assertThat(loaderCalls).hasValue(0);
    }

    @Test
    void getReturnsNullWhenLoaderReturnsNullWithoutCacheNull() {
        Order result = cache.get("order:missing", ORDER_TYPE, key -> null);

        assertThat(result).isNull();
    }

    // ---------------------------------------------------------------- 防击穿

    @Test
    void concurrentMissSingleFlightLoadsOnce() throws InterruptedException {
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    Order order = cache.get("order:hot", ORDER_TYPE, key -> {
                        loaderCalls.incrementAndGet();
                        try { Thread.sleep(300); } catch (InterruptedException ignored) { }
                        return new Order(key, "hot");
                    });
                    if (order == null) errors.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(errors).hasValue(0);
        // single-flight：20 并发只回源 1 次
        assertThat(loaderCalls).hasValue(1);
    }

    // ---------------------------------------------------------------- 防穿透

    @Test
    void cacheNullWritesSentinelAndShortCircuits() {
        CacheConfig config = CacheConfig.redis().cacheNull(true);

        // 第一次：回源返回 null → 写空值占位
        assertThat(cache.get("order:none", ORDER_TYPE, config, key -> {
            loaderCalls.incrementAndGet();
            return null;
        })).isNull();
        assertThat(loaderCalls).hasValue(1);

        // 后续：sentinel 命中直接返回 null，不再回源
        assertThat(cache.get("order:none", ORDER_TYPE, config, key -> {
            loaderCalls.incrementAndGet();
            return null;
        })).isNull();
        assertThat(loaderCalls).hasValue(1);
    }

    @Test
    void evictClearsSentinelSoLoaderRunsAgain() {
        CacheConfig config = CacheConfig.redis().cacheNull(true);
        cache.get("order:none", ORDER_TYPE, config, key -> { loaderCalls.incrementAndGet(); return null; });
        assertThat(loaderCalls).hasValue(1);

        cache.evict("order:none");

        cache.get("order:none", ORDER_TYPE, config, key -> { loaderCalls.incrementAndGet(); return null; });
        assertThat(loaderCalls).hasValue(2);
    }

    // ---------------------------------------------------------------- 批量

    @Test
    void getsHitsInCacheAndLoadsOnlyMissing() {
        // 预置 2 个缓存
        cache.put("order:1", new Order("order:1", "cached-1"), CacheConfig.redis());

        Map<String, Order> result = cache.gets(
                List.of("order:1", "order:2", "order:3"),
                Function.identity(),
                ORDER_TYPE,
                CacheConfig.redis(),
                missing -> {
                    multiLoaderCalls.incrementAndGet();
                    Map<String, Order> loaded = new HashMap<>();
                    for (String id : missing) {
                        loaded.put(id, new Order(id, "loaded-" + id));
                    }
                    return loaded;
                });

        assertThat(result).hasSize(3);
        assertThat(result.get("order:1").name).isEqualTo("cached-1");
        assertThat(result.get("order:2").name).isEqualTo("loaded-order:2");
        assertThat(result.get("order:3").name).isEqualTo("loaded-order:3");
        // 只回源 1 次且只传缺失 ids
        assertThat(multiLoaderCalls).hasValue(1);
    }

    @Test
    void getsReturnsEmptyForEmptyIds() {
        Map<String, Order> result = cache.gets(List.of(), Function.identity(), ORDER_TYPE,
                CacheConfig.redis(), missing -> Map.of());

        assertThat(result).isEmpty();
    }

    @Test
    void getsWithCacheNullShortCircuitsOnSentinel() {
        CacheConfig config = CacheConfig.redis().cacheNull(true);
        // 预置空值占位
        cache.get("order:none", ORDER_TYPE, config, key -> { loaderCalls.incrementAndGet(); return null; });

        Map<String, Order> result = cache.gets(List.of("order:none"), Function.identity(), ORDER_TYPE, config,
                missing -> { multiLoaderCalls.incrementAndGet(); return Map.of(); });

        assertThat(result).containsEntry("order:none", null);
        assertThat(multiLoaderCalls).hasValue(0);
    }

    // ---------------------------------------------------------------- 多级缓存

    @Test
    void localFirstHitsLocalAndSkipsRedis() {
        CacheConfig config = CacheConfig.multi();
        // 写入 redis（模拟其他实例写入）
        cache.put("order:1", new Order("order:1", "redis-data"), CacheConfig.redis());

        Order order = cache.get("order:1", ORDER_TYPE, config, key -> {
            loaderCalls.incrementAndGet();
            return new Order(key, "loaded");
        });

        // 首次：本地 miss → redis 命中
        assertThat(order.name).isEqualTo("redis-data");
        assertThat(loaderCalls).hasValue(0);

        // 再次：本地已回填，短路
        Order second = cache.get("order:1", ORDER_TYPE, config, key -> {
            loaderCalls.incrementAndGet();
            return new Order(key, "loaded");
        });
        assertThat(second.name).isEqualTo("redis-data");
    }

    // ---------------------------------------------------------------- 降级

    @Test
    void failFastThrowsWhenRedisDown() {
        FeatherCache downCache = buildCacheAgainstDownRedis();

        assertThatThrownBy(() -> downCache.get("k", ORDER_TYPE, key -> new Order(key, "x")))
                .isInstanceOf(FeatherCacheException.class);
    }

    @Test
    void returnNullDegradesWhenRedisDown() {
        FeatherCache downCache = buildCacheAgainstDownRedis();
        CacheConfig config = CacheConfig.redis().readMode(CacheReadMode.RETURN_NULL);

        Order result = downCache.get("k", ORDER_TYPE, config, key -> new Order(key, "x"));

        assertThat(result).isNull();
    }

    @Test
    void fallbackLocalServesFromLocalWhenRedisDown() {
        FeatherCache downCache = buildCacheAgainstDownRedis();
        CacheConfig config = CacheConfig.redis().readMode(CacheReadMode.FALLBACK_LOCAL);

        // 回源一次（写入本地），随后本地可服务
        Order first = downCache.get("k", ORDER_TYPE, config, key -> new Order(key, "local"));
        assertThat(first.name).isEqualTo("local");

        // loader 不再被调用（本地命中）
        Order second = downCache.get("k", ORDER_TYPE, config, key -> {
            loaderCalls.incrementAndGet();
            return new Order(key, "again");
        });
        assertThat(second.name).isEqualTo("local");
        assertThat(loaderCalls).hasValue(0);
    }

    // ---------------------------------------------------------------- 工具

    /** 构造一个指向不可达端口的缓存（模拟 Redis 故障） */
    private FeatherCache buildCacheAgainstDownRedis() {
        LettuceConnectionFactory deadFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 65534));
        deadFactory.setTimeout(500L);
        deadFactory.afterPropertiesSet();

        StringRedisTemplate deadTemplate = new StringRedisTemplate(deadFactory);
        deadTemplate.afterPropertiesSet();

        return new FeatherCacheImpl(
                new NamingStrategy("test-app"),
                new LocalCacheClient(),
                new RedisCacheClient(new FeatherRedisClient(deadTemplate)),
                new JsonCodec());
    }

    // ---------------------------------------------------------------- 测试数据

    static class Order {
        public String id;
        public String name;

        public Order() { }

        public Order(String id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Order order)) return false;
            return java.util.Objects.equals(id, order.id) && java.util.Objects.equals(name, order.name);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(id, name);
        }
    }
}
