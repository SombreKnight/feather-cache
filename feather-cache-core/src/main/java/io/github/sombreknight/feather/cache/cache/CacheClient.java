package io.github.sombreknight.feather.cache.cache;

import java.util.List;

/**
 * 缓存客户端 SPI：统一本地缓存与 Redis 缓存的读写协议，供多级缓存路由使用。
 *
 * <p>默认实现见 {@code LocalCacheClient}（Caffeine）与 {@code RedisCacheClient}；
 * 高级用户可实现本接口替换本地缓存实现。</p>
 *
 * @author sombreknight
 * @since 0.1.0
 */
public interface CacheClient {

    /**
     * 读取缓存，未命中返回 null。
     */
    String get(String key);

    /**
     * 批量读取，返回顺序与 keys 一致，未命中对应位置为 null。
     */
    List<String> mget(List<String> keys);

    /**
     * 写入缓存（覆盖）。
     */
    void set(String key, String value, java.time.Duration ttl);

    /**
     * 删除缓存。
     */
    void delete(String key);
}
