package io.github.sombreknight.feather.cache.samples.repository;

import io.github.sombreknight.feather.cache.samples.domain.Order;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模拟数据源（真实项目中为 DAO）：内存 Map + 延迟模拟慢查询，
 * 用于演示缓存未命中时的回源（CacheLoader）。
 */
@Repository
public class OrderRepository {

    /** 模拟 DB：id → Order */
    private final Map<String, Order> store = new ConcurrentHashMap<>();

    /**
     * 回源：单条查询（模拟 50ms 慢查询）。
     */
    public Order findById(String id) {
        simulateSlowQuery();
        return store.get(id);
    }

    /**
     * 回源：批量查询（模拟 80ms 慢查询）。
     */
    public Map<String, Order> findByIds(List<String> ids) {
        simulateSlowQuery();
        Map<String, Order> result = new ConcurrentHashMap<>();
        for (String id : ids) {
            Order order = store.get(id);
            if (order != null) {
                result.put(id, order);
            }
        }
        return result;
    }

    public void save(Order order) {
        store.put(order.getId(), order);
    }

    public void delete(String id) {
        store.remove(id);
    }

    private void simulateSlowQuery() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
