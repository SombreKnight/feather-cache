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
 *     <li>{@code redisTtl} 作用于 <b>Redis 层</b>（默认 2 分钟）</li>
 *     <li>{@code localTtl} 作用于 <b>本地层</b>（默认 10 秒）——本地缓存支持 per-key TTL，
 *         每个 key 可通过自己的 {@code CacheConfig} 独立控制本地时效</li>
 *     <li>{@code cacheNull} 开启后，回源结果为 null 时写入空值占位（独立 sentinel key），
 *         防止缓存穿透；空值 TTL 独立于 {@code redisTtl}（默认 30s，更快恢复）</li>
 * </ul>
 *
 * @author sombreknight
 * @since 0.1.0
 */
public final class CacheConfig {

    /** Redis 层默认 TTL：2 分钟 */
    public static final Duration DEFAULT_REDIS_TTL = Duration.ofMinutes(2);

    /** 本地层默认 TTL：10 秒 */
    public static final Duration DEFAULT_LOCAL_TTL = Duration.ofSeconds(10);

    /** 空值占位默认 TTL：30 秒 */
    public static final Duration DEFAULT_SENTINEL_TTL = Duration.ofSeconds(30);

    private final CacheType type;
    private final Duration redisTtl;
    private final Duration localTtl;
    private final Duration sentinelTtl;
    private final boolean cacheNull;
    private final CacheReadMode readMode;

    private CacheConfig(CacheType type, Duration redisTtl, Duration localTtl, Duration sentinelTtl,
                        boolean cacheNull, CacheReadMode readMode) {
        this.type = Objects.requireNonNull(type, "type 不能为空");
        this.redisTtl = Objects.requireNonNull(redisTtl, "redisTtl 不能为空");
        this.localTtl = Objects.requireNonNull(localTtl, "localTtl 不能为空");
        this.sentinelTtl = Objects.requireNonNull(sentinelTtl, "sentinelTtl 不能为空");
        this.cacheNull = cacheNull;
        this.readMode = Objects.requireNonNull(readMode, "readMode 不能为空");
    }

    public static CacheConfig redis() {
        return new CacheConfig(CacheType.REDIS_ONLY, DEFAULT_REDIS_TTL, DEFAULT_LOCAL_TTL,
                DEFAULT_SENTINEL_TTL, false, CacheReadMode.FAIL_FAST);
    }

    public static CacheConfig redis(Duration redisTtl) {
        return redis().redisTtl(redisTtl);
    }

    public static CacheConfig local() {
        return new CacheConfig(CacheType.LOCAL_ONLY, DEFAULT_REDIS_TTL, DEFAULT_LOCAL_TTL,
                DEFAULT_SENTINEL_TTL, false, CacheReadMode.FAIL_FAST);
    }

    /**
     * 本地优先、Redis 兜底的多级缓存（redis 层 {@code redisTtl}，本地层 {@code localTtl}）。
     */
    public static CacheConfig multi() {
        return new CacheConfig(CacheType.LOCAL_FIRST_THEN_REDIS, DEFAULT_REDIS_TTL, DEFAULT_LOCAL_TTL,
                DEFAULT_SENTINEL_TTL, false, CacheReadMode.FAIL_FAST);
    }

    /**
     * 设置 Redis 层 TTL。
     */
    public CacheConfig redisTtl(Duration redisTtl) {
        return new CacheConfig(type, redisTtl, localTtl, sentinelTtl, cacheNull, readMode);
    }

    /**
     * 设置本地层 TTL（本地缓存 per-key 时效）。
     */
    public CacheConfig localTtl(Duration localTtl) {
        return new CacheConfig(type, redisTtl, localTtl, sentinelTtl, cacheNull, readMode);
    }

    public CacheConfig sentinelTtl(Duration sentinelTtl) {
        return new CacheConfig(type, redisTtl, localTtl, sentinelTtl, cacheNull, readMode);
    }

    public CacheConfig cacheNull(boolean cacheNull) {
        return new CacheConfig(type, redisTtl, localTtl, sentinelTtl, cacheNull, readMode);
    }

    public CacheConfig readMode(CacheReadMode readMode) {
        return new CacheConfig(type, redisTtl, localTtl, sentinelTtl, cacheNull, readMode);
    }

    public CacheType getType() {
        return type;
    }

    /**
     * Redis 层 TTL。
     */
    public Duration getRedisTtl() {
        return redisTtl;
    }

    /**
     * 本地层 TTL（本地缓存 per-key 时效，默认 10 秒）。
     */
    public Duration getLocalTtl() {
        return localTtl;
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
