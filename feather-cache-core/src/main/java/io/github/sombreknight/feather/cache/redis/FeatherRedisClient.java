package io.github.sombreknight.feather.cache.redis;

import io.github.sombreknight.feather.cache.exception.FeatherCacheException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Redis 薄封装：包装 {@link StringRedisTemplate}。
 *
 * <p>设计约束：</p>
 * <ul>
 *     <li><b>不创建任何连接工厂</b>——连接、连接池、超时全部复用 {@code spring.data.redis.*} 配置</li>
 *     <li><b>不吞异常</b>——所有底层 {@link DataAccessException} 统一转译为
 *         {@link FeatherCacheException} 抛出（区别于 common-sdk 的静默返回 null），
 *         降级与否由上层按 {@code CacheReadMode} 决策</li>
 *     <li><b>mget 走 pipeline</b>——批量读单次往返，不做循环单查</li>
 * </ul>
 *
 * @author sombreknight
 * @since 0.1.0
 */
public class FeatherRedisClient {

    private static final Logger log = LoggerFactory.getLogger(FeatherRedisClient.class);

    private final StringRedisTemplate redisTemplate;

    public FeatherRedisClient(StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate 不能为空");
    }

    // ---------------------------------------------------------------- String

    /**
     * 获取缓存值，key 不存在返回 null。
     */
    public String get(String key) {
        return execute(() -> redisTemplate.opsForValue().get(key));
    }

    /**
     * 写入缓存（覆盖），带过期时间。
     */
    public void set(String key, String value, Duration ttl) {
        execute(() -> {
            redisTemplate.opsForValue().set(key, value, ttl);
            return null;
        });
    }

    /**
     * 仅当 key 不存在时写入（SET NX EX，原子），用于分布式锁加锁。
     *
     * @return 写入成功返回 true，key 已存在返回 false
     */
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        Boolean result = execute(() -> redisTemplate.opsForValue().setIfAbsent(key, value, ttl));
        return Boolean.TRUE.equals(result);
    }

    /**
     * 批量获取，返回顺序与 keys 一致；不存在的 key 对应位置为 null。
     *
     * <p>走 pipeline 单次网络往返（common-sdk 的循环单查在此修复）。</p>
     */
    public List<String> mget(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return new ArrayList<>();
        }
        return execute(() -> {
            List<Object> pipelined = redisTemplate.executePipelined(new SessionCallback<List<Object>>() {
                @Override
                @SuppressWarnings({"rawtypes", "unchecked"})
                public List<Object> execute(org.springframework.data.redis.core.RedisOperations operations) {
                    for (String key : keys) {
                        operations.opsForValue().get(key);
                    }
                    return null;
                }
            });
            List<String> result = new ArrayList<>(pipelined.size());
            for (Object value : pipelined) {
                result.add(value == null ? null : value.toString());
            }
            return result;
        });
    }

    /**
     * 删除 key，返回是否删除成功。
     */
    public boolean delete(String key) {
        Boolean result = execute(() -> redisTemplate.delete(key));
        return Boolean.TRUE.equals(result);
    }

    /**
     * 设置过期时间。
     */
    public boolean expire(String key, Duration ttl) {
        Boolean result = execute(() -> redisTemplate.expire(key, ttl));
        return Boolean.TRUE.equals(result);
    }

    /**
     * 剩余过期时间（秒）；key 不存在返回 -2，无过期时间返回 -1。
     */
    public long ttl(String key) {
        Long result = execute(() -> redisTemplate.getExpire(key, java.util.concurrent.TimeUnit.SECONDS));
        return result == null ? -2L : result;
    }

    // ---------------------------------------------------------------- Hash

    public void hSet(String key, String field, String value) {
        execute(() -> {
            redisTemplate.opsForHash().put(key, field, value);
            return null;
        });
    }

    public String hGet(String key, String field) {
        Object value = execute(() -> redisTemplate.opsForHash().get(key, field));
        return value == null ? null : value.toString();
    }

    public List<String> hMGet(String key, List<String> fields) {
        List<String> values = execute(() -> redisTemplate.<String, String>opsForHash().multiGet(key, fields));
        List<String> result = new ArrayList<>(values.size());
        for (String value : values) {
            result.add(value);
        }
        return result;
    }

    public boolean hDelete(String key, String... fields) {
        Long result = execute(() -> redisTemplate.opsForHash().delete(key, (Object[]) fields));
        return result != null && result > 0;
    }

    // ---------------------------------------------------------------- 其他

    /**
     * 自增，返回自增后的值。
     */
    public long incrBy(String key, long delta) {
        Long result = execute(() -> redisTemplate.opsForValue().increment(key, delta));
        return result == null ? 0L : result;
    }

    /**
     * 执行 Lua 脚本 / 原生命令（分布式锁的 compare-and-delete 走此通道）。
     */
    public <T> T execute(RedisCallback<T> callback) {
        return execute(() -> redisTemplate.execute(callback));
    }

    // ---------------------------------------------------------------- 内部

    private <T> T execute(Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessException e) {
            log.error("Redis 操作失败: {}", e.getMessage());
            throw new FeatherCacheException("Redis 操作失败", e);
        }
    }
}
