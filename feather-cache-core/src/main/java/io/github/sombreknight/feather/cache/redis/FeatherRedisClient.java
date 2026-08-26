package io.github.sombreknight.feather.cache.redis;

import io.github.sombreknight.feather.cache.exception.FeatherCacheException;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import io.lettuce.core.KeyValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Redis 薄封装：包装 lettuce 同步命令接口（连接由 {@link FeatherRedisConnectionFactory} 管理）。
 *
 * <p>设计约束：</p>
 * <ul>
 *     <li><b>不创建任何连接工厂</b>——连接、连接池、超时全部由
 *         {@code feather.cache.redis.*} 配置 + {@link FeatherRedisConnectionFactory} 管理；
 *         命令时经工厂懒取命令接口（懒连接，Redis 不可达时应用可正常启动并按
 *         {@code CacheReadMode} 降级）</li>
 *     <li><b>不吞异常</b>——所有底层 {@link RedisException} 统一转译为
 *         {@link FeatherCacheException} 抛出，降级与否由上层决策</li>
 *     <li><b>mget 集群逐 key</b>——集群模式下循环 GET（lettuce 单连接多路复用即流水线），
 *         key 跨 slot 时无 CROSSSLOT 错误；单机/哨兵模式走原生 MGET 单次往返</li>
 * </ul>
 *
 * @author sombreknight
 * @since 1.0.0
 */
public class FeatherRedisClient {

    private static final Logger log = LoggerFactory.getLogger(FeatherRedisClient.class);

    private final FeatherRedisConnectionFactory factory;
    /** 直接指定命令接口的用法（不触发连接管理，命令执行交给调用方控制连接生命周期） */
    private final RedisCommands<String, String> directCommands;
    private final RedisClusterCommands<String, String> directClusterCommands;

    public FeatherRedisClient(RedisCommands<String, String> commands) {
        this.factory = null;
        this.directCommands = Objects.requireNonNull(commands, "commands 不能为空");
        this.directClusterCommands = null;
    }

    public FeatherRedisClient(RedisClusterCommands<String, String> clusterCommands) {
        this.factory = null;
        this.directCommands = null;
        this.directClusterCommands = Objects.requireNonNull(clusterCommands, "clusterCommands 不能为空");
    }

    /**
     * 从连接工厂构造（按部署形态自动选择命令接口，懒连接）。
     */
    public FeatherRedisClient(FeatherRedisConnectionFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory 不能为空");
        this.directCommands = null;
        this.directClusterCommands = null;
    }

    public boolean isCluster() {
        return factory != null ? factory.isCluster() : directClusterCommands != null;
    }

    private RedisCommands<String, String> commands() {
        return factory != null ? factory.sync() : directCommands;
    }

    private RedisClusterCommands<String, String> clusterCommands() {
        return factory != null ? factory.syncCluster() : directClusterCommands;
    }

    // ---------------------------------------------------------------- String

    /**
     * 获取缓存值，key 不存在返回 null。
     */
    public String get(String key) {
        return execute(() -> isCluster() ? clusterCommands().get(key) : commands().get(key));
    }

    /**
     * 写入缓存（覆盖），带过期时间。毫秒精度（PX），支持亚秒级 TTL。
     */
    public void set(String key, String value, Duration ttl) {
        execute(() -> {
            if (isCluster()) {
                clusterCommands().set(key, value, SetArgs.Builder.px(ttl.toMillis()));
            } else {
                commands().set(key, value, SetArgs.Builder.px(ttl.toMillis()));
            }
            return null;
        });
    }

