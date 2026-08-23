package io.github.sombreknight.feather.cache.lock;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Redis 分布式锁实现（不可重入语义由 {@link DistributedLockService} 的注册表保证，
 * 本类只负责：看门狗续期 + 原子释放）。
 *
 * <p><b>看门狗</b>：持有期间按 {@code lockDuration / 3} 周期续期（默认 30s 锁 → 每 10s 续一次），
 * 业务执行超过锁时长也不会提前失锁；{@link #close()} 时取消续期并原子释放。</p>
 *
 * <p>释放与续期均为 Lua compare-and-delete / compare-and-expire：value 不匹配（锁已易主）
 * 时不做任何操作，杜绝误删他人锁。</p>
 *
 * @author sombreknight
 * @since 0.1.0
 */
public class RedisFeatherLock implements FeatherLock {

    private final String key;
    private final String lockKey;
    private final String value;
    private final Duration lockDuration;
    private final DistributedLockService owner;
    private final ScheduledExecutorService watchdogExecutor;

    private volatile boolean released = false;
    private volatile ScheduledFuture<?> renewTask;

    RedisFeatherLock(String key, String lockKey, String value, Duration lockDuration,
                     DistributedLockService owner, ScheduledExecutorService watchdogExecutor) {
        this.key = key;
        this.lockKey = lockKey;
        this.value = value;
        this.lockDuration = lockDuration;
        this.owner = owner;
        this.watchdogExecutor = watchdogExecutor;
    }

    @Override
    public String getKey() {
        return key;
    }

    /**
     * 启动看门狗续期（由 DistributedLockService 在加锁成功后调用）。
     */
    void startWatchDog() {
        long intervalMillis = Math.max(lockDuration.toMillis() / 3, 100L);
        renewTask = watchdogExecutor.scheduleAtFixedRate(() -> {
            if (!released) {
                owner.renew(lockKey, value, lockDuration);
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    void stopWatchDog() {
        ScheduledFuture<?> task = renewTask;
        if (task != null) {
            task.cancel(false);
            renewTask = null;
        }
    }

    @Override
    public void close() {
        if (released) {
            return;
        }
        // 是否真正释放由 DistributedLockService 按重入计数裁决（内层 close 只递减计数）
        owner.release(this);
    }

    void markReleased() {
        this.released = true;
    }

    String getLockKey() {
        return lockKey;
    }

    String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RedisFeatherLock that)) {
            return false;
        }
        return lockKey.equals(that.lockKey) && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lockKey, value);
    }
}
