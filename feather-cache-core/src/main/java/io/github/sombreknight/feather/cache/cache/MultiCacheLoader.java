package io.github.sombreknight.feather.cache.cache;

import java.util.List;
import java.util.Map;

/**
 * 批量缓存加载器：批量 get 场景下，缓存未命中的 id 一次性回源。
 *
 * @param <K> 业务 id 类型
 * @param <T> 数据类型
 * @author sombreknight
 * @since 0.1.0
 */
@FunctionalInterface
public interface MultiCacheLoader<K, T> {

    /**
     * 批量回源。
     *
     * @param ids 未命中的业务 id 列表（非空）
     * @return id → 数据映射；允许缺失（对应 id 视为不存在，配合 {@code cacheNull} 写空值占位）
     */
    Map<K, T> loads(List<K> ids);
}
