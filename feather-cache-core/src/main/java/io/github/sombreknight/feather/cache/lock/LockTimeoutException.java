package io.github.sombreknight.feather.cache.lock;

import io.github.sombreknight.feather.cache.exception.FeatherCacheException;

import java.time.Duration;

/**
 * 获取分布式锁超时。
 *
 * <p>{@code lock()} 在 {@code wait} 时间内未拿到锁时抛出，调用方可捕获后走降级逻辑
 * （区别于 common-sdk 的"系统繁忙"字符串异常，类型化可处理）。</p>
 *
 * @author sombreknight
 * @since 0.1.0
 */
public class LockTimeoutException extends FeatherCacheException {

    private final String key;
    private final Duration waitTime;

    public LockTimeoutException(String key, Duration waitTime) {
        super("获取分布式锁超时: key=" + key + ", wait=" + waitTime);
        this.key = key;
        this.waitTime = waitTime;
    }

    public String getKey() {
        return key;
    }

    public Duration getWaitTime() {
        return waitTime;
    }
}
