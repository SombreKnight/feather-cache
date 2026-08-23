package io.github.sombreknight.feather.cache.samples.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.sombreknight.feather.cache.cache.CacheConfig;
import io.github.sombreknight.feather.cache.cache.FeatherCache;
import io.github.sombreknight.feather.cache.samples.domain.Order;
import io.github.sombreknight.feather.cache.samples.repository.OrderRepository;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 订单缓存服务：演示 FeatherCache 的三种用法。
 */
@Service
public class OrderCacheService {

    private static final TypeReference<Order> ORDER_TYPE = new TypeReference<>() {
    };

    /** Redis 单层：5 分钟 */
    private static final CacheConfig REDIS_CONFIG = CacheConfig.redis(Duration.ofMinutes(5));

    /** 多级缓存：Redis 5 分钟 + 本地 10 秒（per-key 时效独立） */
    private static final CacheConfig MULTI_CONFIG = CacheConfig.multi()
            .redisTtl(Duration.ofMinutes(5))
            .localTtl(Duration.ofSeconds(10));

    /** 空值防穿透：回源 null 时写 sentinel 占位 30s */
    private static final CacheConfig NULL_SAFE_CONFIG = CacheConfig.redis(Duration.ofMinutes(1))
            .cacheNull(true);

    @Resource
    private FeatherCache cache;

    @Resource
    private OrderRepository repository;

    /**
     * 单查：多级缓存，本地 10s → Redis 5min → DB 回源。
     */
    public Order getById(String id) {
        return cache.get(id, ORDER_TYPE, MULTI_CONFIG, repository::findById);
    }

    /**
     * 单查（Redis 单层，允许缓存空值防穿透）。
     */
    public Order getByIdNullSafe(String id) {
        return cache.get(id, ORDER_TYPE, NULL_SAFE_CONFIG, repository::findById);
    }

    /**
     * 批量查：pipeline mget + 缺失 ids 一次性回源。
     */
    public Map<String, Order> getByIds(List<String> ids) {
        return cache.gets(ids, Function.identity(), ORDER_TYPE, MULTI_CONFIG, repository::findByIds);
    }

    /**
     * 更新后主动刷新缓存。
     */
    public void refresh(String id, Order order) {
        repository.save(order);
        cache.put(id, order, MULTI_CONFIG);
    }

    /**
     * 删除后清缓存（本地 + Redis + 空值占位）。
     */
    public void delete(String id) {
        repository.delete(id);
        cache.evict(id);
    }
}
