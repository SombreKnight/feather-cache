package io.github.sombreknight.feather.cache.redis;

import io.github.sombreknight.feather.cache.exception.FeatherCacheException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Redis 连接工厂：基于 lettuce-core 自建连接，feather-cache 完全自治。
 *
 * <p>解析 {@link RedisConnectionConfig}（来自 {@code feather.cache.redis.*}），
 * 按部署形态构建：</p>
 * <ul>
 *     <li>{@link RedisConnectionConfig.Mode#STANDALONE}：单机 {@link RedisClient}</li>
 *     <li>{@link RedisConnectionConfig.Mode#SENTINEL}：哨兵 URI（lettuce 原生哨兵协议，
 *         主从动态发现）</li>
 *     <li>{@link RedisConnectionConfig.Mode#CLUSTER}：{@link RedisClusterClient}，拓扑自动发现</li>
 * </ul>
 *
 * <p><b>懒连接</b>：构造只创建客户端不建立连接，首次命令（{@link #sync()} /
 * {@link #syncCluster()}）时才连接；连接失败不缓存，Redis 恢复后下次命令自动重连。
 * 由此 Redis 不可达时应用可正常启动，按 {@code CacheReadMode} 降级——与旧版（0.1.x）
 * StringRedisTemplate 懒连接语义一致。</p>
 *
 * <p>连接由本工厂持有并统一关闭（Spring 容器销毁时经 {@code destroyMethod="close"}），
 * lettuce 同步命令 API 线程安全，多线程共享同一连接多路复用。</p>
 *
 * @author sombreknight
 * @since 1.0.0
 */
public class FeatherRedisConnectionFactory implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FeatherRedisConnectionFactory.class);

    private final RedisConnectionConfig config;
    private final RedisClient client;
    private final RedisClusterClient clusterClient;

    /** 懒初始化连接（首次命令时建立） */
    private volatile StatefulRedisConnection<String, String> connection;
    private volatile StatefulRedisClusterConnection<String, String> clusterConnection;

    public FeatherRedisConnectionFactory(RedisConnectionConfig config) {
        this.config = Objects.requireNonNull(config, "config 不能为空");
        switch (config.getMode()) {
            case STANDALONE -> {
                this.client = RedisClient.create(standaloneUri());
                this.clusterClient = null;
            }
            case SENTINEL -> {
                this.client = RedisClient.create(sentinelUri());
                this.clusterClient = null;
            }
            case CLUSTER -> {
                this.client = null;
                this.clusterClient = RedisClusterClient.create(clusterUris());
            }
            default -> throw new IllegalArgumentException("不支持的 Redis 模式: " + config.getMode());
        }
    }

    /** 是否为集群模式（决定 mget 的取 key 策略与清理方式）。 */
    public boolean isCluster() {
        return config.getMode() == RedisConnectionConfig.Mode.CLUSTER;
    }

    /**
     * 同步命令接口（单机/哨兵模式）；首次调用时建立连接，失败不缓存（可重试/自动恢复）。
     */
    public RedisCommands<String, String> sync() {
        if (connection == null) {
            synchronized (this) {
                if (connection == null) {
                    connection = client.connect();
                }
            }
        }
        return connection.sync();
    }

    /**
     * 同步命令接口（集群模式）；首次调用时建立连接，失败不缓存。
     */
    public RedisClusterCommands<String, String> syncCluster() {
        if (clusterConnection == null) {
            synchronized (this) {
                if (clusterConnection == null) {
                    clusterConnection = clusterClient.connect();
                }
            }
        }
        return clusterConnection.sync();
    }

    /**
     * 清空当前库（集成测试用；集群模式下 lettuce 无法跨节点执行 FLUSHALL，
     * 由调用方逐节点清理）。
     */
    public void flushAll() {
        if (isCluster()) {
            throw new UnsupportedOperationException("集群模式不支持 flushAll，请逐节点清理");
        }
        sync().flushall();
    }

    @Override
    public void close() {
        try {
            if (clusterConnection != null) {
                clusterConnection.close();
            }
            if (connection != null) {
                connection.close();
            }
            if (clusterClient != null) {
                clusterClient.shutdown();
            }
            if (client != null) {
                client.shutdown();
            }
        } catch (Exception e) {
            log.warn("关闭 Redis 连接失败: {}", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- URI 构建

    private RedisURI standaloneUri() {
        RedisURI.Builder builder = RedisURI.Builder.redis(config.getHost(), config.getPort());
        applyCommon(builder);
        return builder.build();
    }

    private RedisURI sentinelUri() {
        if (config.getSentinelMaster() == null || config.getSentinelNodes().isEmpty()) {
            throw new FeatherCacheException(
                    "哨兵模式必须配置 feather.cache.redis.sentinel.master 与 sentinel.nodes");
        }
        String first = config.getSentinelNodes().get(0);
        RedisURI.Builder builder = RedisURI.Builder.sentinel(
                first.split(":")[0], Integer.parseInt(first.split(":")[1]), config.getSentinelMaster());
        for (int i = 1; i < config.getSentinelNodes().size(); i++) {
            String[] parts = config.getSentinelNodes().get(i).split(":");
            builder.withSentinel(parts[0], Integer.parseInt(parts[1]));
        }
        applyCommon(builder);
        return builder.build();
    }

    private List<RedisURI> clusterUris() {
        if (config.getClusterNodes().isEmpty()) {
            throw new FeatherCacheException(
                    "集群模式必须配置 feather.cache.redis.cluster.nodes（逗号分隔的 host:port）");
        }
        List<RedisURI> uris = new ArrayList<>();
        for (String node : config.getClusterNodes()) {
            String[] parts = node.trim().split(":");
            RedisURI.Builder builder = RedisURI.Builder.redis(parts[0], Integer.parseInt(parts[1]));
            applyCommon(builder);
            uris.add(builder.build());
        }
        return uris;
    }

    private void applyCommon(RedisURI.Builder builder) {
        if (config.getPassword() != null && !config.getPassword().isEmpty()) {
            builder.withPassword(config.getPassword());
        }
        if (config.getDatabase() > 0) {
            builder.withDatabase(config.getDatabase());
        }
        if (config.isSsl()) {
            builder.withSsl(true);
            builder.withVerifyPeer(false);
        }
        if (config.getTimeout() != null) {
            builder.withTimeout(config.getTimeout());
        }
    }
}
