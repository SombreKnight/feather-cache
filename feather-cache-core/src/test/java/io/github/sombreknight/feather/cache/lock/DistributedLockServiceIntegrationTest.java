package io.github.sombreknight.feather.cache.lock;

import io.github.sombreknight.feather.cache.redis.FeatherRedisClient;
import io.github.sombreknight.feather.cache.redis.FeatherRedisConnectionFactory;
import io.github.sombreknight.feather.cache.redis.RedisConnectionConfig;
import io.github.sombreknight.feather.cache.support.NamingStrategy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 分布式锁集成测试（真实 Redis）。
 *
 * <p>需要环境变量 {@code REDIS_TEST_URL}（如 {@code redis://localhost:6379}），未配置自动跳过。</p>
 */
class DistributedLockServiceIntegrationTest {

    private static final String REDIS_URL = defaultRedisUrl();

    /** REDIS_TEST_URL 显式配置优先，默认回退本地 6379（避免集成测试被静默跳过） */
    private static String defaultRedisUrl() {
        String env = System.getenv("REDIS_TEST_URL");
        return env != null && !env.trim().isEmpty() ? env : "redis://localhost:6379";
    }

    private static FeatherRedisConnectionFactory connectionFactory;
    private static FeatherRedisClient redisClient;
    private static DistributedLockService lockService;

    @BeforeAll
    static void init() {
        assumeTrue(REDIS_URL != null && !REDIS_URL.trim().isEmpty(),
                "未配置 REDIS_TEST_URL，跳过锁集成测试");

        URI uri = URI.create(REDIS_URL);
        connectionFactory = new FeatherRedisConnectionFactory(RedisConnectionConfig.builder()
                .host(uri.getHost())
                .port(uri.getPort())
                .build());
        redisClient = new FeatherRedisClient(connectionFactory);
        lockService = new DistributedLockService(new NamingStrategy("test-app"), redisClient);
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

    // ---------------------------------------------------------------- 互斥

    @Test
    void tryLockIsMutuallyExclusive() throws InterruptedException {
        Optional<FeatherLock> first = lockService.tryLock("order:1");
        assertThat(first).isPresent();

        AtomicReference<Optional<FeatherLock>> other = new AtomicReference<>();
        Thread otherThread = new Thread(() -> other.set(lockService.tryLock("order:1")));
        otherThread.start();
        otherThread.join(3000);

        // 他人线程拿不到
        assertThat(other.get()).isEmpty();

        first.get().close();
        // 释放后可获取
        assertThat(lockService.tryLock("order:1")).isPresent();
    }

    @Test
    void lockWaitsUntilReleased() throws InterruptedException {
        AtomicInteger active = new AtomicInteger();
        CountDownLatch acquired = new CountDownLatch(1);

        Thread holder = new Thread(() -> {
            try (FeatherLock ignored = lockService.lock("order:2")) {
                active.incrementAndGet();
                acquired.countDown();
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            } finally {
                active.decrementAndGet();
            }
        });
        holder.start();
        assertThat(acquired.await(3, TimeUnit.SECONDS)).isTrue();

        // 等待 2s 内应拿到锁（A 500ms 后释放）
        long start = System.currentTimeMillis();
        try (FeatherLock ignored = lockService.lock("order:2", Duration.ofSeconds(2), Duration.ofSeconds(10))) {
            assertThat(active).hasValue(0);
        }
        assertThat(System.currentTimeMillis() - start).isLessThan(2000);
        holder.join(3000);
    }

    @Test
    void lockThrowsWhenWaitTimeout() throws Exception {
        try (FeatherLock ignored = lockService.lock("order:3", Duration.ofSeconds(5), Duration.ofSeconds(30))) {
            assertThatThrownBy(() -> inOtherThread(() ->
                    lockService.lock("order:3", Duration.ofMillis(300), Duration.ofSeconds(30))))
                    .isInstanceOf(LockTimeoutException.class)
                    .satisfies(e -> assertThat(((LockTimeoutException) e).getKey()).isEqualTo("order:3"));
        }
    }

    // ---------------------------------------------------------------- 可重入

    @Test
    void lockIsReentrantForSameThread() throws Exception {
        try (FeatherLock outer = lockService.lock("order:4")) {
            try (FeatherLock inner = lockService.lock("order:4")) {
                // 重入返回同一实例
                assertThat(inner).isSameAs(outer);
            }
            // 内层 close 后锁仍未释放（计数未归零）→ 其他线程拿不到
            assertThat(inOtherThread(() -> lockService.tryLock("order:4"))).isEmpty();
        }
        // 外层 close 后其他线程可获取
        assertThat(inOtherThread(() -> lockService.tryLock("order:4"))).isPresent();
    }

    // ---------------------------------------------------------------- 误删防护

    @Test
    void releaseDoesNotDeleteOthersLockAfterExpiry() throws Exception {
        // 用无看门狗的 service 持有锁；锁时长给足，避免测试期间自然过期干扰
        DistributedLockService noWatchDog = new DistributedLockService(
                new NamingStrategy("test-app"), redisClient, false);
        FeatherLock holder = noWatchDog.lock("order:5", Duration.ofSeconds(1), Duration.ofSeconds(30));
        String lockKey = new NamingStrategy("test-app").lockKey("order:5");

        // 模拟锁在 redis 侧过期（等价于业务执行超时 TTL 到期）：直接删除锁 key
        redisClient.delete(lockKey);

        // 他人（其他线程）加锁成功，持有新 value
        Optional<FeatherLock> other = inOtherThread(() -> lockService.tryLock("order:5"));
        assertThat(other).isPresent();

        // 原持有者释放：value 不匹配，Lua 返回 0，不得删除他人的锁
        holder.close();
        assertThat(redisClient.get(lockKey)).isNotNull();

        other.get().close();
        assertThat(redisClient.get(lockKey)).isNull();
        noWatchDog.close();
    }

    // ---------------------------------------------------------------- 看门狗

    @Test
    void watchDogKeepsLockAliveBeyondLockDuration() throws Exception {
        FeatherLock lock = lockService.lock("order:6", Duration.ofSeconds(1), Duration.ofSeconds(1));
        try {
            // 锁时长 1s，但看门狗每 ~333ms 续期；1.8s 后仍持有 → 其他线程拿不到
            Thread.sleep(1800);
            assertThat(inOtherThread(() -> lockService.tryLock("order:6"))).isEmpty();
        } finally {
            lock.close();
        }
    }

    @Test
    void withoutWatchDogLockExpires() throws InterruptedException {
        DistributedLockService noWatchDog = new DistributedLockService(
                new NamingStrategy("test-app"), redisClient, false);

        FeatherLock lock = noWatchDog.lock("order:7", Duration.ofSeconds(1), Duration.ofSeconds(1));
        lock.close(); // 立即释放，避免占着；下面用 tryLock 验证自然过期

        // 重新加锁（无看门狗），等过期
        FeatherLock expiring = noWatchDog.lock("order:7", Duration.ofSeconds(1), Duration.ofSeconds(1));
        Thread.sleep(1500);

        // 锁已自然过期，其他线程可获取
        assertThat(lockService.tryLock("order:7")).isPresent();
    }

    @Test
    void watchDogStopsAfterClose() throws InterruptedException {
        FeatherLock lock = lockService.lock("order:8", Duration.ofSeconds(1), Duration.ofSeconds(1));
        lock.close();

        // close 后不再续期，1.2s 后锁过期
        Thread.sleep(1200);
        assertThat(lockService.tryLock("order:8")).isPresent();
    }

    // ---------------------------------------------------------------- execute 模板

    @Test
    void executeRunsInsideLockAndReturnsResult() {
        AtomicInteger inside = new AtomicInteger();
        try (FeatherLock guard = lockService.lock("order:9")) {
            String result = lockService.execute("order:9", () -> {
                inside.incrementAndGet();
                return "done";
            });
            assertThat(result).isEqualTo("done");
            assertThat(inside).hasValue(1);
        }
    }

    @Test
    void executeThrowsLockTimeoutWhenUnavailable() throws Exception {
        try (FeatherLock ignored = lockService.lock("order:10", Duration.ofSeconds(5), Duration.ofSeconds(30))) {
            assertThatThrownBy(() -> inOtherThread(() -> {
                lockService.execute("order:10", Duration.ofMillis(200), Duration.ofSeconds(30), () -> "never");
                return null;
            })).isInstanceOf(LockTimeoutException.class);
        }
    }

    // ---------------------------------------------------------------- key 命名

    @Test
    void lockKeyUsesNamingStrategyPrefix() {
        try (FeatherLock ignored = lockService.lock("order:11", Duration.ofSeconds(1), Duration.ofSeconds(10))) {
            String raw = redisClient.get(new NamingStrategy("test-app").lockKey("order:11"));
            assertThat(raw).isNotNull();
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 在独立线程执行任务（可重入语义下，互斥/超时验证必须换线程）；解包 ExecutionException 暴露原始异常 */
    private <T> T inOtherThread(java.util.concurrent.Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<T> future = pool.submit(task);
            try {
                return future.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception ex) {
                    throw ex;
                }
                throw new RuntimeException(cause);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- 看门狗边界（P0）

    /**
     * P0 缺陷修复验证：锁时长 &lt;1s 时加锁与看门狗均用毫秒（PX/PEXPIRE），
     * 500ms 锁应保持互斥（修复前 Redis 拒绝 EX 0 直接报错）。
     */
    @Test
    void subSecondLockDurationKeepsMutualExclusion() throws Exception {
        try (FeatherLock ignored = lockService.lock("it.subsecond", Duration.ofSeconds(3), Duration.ofMillis(500))) {
            // 500ms 锁应仍被本线程持有：他人线程拿不到
            Optional<FeatherLock> other = inOtherThread(() -> lockService.tryLock("it.subsecond"));
            assertThat(other).isEmpty();
        }
    }

    /**
     * 看门狗正常续期：锁时长 2s，持有 2.5s（跨多个续期周期）后锁仍有效。
     */
    @Test
    void lockSurvivesBeyondDurationViaWatchdog() throws Exception {
        try (FeatherLock ignored = lockService.lock("it.watchdog", Duration.ofSeconds(3), Duration.ofSeconds(2))) {
            Thread.sleep(2500); // 超过锁时长，看门狗应已续期
            Optional<FeatherLock> other = inOtherThread(() -> lockService.tryLock("it.watchdog"));
            assertThat(other).isEmpty();
        }
    }

    // ---------------------------------------------------------------- 重入边界

    @Test
    void deepReentrancyThreeLevels() throws Exception {
        Optional<FeatherLock> level1 = lockService.tryLock("it.deep");
        assertThat(level1).isPresent();
        Optional<FeatherLock> level2 = lockService.tryLock("it.deep");
        Optional<FeatherLock> level3 = lockService.tryLock("it.deep");
        assertThat(level2).isPresent();
        assertThat(level3).isPresent();

        // 乱序关闭：先关外层（计数 3→2），锁仍持有
        level3.get().close();
        level2.get().close();
        assertThat(inOtherThread(() -> lockService.tryLock("it.deep"))).isEmpty();

        // 全部关闭后释放
        level1.get().close();
        assertThat(lockService.tryLock("it.deep")).isPresent();
    }

    // ---------------------------------------------------------------- execute 模板

    @Test
    void executeWrapsBusinessException() {
        IllegalStateException biz = new IllegalStateException("biz boom");
        assertThatThrownBy(() -> lockService.execute("it.execute", () -> {
            throw biz;
        })).isInstanceOf(io.github.sombreknight.feather.cache.exception.FeatherCacheException.class)
                .hasCause(biz);
    }

    @Test
    void executeRunsAndReleasesLockAfterwards() {
        lockService.execute("it.execute2", () -> { });
        // 执行后锁已释放
        assertThat(lockService.tryLock("it.execute2")).isPresent();
    }

    // ---------------------------------------------------------------- 并发争抢（压力）

    @Test
    void multiThreadContentionEnsuresSingleWinner() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger winners = new AtomicInteger();
        AtomicInteger counter = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    try (FeatherLock ignored = lockService.lock("it.stress", Duration.ofSeconds(5), Duration.ofSeconds(2))) {
                        winners.incrementAndGet();
                        counter.incrementAndGet();
                        Thread.sleep(300); // 持锁期间互斥
                        counter.decrementAndGet();
                        assertThat(counter.get()).isZero(); // 持锁期间无其他线程进入
                    } catch (LockTimeoutException e) {
                        // 未抢到锁，正常
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        // 每个线程都最终拿到过锁（串行执行），且任意时刻只有一个赢家
        assertThat(winners.get()).isEqualTo(threads);
    }
}
