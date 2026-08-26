package io.github.sombreknight.feather.cache.redis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Redis 连接配置（{@code feather.cache.redis.*} 的绑定载体）。
 *
 * <p>三种部署形态：</p>
 * <ul>
 *     <li>{@link Mode#STANDALONE}（默认）：{@code host} + {@code port} + 可选
 *         {@code password} / {@code database} / {@code ssl}</li>
 *     <li>{@link Mode#SENTINEL}：{@code sentinel.master} + {@code sentinel.nodes}，
 *         主从由哨兵动态发现</li>
 *     <li>{@link Mode#CLUSTER}：{@code cluster.nodes}（任意/全部节点），拓扑自动发现</li>
 * </ul>
 *
 * @author sombreknight
 * @since 1.0.0
 */
public class RedisConnectionConfig {

    /** 部署形态 */
    public enum Mode {
        /** 单机 Redis（默认） */
        STANDALONE,
        /** 哨兵模式（主从动态发现） */
        SENTINEL,
        /** Redis Cluster（分片集群，拓扑自动发现） */
        CLUSTER
    }

    private final Mode mode;
    private final String host;
    private final int port;
    private final String password;
    private final int database;
    private final boolean ssl;
    private final Duration timeout;
    private final String sentinelMaster;
    private final List<String> sentinelNodes;
    private final List<String> clusterNodes;

    private RedisConnectionConfig(Builder builder) {
        this.mode = Objects.requireNonNull(builder.mode, "mode 不能为空");
        this.host = builder.host;
        this.port = builder.port;
        this.password = builder.password;
        this.database = builder.database;
        this.ssl = builder.ssl;
        this.timeout = builder.timeout;
        this.sentinelMaster = builder.sentinelMaster;
        this.sentinelNodes = builder.sentinelNodes == null ? List.of() : List.copyOf(builder.sentinelNodes);
        this.clusterNodes = builder.clusterNodes == null ? List.of() : List.copyOf(builder.clusterNodes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Mode getMode() {
        return mode;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getPassword() {
        return password;
    }

    public int getDatabase() {
        return database;
    }

    public boolean isSsl() {
        return ssl;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public String getSentinelMaster() {
        return sentinelMaster;
    }

    public List<String> getSentinelNodes() {
        return sentinelNodes;
    }

    public List<String> getClusterNodes() {
        return clusterNodes;
    }

    /** 连接配置构建器。 */
    public static class Builder {

        private Mode mode = Mode.STANDALONE;
        private String host = "localhost";
        private int port = 6379;
        private String password;
        private int database;
        private boolean ssl;
        private Duration timeout = Duration.ofSeconds(3);
        private String sentinelMaster;
        private List<String> sentinelNodes = new ArrayList<>();
        private List<String> clusterNodes = new ArrayList<>();

        public Builder mode(Mode mode) {
            this.mode = mode;
            return this;
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder database(int database) {
            this.database = database;
            return this;
        }

        public Builder ssl(boolean ssl) {
            this.ssl = ssl;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder sentinelMaster(String sentinelMaster) {
            this.sentinelMaster = sentinelMaster;
            return this;
        }

        public Builder sentinelNodes(List<String> sentinelNodes) {
            this.sentinelNodes = sentinelNodes;
            return this;
        }

        public Builder clusterNodes(List<String> clusterNodes) {
            this.clusterNodes = clusterNodes;
            return this;
        }

        public RedisConnectionConfig build() {
            return new RedisConnectionConfig(this);
        }
    }
}
