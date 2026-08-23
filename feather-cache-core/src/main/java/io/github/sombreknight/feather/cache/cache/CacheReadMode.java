package io.github.sombreknight.feather.cache.cache;

/**
 * 缓存读取的异常策略。
 *
 * <p>common-sdk 的教训：Redis 故障被无差别吞掉、静默返回 null，导致缓存全部打穿到数据库且无告警。
 * feather-cache 把降级行为显式化，由使用方按业务可容忍度选择：</p>
 * <ul>
 *     <li>{@link #FAIL_FAST}：Redis 故障立即抛 {@code FeatherCacheException}（默认，保数据正确性）</li>
 *     <li>{@link #RETURN_NULL}：Redis 故障按"未命中"处理返回 null，打印告警（可容忍短暂空窗）</li>
 *     <li>{@link #FALLBACK_LOCAL}：Redis 故障回退本地缓存（读多写少场景）</li>
 * </ul>
 *
 * @author sombreknight
 * @since 0.1.0
 */
public enum CacheReadMode {

    /**
     * Redis 故障立即抛异常，不降级（默认）。
     */
    FAIL_FAST,

    /**
     * Redis 故障时按未命中处理返回 null，并打印 WARN 日志。
     */
    RETURN_NULL,

    /**
     * Redis 故障时回退到本地缓存。
     */
    FALLBACK_LOCAL
}
