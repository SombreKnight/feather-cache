package io.github.sombreknight.feather.cache.support;

/**
 * key 命名单一事实源（Single Source of Truth）。
 *
 * <p>框架内所有 Redis key / 本地缓存 key 的生成必须经过本类，禁止在业务代码中手拼 key 前缀。</p>
 *
 * <p>命名格式：{@code feather:{app}:{scope}:{key}}，例如：</p>
 * <ul>
 *     <li>{@code feather:order-service:cache:user:123} —— 缓存数据</li>
 *     <li>{@code feather:order-service:lock:pay:456} —— 分布式锁</li>
 *     <li>{@code feather:order-service:sentinel:user:123} —— 空值占位</li>
 * </ul>
 *
 * <p>{@code app} 取 {@code spring.application.name} 或 {@code feather.cache.namespace}（缺省 default）；
 * {@code scope} 由本类固定；业务只提供最后的 {@code key} 段。</p>
 *
 * @author sombreknight
 * @since 0.1.0
 */
public class NamingStrategy {

    public static final String PREFIX = "feather";

    /** 缓存数据 scope */
    public static final String SCOPE_CACHE = "cache";

    /** 分布式锁 scope */
    public static final String SCOPE_LOCK = "lock";

    /** 空值占位 scope */
    public static final String SCOPE_SENTINEL = "sentinel";

    /** app 段缺省值 */
    public static final String DEFAULT_APP = "default";

    private final String appSegment;

    /**
     * @param appName 应用名（{@code spring.application.name} 或自定义 namespace），为空时回退 {@link #DEFAULT_APP}
     */
    public NamingStrategy(String appName) {
        String normalized = normalizeSegment(appName);
        this.appSegment = normalized.isEmpty() ? DEFAULT_APP : normalized;
    }

    /**
     * 生成缓存数据 key。
     */
    public String cacheKey(String key) {
        return build(SCOPE_CACHE, key);
    }

    /**
     * 生成分布式锁 key。
     */
    public String lockKey(String key) {
        return build(SCOPE_LOCK, key);
    }

    /**
     * 生成空值占位 key。
     */
    public String sentinelKey(String key) {
        return build(SCOPE_SENTINEL, key);
    }

    private String build(String scope, String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key 不能为空: scope=" + scope);
        }
        return PREFIX + ":" + appSegment + ":" + scope + ":" + normalizeSegment(key);
    }

    /**
     * 规范化 key 段：去除首尾空白、压缩连续冒号、去除首尾冒号。
     * 业务 key 段内部的单个冒号（如 {@code order:123}）保留。
     */
    public static String normalizeSegment(String segment) {
        if (segment == null) {
            return "";
        }
        String trimmed = segment.trim();
        // 压缩连续冒号（"a::b" → "a:b"），并去掉首尾冒号
        return trimmed.replaceAll(":+", ":").replaceAll("^:+|:+$", "");
    }
}
