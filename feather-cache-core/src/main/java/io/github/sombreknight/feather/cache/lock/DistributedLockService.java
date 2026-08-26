package io.github.sombreknight.feather.cache.lock;

import io.github.sombreknight.feather.cache.exception.FeatherCacheException;
import io.github.sombreknight.feather.cache.redis.FeatherRedisClient;
import io.github.sombreknight.feather.cache.support.NamingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分布式锁服务。
 *
 * <p>API 面（方案 B 定稿）：</p>
 * <ul>
 *     <li>{@link #lock(String)} / {@link #lock(String, Duration, Duration)}：阻塞获取，
 *         超时抛 {@link LockTimeoutException}（fail-fast，不会忘判）</li>
 *     <li>{@link #tryLock(String)}：非阻塞获取，返回 {@link Optional}，拿不到即 empty</li>
 *     <li>{@link #execute(String, ...)}：模板方法，内部 try-with-resources</li>
 * </ul>
 *
 * <p><b>可重入</b>：同一线程重复 lock 同一 key 返回同一锁实例（计数 +1），close 对齐减
 * 计数，归零才真正释放；线程持有的锁记录在 ThreadLocal 注册表，close 时随计数归零
 * 立即清理（区别于 common-sdk 永不清理导致的累积泄漏）。</p>
 *
 * <p><b>看门狗</b>：默认开启，锁持有期间自动续期；可整体关闭（{@code enableWatchDog(false)}）。</p>
 *
 * @author sombreknight
 * @since 0.1.0
 */
public class DistributedLockService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockService.class);

    /** 默认等待时间：3 秒 */
    public static final Duration DEFAULT_WAIT = Duration.ofSeconds(3);

    /** 默认锁时长：30 秒 */
    public static final Duration DEFAULT_LOCK = Duration.ofSeconds(30);

    /** 自旋轮询间隔：50ms */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private final NamingStrategy namingStrategy;
    private final FeatherRedisClient redisClient;
    private final boolean enableWatchDog;
    private final Duration defaultWait;
    private final Duration defaultLockDuration;
    private final ScheduledExecutorService watchdogExecutor;

    /**
     * 线程持有的锁注册表：lockKey → 持有计数。
     * close 时计数归零即移除，线程销毁时整体回收，无累积泄漏。
     */
    private final ThreadLocal<Map<String, LockEntry>> heldLocks = ThreadLocal.withInitial(HashMap::new);

    private final AtomicInteger lockCounter = new AtomicInteger();

    public DistributedLockService(NamingStrategy namingStrategy, FeatherRedisClient redisClient) {
        this(namingStrategy, redisClient, true, DEFAULT_WAIT, DEFAULT_LOCK);
    }

    public DistributedLockService(NamingStrategy namingStrategy, FeatherRedisClient redisClient,
                                  boolean enableWatchDog) {
        this(namingStrategy, redisClient, enableWatchDog, DEFAULT_WAIT, DEFAULT_LOCK);
    }

    public DistributedLockService(NamingStrategy namingStrategy, FeatherRedisClient redisClient,
                                  boolean enableWatchDog, Duration defaultWait, Duration defaultLockDuration) {
        this.namingStrategy = namingStrategy;
        this.redisClient = redisClient;
        this.enableWatchDog = enableWatchDog;
        this.defaultWait = defaultWait;
        this.defaultLockDuration = defaultLockDuration;
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "feather-lock-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        this.watchdogExecutor = executor;
    }

    // ---------------------------------------------------------------- lock

    /**
     * 阻塞获取分布式锁（默认等 3s / 锁 30s，可经 {@code feather.cache.lock.*} 配置），超时抛
     * {@link LockTimeoutException}。
     */
    public FeatherLock lock(String key) {
        return lock(key, defaultWait, defaultLockDuration);
    }

    /**
     * 阻塞获取分布式锁（自旋轮询），超时抛 {@link LockTimeoutException}。
     *
     * @param wait         等待获取的超时时间
     * @param lockDuration 锁持有时长（看门狗开启时会被自动续期）
     */
    public FeatherLock lock(String key, Duration wait, Duration lockDuration) {
        String lockKey = namingStrategy.lockKey(key);

        // 可重入：本线程已持有 → 计数 +1，返回同一实例
        LockEntry existing = heldLocks.get().get(lockKey);
        if (existing != null) {
            existing.count++;
            return existing.lock;
        }

        String value = generateValue();
        long deadlineNanos = System.nanoTime() + wait.toNanos();
        while (true) {
            if (redisClient.setIfAbsent(lockKey, value, lockDuration)) {
                return registerAndStart(lockKey, key, value, lockDuration);
            }
            if (System.nanoTime() >= deadlineNanos) {
                throw new LockTimeoutException(key, wait);
            }
            sleep(POLL_INTERVAL);
        }
    }

    /**
     * 非阻塞获取分布式锁（拿不到立即返回 empty，不自旋）。
     */
    public Optional<FeatherLock> tryLock(String key) {
        return tryLock(key, defaultLockDuration);
    }

    /**
     * 非阻塞获取分布式锁（指定锁时长）。
     */
    public Optional<FeatherLock> tryLock(String key, Duration lockDuration) {
        String lockKey = namingStrategy.lockKey(key);

        LockEntry existing = heldLocks.get().get(lockKey);
        if (existing != null) {
            existing.count++;
            return Optional.of(existing.lock);
        }

        String value = generateValue();
        if (redisClient.setIfAbsent(lockKey, value, lockDuration)) {
            return Optional.of(registerAndStart(lockKey, key, value, lockDuration));
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- execute 模板方法

    /**
     * 锁内执行（try-with-resources 语法糖），拿不到锁抛 {@link LockTimeoutException}。
     */
    public void execute(String key, Runnable action) {
        execute(key, defaultWait, defaultLockDuration, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 锁内执行并返回结果。
     */
    public <T> T execute(String key, java.util.concurrent.Callable<T> action) {
        return execute(key, defaultWait, defaultLockDuration, action);
    }

    /**
     * 锁内执行并返回结果（自定义等待与锁时长）。
     */
    public <T> T execute(String key, Duration wait, Duration lockDuration, java.util.concurrent.Callable<T> action) {
        try (FeatherLock ignored = lock(key, wait, lockDuration)) {
            return action.call();
        } catch (FeatherCacheException e) {
            throw e;
        } catch (Exception e) {
            throw new FeatherCacheException("锁内业务执行失败: key=" + key, e);
        }
    }

    // ---------------------------------------------------------------- 内部

    private FeatherLock registerAndStart(String lockKey, String key, String value, Duration lockDuration) {
        RedisFeatherLock lock = new RedisFeatherLock(key, lockKey, value, lockDuration, this, watchdogExecutor);
        if (enableWatchDog) {
            lock.startWatchDog();
        }
        heldLocks.get().put(lockKey, new LockEntry(lock, 1));
        lockCounter.incrementAndGet();
        return lock;
    }

    /**
     * 看门狗续期（RedisFeatherLock 定时调用；Lua compare-and-expire，锁已易主则停止）。
     */
    void renew(String lockKey, String value, Duration lockDuration) {
        try {
            redisClient.evalInteger(LockScripts.RENEW, List.of(lockKey),
                    List.of(value, String.valueOf(lockDuration.toSeconds())));
        } catch (Exception e) {
            log.warn("看门狗续期失败，lockKey={}, 原因: {}", lockKey, e.getMessage());
        }
    }

    /**
     * 原子释放（Lua compare-and-delete），并清理线程注册表。
     *
     * <p>可重入：仅当计数归零（最后一个持有者 close）时才执行 Lua 释放；
     * 内层 close 只递减计数，避免把外层还在持有的锁提前释放。</p>
     */
    void release(RedisFeatherLock lock) {
        // 1. 线程注册表递减计数，归零移除（防泄漏）
        Map<String, LockEntry> held = heldLocks.get();
        LockEntry entry = held.get(lock.getLockKey());
        boolean doLuaRelease = true;
        if (entry != null && entry.lock.equals(lock)) {
            entry.count--;
            if (entry.count <= 0) {
                held.remove(lock.getLockKey());
            } else {
                // 仍有外层持有，只递减不释放
                doLuaRelease = false;
            }
        }
        if (held.isEmpty()) {
            heldLocks.remove();
        }
        if (!doLuaRelease) {
            return;
        }
        // 2. 真正释放：先停续期、标记已释放，再 Lua 原子释放（避免"释放后又续期复活"）
        lock.stopWatchDog();
        lock.markReleased();
        try {
            redisClient.evalInteger(LockScripts.UNLOCK, List.of(lock.getLockKey()), List.of(lock.getValue()));
            lockCounter.decrementAndGet();
        } catch (Exception e) {
            log.error("释放分布式锁失败，lockKey={}, 原因: {}", lock.getLockKey(), e.getMessage());
        }
    }

    private String generateValue() {
        // 持有者标识：纯随机值，用于 Lua compare-and-delete 的归属校验
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FeatherCacheException("等待获取锁被中断", e);
        }
    }

    /**
     * 关闭看门狗调度器（Spring 容器销毁时调用）。
     */
    @Override
    public void close() {
        watchdogExecutor.shutdownNow();
    }

    private static class LockEntry {
        final RedisFeatherLock lock;
        int count;

        LockEntry(RedisFeatherLock lock, int count) {
            this.lock = lock;
            this.count = count;
        }
    }
}
