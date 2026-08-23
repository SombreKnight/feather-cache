package io.github.sombreknight.feather.cache.cache.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.github.sombreknight.feather.cache.cache.CacheClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地缓存客户端：Caffeine 进程内缓存。
 *
 * <p>支持 <b>per-key TTL</b>：每次 {@link #set(String, String, Duration)} 传入的 ttl
 * 决定该 entry 的过期时间（Caffeine {@link Expiry} 实现），构造参数 ttl 仅作兜底默认。
 * 多实例部署下各实例本地缓存相互独立，无跨实例失效机制。</p>
 *
 * @author sombreknight
 * @since 0.1.0
 */
public class LocalCacheClient implements CacheClient {

    /** 默认最大条目数 */
    public static final int DEFAULT_MAX_SIZE = 4096;

    /** 默认过期时间：10 秒 */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(10);

    private final Cache<String, LocalEntry> cache;
    private final Duration defaultTtl;

    public LocalCacheClient() {
        this(DEFAULT_MAX_SIZE, DEFAULT_TTL);
    }

    public LocalCacheClient(int maxSize, Duration defaultTtl) {
        this.defaultTtl = defaultTtl;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfter(new Expiry<String, LocalEntry>() {
                    @Override
                    public long expireAfterCreate(String key, LocalEntry entry, long currentTime) {
                        return entry.ttl.toNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, LocalEntry entry, long currentTime,
                                                  long currentDuration) {
                        // 覆盖写入时按新 ttl 重新计时
                        return entry.ttl.toNanos();
                    }

                    @Override
                    public long expireAfterRead(String key, LocalEntry entry, long currentTime,
                                                long currentDuration) {
                        // 读取不刷新过期时间
                        return currentDuration;
                    }
                })
                .build();
    }

    @Override
    public String get(String key) {
        LocalEntry entry = cache.getIfPresent(key);
        return entry == null ? null : entry.value;
    }

    @Override
    public List<String> mget(List<String> keys) {
        List<String> result = new ArrayList<>(keys.size());
        for (String key : keys) {
            LocalEntry entry = cache.getIfPresent(key);
            result.add(entry == null ? null : entry.value);
        }
        return result;
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        cache.put(key, new LocalEntry(value, ttl == null ? defaultTtl : ttl));
    }

    @Override
    public void delete(String key) {
        cache.invalidate(key);
    }

    /** 缓存条目：value + 该条目的独立过期时间 */
    private static class LocalEntry {

        final String value;
        final Duration ttl;

        LocalEntry(String value, Duration ttl) {
            this.value = value;
            this.ttl = ttl;
        }
    }
}