    /**
     * 仅当 key 不存在时写入（SET NX EX，原子），用于分布式锁加锁。
     *
     * @return 写入成功返回 true，key 已存在返回 false
     */
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        // lettuce SET 返回 "OK"；NX 未写入（key 已存在）时返回 null。
        // 用毫秒 PX：支持亚秒级 TTL（toSeconds() 对 <1s 会取整为 0 导致锁立即失效）
        String result = execute(() -> isCluster()
                ? clusterCommands().set(key, value, SetArgs.Builder.nx().px(ttl.toMillis()))
                : commands().set(key, value, SetArgs.Builder.nx().px(ttl.toMillis())));
        return "OK".equals(result);
    }

    /**
     * 批量获取，返回顺序与 keys 一致；不存在的 key 对应位置为 null。
     *
     * <p>单机/哨兵走原生 MGET（单次往返）；集群走逐 key GET（多路复用流水线，避免 CROSSSLOT）。</p>
     */
    public List<String> mget(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return new ArrayList<>();
        }
        return execute(() -> {
            if (isCluster()) {
                List<String> result = new ArrayList<>(keys.size());
                for (String key : keys) {
                    result.add(clusterCommands().get(key));
                }
                return result;
            }
            List<KeyValue<String, String>> pairs = commands().mget(keys.toArray(new String[0]));
            List<String> result = new ArrayList<>(pairs.size());
            for (KeyValue<String, String> pair : pairs) {
                result.add(pair.hasValue() ? pair.getValue() : null);
            }
            return result;
        });
    }

    /**
     * 删除 key，返回是否删除成功。
     */
    public boolean delete(String key) {
        Long result = execute(() -> isCluster() ? clusterCommands().del(key) : commands().del(key));
        return result != null && result > 0;
    }

    /**
     * 设置过期时间。毫秒精度（PEXPIRE），支持亚秒级 TTL。
     */
    public boolean expire(String key, Duration ttl) {
        Boolean result = execute(() -> isCluster()
                ? clusterCommands().pexpire(key, ttl.toMillis())
                : commands().pexpire(key, ttl.toMillis()));
        return Boolean.TRUE.equals(result);
    }

    /**
     * 剩余过期时间（秒）；key 不存在返回 -2，无过期时间返回 -1。
     */
    public long ttl(String key) {
        Long result = execute(() -> isCluster() ? clusterCommands().ttl(key) : commands().ttl(key));
        return result == null ? -2L : result;
    }

    // ---------------------------------------------------------------- Hash

    public void hSet(String key, String field, String value) {
        execute(() -> {
            if (isCluster()) {
                clusterCommands().hset(key, field, value);
            } else {
                commands().hset(key, field, value);
            }
            return null;
        });
    }

    public String hGet(String key, String field) {
        return execute(() -> isCluster() ? clusterCommands().hget(key, field) : commands().hget(key, field));
    }

    public List<String> hMGet(String key, List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return new ArrayList<>();
        }
        return execute(() -> {
            List<KeyValue<String, String>> pairs = isCluster()
                    ? clusterCommands().hmget(key, fields.toArray(new String[0]))
                    : commands().hmget(key, fields.toArray(new String[0]));
            List<String> result = new ArrayList<>(pairs.size());
            for (KeyValue<String, String> pair : pairs) {
                result.add(pair.hasValue() ? pair.getValue() : null);
            }
            return result;
        });
    }

    public boolean hDelete(String key, String... fields) {
        Long result = execute(() -> isCluster()
                ? clusterCommands().hdel(key, fields)
                : commands().hdel(key, fields));
        return result != null && result > 0;
    }

    // ---------------------------------------------------------------- 其他

    /**
     * 自增，返回自增后的值。
     */
    public long incrBy(String key, long delta) {
        Long result = execute(() -> isCluster()
                ? clusterCommands().incrby(key, delta)
                : commands().incrby(key, delta));
        return result == null ? 0L : result;
    }

    /**
     * 执行 Lua 脚本（整数返回），分布式锁的 compare-and-delete / compare-and-expire 走此通道。
     * 脚本 keys 必须同 slot（feather 锁恒为单 key，天然满足）。
     */
    public Long evalInteger(String script, List<String> keys, List<String> values) {
        return execute(() -> isCluster()
                ? clusterCommands().eval(script, ScriptOutputType.INTEGER, keys.toArray(new String[0]),
                        values.toArray(new String[0]))
                : commands().eval(script, ScriptOutputType.INTEGER, keys.toArray(new String[0]),
                        values.toArray(new String[0])));
    }

    // ---------------------------------------------------------------- 内部

    private <T> T execute(Supplier<T> action) {
        try {
            return action.get();
        } catch (RedisException e) {
            log.error("Redis 操作失败: {}", e.getMessage());
            throw new FeatherCacheException("Redis 操作失败", e);
        }
    }
}
