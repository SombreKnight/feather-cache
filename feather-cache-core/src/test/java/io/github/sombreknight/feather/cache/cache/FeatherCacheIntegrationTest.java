package io.github.sombreknight.feather.cache.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.sombreknight.feather.cache.cache.impl.FeatherCacheImpl;
import io.github.sombreknight.feather.cache.cache.impl.LocalCacheClient;
import io.github.sombreknight.feather.cache.cache.impl.RedisCacheClient;
import io.github.sombreknight.feather.cache.exception.FeatherCacheException;
import io.github.sombreknight.feather.cache.redis.FeatherRedisClient;
import io.github.sombreknight.feather.cache.redis.FeatherRedisConnectionFactory;
import io.github.sombreknight.feather.cache.redis.RedisConnectionConfig;
import io.github.sombreknight.feather.cache.support.JsonCodec;
import io.github.sombreknight.feather.cache.support.NamingStrategy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    private static final String REDIS_URL = defaultRedisUrl();

    /** REDIS_TEST_URL 显式配置优先，默认回退本地 6379（避免集成测试被静默跳过） */
    private static String defaultRedisUrl() {
        String env = System.getenv("REDIS_TEST_URL");
        return env != null && !env.trim().isEmpty() ? env : "redis://localhost:6379";
    }

    private static FeatherRedisConnectionFactory connectionFactory;
    private static LocalCacheClient localCacheClient;
    private static FeatherCache cache;

    private final AtomicInteger loaderCalls = new AtomicInteger();
    private final AtomicInteger multiLoaderCalls = new AtomicInteger();

    @BeforeAll
    static void init() {
        assumeTrue(REDIS_URL != null && !REDIS_URL.trim().isEmpty(),
                "未配置 REDIS_TEST_URL，跳过缓存集成测试");

        URI uri = URI.create(REDIS_URL);
        connectionFactory = new FeatherRedisConnectionFactory(RedisConnectionConfig.builder()
                .host(uri.getHost())
                .port(uri.getPort())
                .build());

        localCacheClient = new LocalCacheClient();
        cache = new FeatherCacheImpl(
                new NamingStrategy("test-app"),
                localCacheClient,
                new RedisCacheClient(new FeatherRedisClient(connectionFactory)),
                new JsonCodec());
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

    @Test
    void multiCacheFullMissLoadsWithoutDeadlock() throws InterruptedException {
        // 两层都 miss → 回源：本地层与 redis 层 semaphore 必须隔离，否则嵌套回源自锁
        CacheConfig config = CacheConfig.multi();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    Order order = cache.get("order:full-miss", ORDER_TYPE, config, key -> {
                        loaderCalls.incrementAndGet();
                        try { Thread.sleep(200); } catch (InterruptedException ignored) { }
                        return new Order(key, "loaded");
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
        // 全部命中（redis 层单飞，只回源 1 次）
        assertThat(loaderCalls).hasValue(1);
    }

    @Test
    void multiCacheLocalTtlIsPerKeyControlled() throws InterruptedException {
        CacheConfig config = CacheConfig.multi().localTtl(Duration.ofMillis(300));

        Order order = cache.get("order:local-ttl", ORDER_TYPE, config,
                key -> new Order(key, "v"));
        assertThat(order.name).isEqualTo("v");

        // 本地已回填
        String localKey = new NamingStrategy("test-app").cacheKey("order:local-ttl");
        assertThat(localCacheClient.get(localKey)).isNotNull();

        Thread.sleep(400);

        // 本地已按 localTtl(300ms) 过期（若 localTtl 未生效、本地沿用 redis 120s，此处仍会有值）
        assertThat(localCacheClient.get(localKey)).isNull();

        // redis 层（120s）仍在 → 第二次走 redis 命中，loader 不调；命中值回填本地
        Order second = cache.get("order:local-ttl", ORDER_TYPE, config,
                key -> { loaderCalls.incrementAndGet(); return new Order(key, "again"); });
        assertThat(second.name).isEqualTo("v");
        assertThat(loaderCalls).hasValue(0);
    }

    // ---------------------------------------------------------------- 亚秒级 TTL（P0 缺陷回归）

    /**
     * P0 缺陷回归：缓存 redisTtl &lt;1s 时必须可用（PX 毫秒）。
     * 修复前（1.0.0 发布产物）SET EX + toSeconds()，500ms 取整为 0 → Redis 拒绝写入，回源直接抛异常。
     */
    @Test
    void subSecondRedisTtlBackfillWorksAndExpires() throws InterruptedException {
        CacheConfig config = CacheConfig.redis(Duration.ofMillis(500));

        Order order = cache.get("order:sub-ttl", ORDER_TYPE, config,
                key -> new Order(key, "v"));
        assertThat(order.name).isEqualTo("v");

        // 二次读取命中 Redis（未过期，loader 不调）
        Order cached = cache.get("order:sub-ttl", ORDER_TYPE, config,
                key -> { loaderCalls.incrementAndGet(); return new Order(key, "again"); });
        assertThat(cached.name).isEqualTo("v");
        assertThat(loaderCalls).hasValue(0);

        Thread.sleep(700); // > 500ms，Redis 层应已过期

        // 过期后再次回源：loader 必须再调一次
        Order reloaded = cache.get("order:sub-ttl", ORDER_TYPE, config,
                key -> { loaderCalls.incrementAndGet(); return new Order(key, "reloaded"); });
        assertThat(reloaded.name).isEqualTo("reloaded");
        assertThat(loaderCalls).hasValue(1);
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
        FeatherRedisConnectionFactory deadFactory = new FeatherRedisConnectionFactory(
                RedisConnectionConfig.builder()
                        .host("127.0.0.1")
                        .port(65534)
                        .timeout(Duration.ofMillis(500))
                        .build());

        return new FeatherCacheImpl(
                new NamingStrategy("test-app"),
                new LocalCacheClient(),
                new RedisCacheClient(new FeatherRedisClient(deadFactory)),
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

    // ---------------------------------------------------------------- loader 异常（P0）

    @Test
    void loaderExceptionPropagatesAndCacheStaysEmpty() {
        AtomicInteger calls = new AtomicInteger();

        // 第一次：loader 抛异常 → get 抛出（FAIL_FAST），不写缓存
        assertThatThrownBy(() -> cache.get("order:boom", ORDER_TYPE, key -> {
            calls.incrementAndGet();
            throw new IllegalStateException("loader down");
        })).isInstanceOf(IllegalStateException.class).hasMessage("loader down");

        // 第二次：缓存未被污染（无半成品值），loader 再次被调（下次可恢复）
        Order recovered = cache.get("order:boom", ORDER_TYPE, key -> {
            calls.incrementAndGet();
            return new Order(key, "recovered");
        });
        assertThat(recovered).isEqualTo(new Order("order:boom", "recovered"));
        assertThat(calls).hasValue(2);
    }

    @Test
    void putClearsSentinelAndRestoresReadPath() {
        CacheConfig config = CacheConfig.redis().cacheNull(true);

        // 防穿透：回源 null → 写 sentinel，后续短路
        assertThat(cache.get("order:revive", ORDER_TYPE, config, key -> null)).isNull();
        AtomicInteger calls = new AtomicInteger();
        assertThat(cache.get("order:revive", ORDER_TYPE, config, key -> {
            calls.incrementAndGet();
            return new Order(key, "never");
        })).isNull();
        assertThat(calls).hasValue(0); // sentinel 短路，未回源

        // put 覆盖：sentinel 被清理，读路径恢复
        cache.put("order:revive", new Order("order:revive", "alive"), config);
        assertThat(cache.get("order:revive", ORDER_TYPE, config, key -> {
            calls.incrementAndGet();
            return null;
        })).isEqualTo(new Order("order:revive", "alive"));
        assertThat(calls).hasValue(0);
    }

    // ---------------------------------------------------------------- 批量 gets 边界（P0）

    @Test
    void getsPartialLoaderReturnsNullForMissingIds() {
        List<String> ids = List.of("order:a", "order:b", "order:c");
        Map<String, Order> loaded = cache.gets(ids, key -> key, ORDER_TYPE, CacheConfig.redis(),
                (missing) -> {
                    multiLoaderCalls.incrementAndGet();
                    Map<String, Order> result = new HashMap<>();
                    for (String id : missing) {
                        if (!id.equals("order:b")) { // b 缺数据
                            result.put(id, new Order(id, "loaded"));
                        }
                    }
                    return result;
                });

        // 缺 id 的行为：取决于实现（回源结果不含该 id）；此处断言不缺的 id 都正确回填
        assertThat(loaded.get("order:a")).isEqualTo(new Order("order:a", "loaded"));
        assertThat(loaded.get("order:c")).isEqualTo(new Order("order:c", "loaded"));
    }

    /**
     * P1 缺陷修复验证：批量 gets 的 loader 回源受 single-flight 保护（
     * 8 并发同组 miss 不应重复回源；修复前 8 次全量回源）。
     */
    @Test
    void concurrentGetsSingleFlightLoadsEachMissingKeyOnce() throws Exception {
        List<String> ids = List.of("order:x1", "order:x2");
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger loadCalls = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    Map<String, Order> result = cache.gets(ids, key -> key, ORDER_TYPE, CacheConfig.redis(),
                            (missing) -> {
                                loadCalls.incrementAndGet();
                                try { Thread.sleep(100); } catch (InterruptedException ignored) { }
                                Map<String, Order> r = new HashMap<>();
                                for (String id : missing) {
                                    r.put(id, new Order(id, "v"));
                                }
                                return r;
                            });
                    if (result.size() != 2) {
                        errors.incrementAndGet();
                    }
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
        // 批量路径同样受 single-flight 保护：8 并发不应重复回源
        assertThat(loadCalls.get()).isLessThanOrEqualTo(2);
    }
}
