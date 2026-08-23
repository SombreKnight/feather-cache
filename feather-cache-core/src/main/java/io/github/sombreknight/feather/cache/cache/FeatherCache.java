package io.github.sombreknight.feather.cache.cache;

import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 多级缓存服务。
 *
 * <p>核心能力：</p>
 * <ul>
 *     <li><b>防击穿</b>：单 key single-flight 信号量 + 双重检查，同一 key 的缓存重建只放行 1 个回源</li>
 *     <li><b>防穿透</b>：{@code cacheNull} 开启后，回源 null 写入独立 sentinel 占位（TTL 独立），
 *         下游请求直接判空不再回源</li>
 *     <li><b>异常降级</b>：Redis 故障按 {@link CacheReadMode} 决策（抛异常 / 返回 null / 回退本地），
 *         绝不静默吞异常</li>
 * </ul>
 *
 * @author sombreknight
 * @since 0.1.0
 */
public interface FeatherCache {

    /**
     * 获取缓存，未命中走 loader 回源并回填缓存。默认配置：Redis 单层、2 分钟 TTL、fail-fast。
     */
    <T> T get(String key, TypeReference<T> type, CacheLoader<T> loader);

    /**
     * 获取缓存（自定义配置）。
     */
    <T> T get(String key, TypeReference<T> type, CacheConfig config, CacheLoader<T> loader);

    /**
     * 批量获取缓存：mget 批量命中 + 未命中 ids 一次性回源。
     *
     * @param ids          业务 id 列表
     * @param keyGenerator 业务 id → 缓存 key 的生成器
     * @param type         数据类型（TypeReference 支持泛型）
     * @param config       缓存配置
     * @param loader       批量回源加载器
     * @return id → 数据映射（未回源到的 id 不会出现在结果中）
     */
    <K, T> Map<K, T> gets(List<K> ids, Function<K, String> keyGenerator, TypeReference<T> type,
                          CacheConfig config, MultiCacheLoader<K, T> loader);

    /**
     * 主动写入缓存（业务更新后可刷新缓存），并清理对应空值占位。
     */
    void put(String key, Object value, CacheConfig config);

    /**
     * 删除缓存（本地 + Redis + 空值占位一并清除）。
     */
    void evict(String key);
}
