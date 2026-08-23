package io.github.sombreknight.feather.cache.cache.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.sombreknight.feather.cache.cache.CacheClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地缓存客户端：Caffeine 进程内缓存。
 *
 * <p>定位为<b>短时效热点缓冲</b>：过期时间全局统一（默认 10s，可构造调整），
 * 不提供 per-key TTL——需要精细控制时效的数据请走 Redis 层。
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

    private final Cache<String, String> cache;

    public LocalCacheClient() {
        this(DEFAULT_MAX_SIZE, DEFAULT_TTL);
    }

    public LocalCacheClient(int maxSize, Duration ttl) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttl)
                .build();
    }

    @Override
    public String get(String key) {
        return cache.getIfPresent(key);
    }

    @Override
    public List<String> mget(List<String> keys) {
        List<String> result = new ArrayList<>(keys.size());
        for (String key : keys) {
            result.add(cache.getIfPresent(key));
        }
        return result;
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        // 本地缓存过期时间全局统一，忽略 per-key ttl
        cache.put(key, value);
    }

    @Override
    public void delete(String key) {
        cache.invalidate(key);
    }
}
