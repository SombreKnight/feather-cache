package io.github.sombreknight.feather.cache.autoconfigure;

import io.github.sombreknight.feather.cache.cache.impl.LocalCacheClient;
import io.github.sombreknight.feather.cache.lock.DistributedLockService;
import io.github.sombreknight.feather.cache.redis.RedisConnectionConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * feather-cache 配置项（前缀 {@code feather.cache}）。
 *
 * <p>连接层完全自治：Redis 连接基于 lettuce 自建，配置统一收在 {@code feather.cache.redis.*}，
 * 不依赖 {@code spring.data.redis.*}。</p>
 *
 * <pre>{@code
 * feather:
 *   cache:
 *     enabled: true
 *     namespace: order-service        # 可选，默认取 spring.application.name
 *     redis:                          # Redis 连接（lettuce 自建，自闭环）
 *       mode: standalone              # standalone | sentinel | cluster
 *       host: localhost
 *       port: 6379
 *       password:                     # 可选
 *       database: 0
 *       ssl: false
 *       timeout: 3s
 *       cluster:                      # mode=cluster 时必填
 *         nodes: redis-1:6379,redis-2:6379,redis-3:6379
 *       sentinel:                     # mode=sentinel 时必填
 *         master: mymaster
 *         nodes: sentinel-1:26379,sentinel-2:26379
 *     local:
 *       max-size: 4096                # 本地缓存最大条目数（过期由 per-key TTL 控制）
 *     lock:
 *       default-wait: 3s              # lock() 默认等待超时
 *       default-lock-duration: 30s    # 锁默认时长（看门狗开启时自动续期）
 *       enable-watch-dog: true        # 看门狗续期总开关
 * }</pre>
 *
 * @author sombreknight
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "feather.cache")
public class FeatherCacheProperties {

    /**
     * 是否启用 feather-cache（默认启用）。
     */
    private boolean enabled = true;

    /**
     * 全局 key 命名空间（默认取 spring.application.name，用于 {@code feather:{app}:*} key 前缀）。
     */
    private String namespace;

    /**
     * Redis 连接配置（lettuce 自建，自闭环）。
     */
    private final Redis redis = new Redis();

    /**
     * 本地缓存配置。
     */
    private final Local local = new Local();

    /**
     * 分布式锁配置。
     */
    private final Lock lock = new Lock();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public Redis getRedis() {
        return redis;
    }

    public Local getLocal() {
        return local;
    }

    public Lock getLock() {
        return lock;
    }

    /**
     * Redis 连接配置（lettuce 自建，自闭环）。
     */
    public static class Redis {

        /**
         * 部署形态：standalone（单机，默认）/ sentinel（哨兵）/ cluster（集群）。
         */
        private RedisConnectionConfig.Mode mode = RedisConnectionConfig.Mode.STANDALONE;

        /**
         * 单机/哨兵模式主机（默认 localhost）。
         */
        private String host = "localhost";

        /**
         * 单机/哨兵模式端口（默认 6379）。
         */
        private int port = 6379;

        /**
         * 密码（无认证可不配）。
         */
        private String password;

        /**
         * 逻辑库索引（默认 0）。
         */
        private int database;

        /**
         * 是否启用 SSL（默认 false）。
         */
        private boolean ssl;

        /**
         * 命令超时（默认 3s）。
         */
        private Duration timeout = Duration.ofSeconds(3);

        private final Cluster cluster = new Cluster();

        private final Sentinel sentinel = new Sentinel();

        public RedisConnectionConfig.Mode getMode() {
            return mode;
        }

        public void setMode(RedisConnectionConfig.Mode mode) {
            this.mode = mode;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getDatabase() {
            return database;
        }

        public void setDatabase(int database) {
            this.database = database;
        }

        public boolean isSsl() {
            return ssl;
        }

        public void setSsl(boolean ssl) {
            this.ssl = ssl;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Cluster getCluster() {
            return cluster;
        }

        public Sentinel getSentinel() {
            return sentinel;
        }
    }

    /**
     * 集群模式配置（{@code mode=cluster} 时必填）。
     */
    public static class Cluster {

        /**
         * 节点列表，逗号分隔 {@code host:port}（列出任意/全部节点，拓扑自动发现）。
         */
        private String nodes;

        public String getNodes() {
            return nodes;
        }

        public void setNodes(String nodes) {
            this.nodes = nodes;
        }
    }

    /**
     * 哨兵模式配置（{@code mode=sentinel} 时必填）。
     */
    public static class Sentinel {

        /**
         * 哨兵监控的主节点名称。
         */
        private String master;

        /**
         * 哨兵节点列表，逗号分隔 {@code host:port}。
         */
        private String nodes;

        public String getMaster() {
            return master;
        }

        public void setMaster(String master) {
            this.master = master;
        }

        public String getNodes() {
            return nodes;
        }

        public void setNodes(String nodes) {
            this.nodes = nodes;
        }
    }

    public static class Local {

        /**
         * 本地缓存最大条目数（超限按 LRU 淘汰最久未访问的条目）。
         */
        private int maxSize = LocalCacheClient.DEFAULT_MAX_SIZE;

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }
    }

    public static class Lock {

        /**
         * lock() 默认等待超时（支持 3s / 1m 等格式；超过则放弃获取锁）。
         */
        private Duration defaultWait = DistributedLockService.DEFAULT_WAIT;

        /**
         * 锁默认时长（超过自动过期释放；看门狗开启时自动续期，长任务不会提前释放）。
         */
        private Duration defaultLockDuration = DistributedLockService.DEFAULT_LOCK;

        /**
         * 看门狗续期总开关：开启后锁在默认时长内被自动续期，防止长任务持锁期间锁过期。
         */
        private boolean enableWatchDog = true;

        public Duration getDefaultWait() {
            return defaultWait;
        }

        public void setDefaultWait(Duration defaultWait) {
            this.defaultWait = defaultWait;
        }

        public Duration getDefaultLockDuration() {
            return defaultLockDuration;
        }

        public void setDefaultLockDuration(Duration defaultLockDuration) {
            this.defaultLockDuration = defaultLockDuration;
        }

        public boolean isEnableWatchDog() {
            return enableWatchDog;
        }

        public void setEnableWatchDog(boolean enableWatchDog) {
            this.enableWatchDog = enableWatchDog;
        }
    }
}
