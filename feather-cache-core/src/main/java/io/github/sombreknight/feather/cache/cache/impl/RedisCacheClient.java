package io.github.sombreknight.feather.cache.cache.impl;

import io.github.sombreknight.feather.cache.cache.CacheClient;
import io.github.sombreknight.feather.cache.redis.FeatherRedisClient;

import java.time.Duration;
import java.util.List;

/**
 * Redis 缓存客户端：包装 {@link FeatherRedisClient}，供多级缓存路由使用。
 *
 * @author sombreknight
 * @since 0.1.0
 */
public class RedisCacheClient implements CacheClient {

    private final FeatherRedisClient redisClient;

    public RedisCacheClient(FeatherRedisClient redisClient) {
        this.redisClient = redisClient;
    }

    @Override
    public String get(String key) {
        return redisClient.get(key);
    }

    @Override
    public List<String> mget(List<String> keys) {
        return redisClient.mget(keys);
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        redisClient.set(key, value, ttl);
    }

    @Override
    public void delete(String key) {
        redisClient.delete(key);
    }
}
