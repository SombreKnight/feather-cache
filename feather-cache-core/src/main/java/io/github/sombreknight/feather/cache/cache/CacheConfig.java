package io.github.sombreknight.feather.cache.cache;

import java.time.Duration;
import java.util.Objects;

/**
 * 缓存配置（不可变）。
 *
 * <p>使用静态工厂 + with 链式复制构造，例如：</p>
 * <pre>{@code
 * CacheConfig config = CacheConfig.redis(Duration.ofMinutes(2))
 *         .cacheNull(true)
 *         .readMode(CacheReadMode.RETURN_NULL);
 * }</pre>
 *
 * <p>语义说明：</p>
 * <ul>
 *     <li>{@code ttl} 仅对 Redis 层生效；本地缓存统一走全局过期（默认 10s，见 LocalCacheClient）——
 *         本地缓存定位为短时效热点缓冲，不提供 per-key TTL</li>
 *     <li>{@code cacheNull} 开启后，回源结果为 null 时写入空值占位（独立 sentinel key），
 *         防止缓存穿透；空值 TTL 独立于 {@code ttl}（默认 30s，更快恢复）</li>
 * </ul>
 *
 * @author sombreknight
 * @since 0.1.0
 */
public final class CacheConfig {

    /** Redis 层默认 TTL：2 分钟 */
    public static final Duration DEFAULT_REDIS_TTL = Duration.ofMinutes(2);

    /** 空值占位默认 TTL：30 秒 */
    public static final Duration DEFAULT_SENTINEL_TTL = Duration.ofSeconds(30);

    private final CacheType type;
    private final Duration ttl;
    private final Duration sentinelTtl;
    private final boolean cacheNull;
    private final CacheReadMode readMode;

    private CacheConfig(CacheType type, Duration ttl, Duration sentinelTtl, boolean cacheNull, CacheReadMode readMode) {
        this.type = Objects.requireNonNull(type, "type 不能为空");
        this.ttl = Objects.requireNonNull(ttl, "ttl 不能为空");
        this.sentinelTtl = Objects.requireNonNull(sentinelTtl, "sentinelTtl 不能为空");
        this.cacheNull = cacheNull;
        this.readMode = Objects.requireNonNull(readMode, "readMode 不能为空");
    }

    public static CacheConfig redis() {
        return new CacheConfig(CacheType.REDIS_ONLY, DEFAULT_REDIS_TTL, DEFAULT_SENTINEL_TTL, false, CacheReadMode.FAIL_FAST);
    }

    public static CacheConfig redis(Duration ttl) {
        return redis().ttl(ttl);
    }

    public static CacheConfig local() {
        return new CacheConfig(CacheType.LOCAL_ONLY, Duration.ofSeconds(10), DEFAULT_SENTINEL_TTL, false, CacheReadMode.FAIL_FAST);
    }

    /**
     * 本地优先、Redis 兜底的多级缓存。
     */
    public static CacheConfig multi() {
        return new CacheConfig(CacheType.LOCAL_FIRST_THEN_REDIS, DEFAULT_REDIS_TTL, DEFAULT_SENTINEL_TTL, false, CacheReadMode.FAIL_FAST);
    }

    public CacheConfig ttl(Duration ttl) {
        return new CacheConfig(type, ttl, sentinelTtl, cacheNull, readMode);
    }

    public CacheConfig sentinelTtl(Duration sentinelTtl) {
        return new CacheConfig(type, ttl, sentinelTtl, cacheNull, readMode);
    }

    public CacheConfig cacheNull(boolean cacheNull) {
        return new CacheConfig(type, ttl, sentinelTtl, cacheNull, readMode);
    }

    public CacheConfig readMode(CacheReadMode readMode) {
        return new CacheConfig(type, ttl, sentinelTtl, cacheNull, readMode);
    }

    public CacheType getType() {
        return type;
    }

    public Duration getTtl() {
        return ttl;
    }

    public Duration getSentinelTtl() {
        return sentinelTtl;
    }

    public boolean isCacheNull() {
        return cacheNull;
    }

    public CacheReadMode getReadMode() {
        return readMode;
    }
}
