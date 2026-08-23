package io.github.sombreknight.feather.cache.cache.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.sombreknight.feather.cache.cache.CacheClient;
import io.github.sombreknight.feather.cache.cache.CacheConfig;
import io.github.sombreknight.feather.cache.cache.CacheLoader;
import io.github.sombreknight.feather.cache.cache.CacheReadMode;
import io.github.sombreknight.feather.cache.cache.CacheType;
import io.github.sombreknight.feather.cache.cache.FeatherCache;
import io.github.sombreknight.feather.cache.cache.MultiCacheLoader;
import io.github.sombreknight.feather.cache.exception.FeatherCacheException;
import io.github.sombreknight.feather.cache.support.JsonCodec;
import io.github.sombreknight.feather.cache.support.NamingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * 多级缓存核心实现。
 *
 * <p>实现要点：</p>
 * <ul>
 *     <li><b>single-flight 防击穿</b>：每 key 一个信号量（默认 1 个许可），未命中线程先取许可，
 *         拿到的线程回源，其余线程在许可内<b>双重检查</b>后直接命中；信号量实例放 Caffeine LRU
 *         限容（默认 1024），自动淘汰不泄漏</li>
 *     <li><b>sentinel 防穿透</b>：空值占位写在独立 scope 的 key（{@code feather:{app}:sentinel:{key}}），
 *         与业务数据 key 空间隔离，不可能碰撞；空值 TTL 独立（默认 30s）</li>
 *     <li><b>降级显式化</b>：Redis 故障按 {@link CacheReadMode} 处理，不吞异常</li>
 * </ul>
 *
 * <p>对外接口收<b>业务 key</b>（如 {@code order:123}），框架内部统一经
 * {@link NamingStrategy} 加前缀。</p>
 *
 * @author sombreknight
 * @since 0.1.0
 */
public class FeatherCacheImpl implements FeatherCache {

    private static final Logger log = LoggerFactory.getLogger(FeatherCacheImpl.class);

    /** 空值占位值（sentinel key 存在即代表"缓存过的空值"） */
    private static final String SENTINEL_VALUE = "1";

    private final NamingStrategy namingStrategy;
    private final CacheClient localCacheClient;
    private final CacheClient redisCacheClient;
    private final JsonCodec codec;

    /** single-flight 许可数（每 key），默认 1 */
    private final int singleFlightPermits;

    /** 信号量实例 LRU 池，防止 key 无限增长 */
    private final Cache<String, Semaphore> semaphoreCache;

    public FeatherCacheImpl(NamingStrategy namingStrategy, CacheClient localCacheClient,
                            CacheClient redisCacheClient, JsonCodec codec) {
        this(namingStrategy, localCacheClient, redisCacheClient, codec, 1, 1024);
    }

    public FeatherCacheImpl(NamingStrategy namingStrategy, CacheClient localCacheClient,
                            CacheClient redisCacheClient, JsonCodec codec,
                            int singleFlightPermits, int semaphoreCacheMaxSize) {
        this.namingStrategy = namingStrategy;
        this.localCacheClient = localCacheClient;
        this.redisCacheClient = redisCacheClient;
        this.codec = codec;
        this.singleFlightPermits = singleFlightPermits;
        this.semaphoreCache = Caffeine.newBuilder()
                .maximumSize(semaphoreCacheMaxSize)
                .build();
    }

    // ---------------------------------------------------------------- get

    @Override
    public <T> T get(String key, TypeReference<T> type, CacheLoader<T> loader) {
        return get(key, type, CacheConfig.redis(), loader);
    }

    @Override
    public <T> T get(String key, TypeReference<T> type, CacheConfig config, CacheLoader<T> loader) {
        try {
            return route(key, type, config, k -> doGet(cacheClient(config), k, type, config, loader));
        } catch (FeatherCacheException e) {
            return handleReadFailure(key, type, config, loader, e);
        }
    }

    /**
     * 多级路由：LOCAL_FIRST_THEN_REDIS 时把"redis 查询+回源"作为本地缓存的 loader 链式调用。
     */
    private <T> T route(String key, TypeReference<T> type, CacheConfig config, Function<String, T> action) {
        switch (config.getType()) {
            case LOCAL_ONLY:
            case REDIS_ONLY:
                return action.apply(key);
            case LOCAL_FIRST_THEN_REDIS:
                return doGet(localCacheClient, key, type, config, k -> action.apply(key));
            default:
                throw new IllegalArgumentException("不支持的缓存类型: " + config.getType());
        }
    }

