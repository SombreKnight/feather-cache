package io.github.sombreknight.feather.cache.cache;

/**
 * 缓存加载器：缓存未命中时的回源逻辑。
 *
 * @param <T> 数据类型
 * @author sombreknight
 * @since 0.1.0
 */
@FunctionalInterface
public interface CacheLoader<T> {

    /**
     * 回源加载数据。
     *
     * @param key 缓存 key（业务 key，不含框架前缀）
     * @return 数据；返回 null 表示数据不存在（配合 {@code cacheNull} 可写空值占位防穿透）
     */
    T load(String key);
}
