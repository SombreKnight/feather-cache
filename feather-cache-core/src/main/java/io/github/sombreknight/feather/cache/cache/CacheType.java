package io.github.sombreknight.feather.cache.cache;

/**
 * 缓存层级类型。
 *
 * @author sombreknight
 * @since 0.1.0
 */
public enum CacheType {

    /**
     * 仅本地缓存（Caffeine，进程内，不跨实例）。
     */
    LOCAL_ONLY,

    /**
     * 仅 Redis 缓存（跨实例共享）。
     */
    REDIS_ONLY,

    /**
     * 本地优先，未命中再查 Redis（多级缓存）。
     * 注意：本地缓存无跨实例失效机制，存在"最终一致窗口 = 本地缓存 TTL"，
     * 对强一致场景请使用 {@link #REDIS_ONLY}。
     */
    LOCAL_FIRST_THEN_REDIS
}
