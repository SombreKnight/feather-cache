package io.github.sombreknight.feather.cache.lock;

import io.github.sombreknight.feather.cache.redis.FeatherRedisClient;
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

    private static final String REDIS_URL = System.getenv("REDIS_TEST_URL");

    private static StringRedisTemplate redisTemplate;
    private static FeatherRedisClient redisClient;
    private static DistributedLockService lockService;

    @BeforeAll
    static void init() {
        assumeTrue(REDIS_URL != null && !REDIS_URL.trim().isEmpty(),
                "未配置 REDIS_TEST_URL，跳过锁集成测试");

        URI uri = URI.create(REDIS_URL);
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(uri.getHost(), uri.getPort()));
        factory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(factory);
        redisTemplate.afterPropertiesSet();

        redisClient = new FeatherRedisClient(redisTemplate);
        lockService = new DistributedLockService(new NamingStrategy("test-app"), redisClient);
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
        redisTemplate.delete(lockKey);

        // 他人（其他线程）加锁成功，持有新 value
        Optional<FeatherLock> other = inOtherThread(() -> lockService.tryLock("order:5"));
        assertThat(other).isPresent();

        // 原持有者释放：value 不匹配，Lua 返回 0，不得删除他人的锁
        holder.close();
        assertThat(redisTemplate.opsForValue().get(lockKey)).isNotNull();

        other.get().close();
        assertThat(redisTemplate.opsForValue().get(lockKey)).isNull();
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
            String raw = redisTemplate.opsForValue()
                    .get(new NamingStrategy("test-app").lockKey("order:11"));
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
}