    private <T> T doGet(CacheClient client, String businessKey, TypeReference<T> type,
                        CacheConfig config, CacheLoader<T> loader) {
        String cacheKey = namingStrategy.cacheKey(businessKey);
        String sentinelKey = namingStrategy.sentinelKey(businessKey);
        // 1. 读缓存
        String cached = client.get(cacheKey);
        if (cached != null) {
            return codec.toObject(cached, type);
        }
        // 2. 空值占位快速路径（防穿透）
        if (config.isCacheNull() && client.get(sentinelKey) != null) {
            return null;
        }
        // 3. single-flight 许可
        Semaphore semaphore = semaphoreCache.get(cacheKey, k -> new Semaphore(singleFlightPermits));
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FeatherCacheException("等待缓存重建许可被中断: " + cacheKey, e);
        }
        try {
            // 4. 双重检查（许可内重查，避免拿到许可的线程重复回源）
            cached = client.get(cacheKey);
            if (cached != null) {
                return codec.toObject(cached, type);
            }
            if (config.isCacheNull() && client.get(sentinelKey) != null) {
                return null;
            }
            // 5. 回源并回填
            T value = loader.load(businessKey);
            if (value == null) {
                if (config.isCacheNull()) {
                    client.set(sentinelKey, SENTINEL_VALUE, config.getSentinelTtl());
                }
                return null;
            }
            client.set(cacheKey, codec.toJson(value), config.getTtl());
            return value;
        } finally {
            semaphore.release();
        }
    }

    /**
     * Redis 故障降级：按 CacheReadMode 决策。
     */
    private <T> T handleReadFailure(String key, TypeReference<T> type, CacheConfig config,
                                    CacheLoader<T> loader, FeatherCacheException e) {
        switch (config.getReadMode()) {
            case FAIL_FAST:
                throw e;
            case RETURN_NULL:
                log.warn("缓存读取降级（RETURN_NULL），key={}, 原因: {}", key, e.getMessage());
                return null;
            case FALLBACK_LOCAL:
                if (config.getType() != CacheType.LOCAL_ONLY) {
                    log.warn("缓存读取降级（FALLBACK_LOCAL），key={}, 原因: {}", key, e.getMessage());
                    return doGet(localCacheClient, key, type, config, loader);
                }
                throw e;
            default:
                throw e;
        }
    }

    // ---------------------------------------------------------------- gets

    @Override
    public <K, T> Map<K, T> gets(List<K> ids, Function<K, String> keyGenerator, TypeReference<T> type,
                                  CacheConfig config, MultiCacheLoader<K, T> loader) {
        if (ids == null || ids.isEmpty()) {
            return new HashMap<>();
        }
        try {
            return routeGets(ids, keyGenerator, type, config, loader);
        } catch (FeatherCacheException e) {
            return handleGetsFailure(ids, keyGenerator, type, config, loader, e);
        }
    }

    private <K, T> Map<K, T> routeGets(List<K> ids, Function<K, String> keyGenerator, TypeReference<T> type,
                                       CacheConfig config, MultiCacheLoader<K, T> loader) {
        switch (config.getType()) {
            case LOCAL_ONLY:
            case REDIS_ONLY:
                return doGets(cacheClient(config), ids, keyGenerator, type, config, loader);
            case LOCAL_FIRST_THEN_REDIS:
                // 本地批量命中后，未命中 ids 的 loader 接"redis 批量查询+回源"
                return doGets(localCacheClient, ids, keyGenerator, type, config,
                        missingIds -> doGets(redisCacheClient, missingIds, keyGenerator, type, config, loader));
            default:
                throw new IllegalArgumentException("不支持的缓存类型: " + config.getType());
        }
    }

    private <K, T> Map<K, T> doGets(CacheClient client, List<K> ids, Function<K, String> keyGenerator,
                                     TypeReference<T> type, CacheConfig config, MultiCacheLoader<K, T> loader) {
        List<String> businessKeys = ids.stream().map(keyGenerator).toList();
        List<String> cacheKeys = businessKeys.stream().map(namingStrategy::cacheKey).toList();
        List<String> values = client.mget(cacheKeys);

        Map<K, T> result = new HashMap<>(ids.size());
        List<K> missingIds = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            String value = values.get(i);
            if (value != null) {
                result.put(ids.get(i), codec.toObject(value, type));
            } else {
                missingIds.add(ids.get(i));
            }
        }
        if (missingIds.isEmpty()) {
            return result;
        }

        // 空值占位批量检查：已缓存的空值直接判 null，不再回源
        if (config.isCacheNull()) {
            List<String> sentinelKeys = missingIds.stream()
                    .map(id -> namingStrategy.sentinelKey(keyGenerator.apply(id)))
                    .toList();
            List<String> sentinelValues = client.mget(sentinelKeys);
            Iterator<K> iterator = missingIds.iterator();
            for (int i = 0; iterator.hasNext(); i++) {
                K id = iterator.next();
                if (sentinelValues.get(i) != null) {
                    result.put(id, null);
                    iterator.remove();
                }
            }
        }

        if (missingIds.isEmpty()) {
            return result;
        }

        // 回源
        Map<K, T> loaded = loader.loads(missingIds);
        for (K id : missingIds) {
            T value = loaded.get(id);
            String cacheKey = namingStrategy.cacheKey(keyGenerator.apply(id));
            if (value == null) {
                if (config.isCacheNull()) {
                    client.set(namingStrategy.sentinelKey(keyGenerator.apply(id)), SENTINEL_VALUE, config.getSentinelTtl());
                }
            } else {
                client.set(cacheKey, codec.toJson(value), config.getTtl());
            }
            result.put(id, value);
        }
        return result;
    }

    private <K, T> Map<K, T> handleGetsFailure(List<K> ids, Function<K, String> keyGenerator,
                                               TypeReference<T> type, CacheConfig config,
                                               MultiCacheLoader<K, T> loader, FeatherCacheException e) {
        switch (config.getReadMode()) {
            case FAIL_FAST:
                throw e;
            case RETURN_NULL:
                log.warn("批量缓存读取降级（RETURN_NULL），ids={}, 原因: {}", ids, e.getMessage());
                return new HashMap<>();
            case FALLBACK_LOCAL:
                if (config.getType() != CacheType.LOCAL_ONLY) {
                    log.warn("批量缓存读取降级（FALLBACK_LOCAL），ids={}, 原因: {}", ids, e.getMessage());
                    return doGets(localCacheClient, ids, keyGenerator, type, config, loader);
                }
                throw e;
            default:
                throw e;
        }
    }

    // ---------------------------------------------------------------- put / evict

    @Override
    public void put(String key, Object value, CacheConfig config) {
        String cacheKey = namingStrategy.cacheKey(key);
        switch (config.getType()) {
            case LOCAL_ONLY -> localCacheClient.set(cacheKey, codec.toJson(value), config.getTtl());
            case REDIS_ONLY -> redisCacheClient.set(cacheKey, codec.toJson(value), config.getTtl());
            case LOCAL_FIRST_THEN_REDIS -> {
                localCacheClient.set(cacheKey, codec.toJson(value), config.getTtl());
                redisCacheClient.set(cacheKey, codec.toJson(value), config.getTtl());
            }
            default -> throw new IllegalArgumentException("不支持的缓存类型: " + config.getType());
        }
        // 清理空值占位
        evictSentinel(key);
    }

    @Override
    public void evict(String key) {
        localCacheClient.delete(namingStrategy.cacheKey(key));
        redisCacheClient.delete(namingStrategy.cacheKey(key));
        evictSentinel(key);
    }

    private void evictSentinel(String businessKey) {
        localCacheClient.delete(namingStrategy.sentinelKey(businessKey));
        redisCacheClient.delete(namingStrategy.sentinelKey(businessKey));
    }

    // ---------------------------------------------------------------- 内部

    private CacheClient cacheClient(CacheConfig config) {
        return config.getType() == CacheType.LOCAL_ONLY ? localCacheClient : redisCacheClient;
    }
}
