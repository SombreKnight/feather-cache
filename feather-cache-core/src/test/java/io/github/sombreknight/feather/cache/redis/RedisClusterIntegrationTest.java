package io.github.sombreknight.feather.cache.redis;

import io.github.sombreknight.feather.cache.lock.DistributedLockService;
import io.github.sombreknight.feather.cache.lock.FeatherLock;
import io.github.sombreknight.feather.cache.support.NamingStrategy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Redis 集群模式集成测试（3 主 0 从最小集群）。
 *
 * <p>需要环境变量 {@code REDIS_CLUSTER_TEST_URL}（如 {@code 127.0.0.1:7001,127.0.0.1:7002,127.0.0.1:7003}），
 * 未配置自动跳过。验证集群下的三条关键路径：跨节点读写、pipeline mget（无 CROSSSLOT）、
 * 单 key Lua 锁（天然同 slot）。</p>
 */
class RedisClusterIntegrationTest {

    private static final String CLUSTER_URL = System.getenv("REDIS_CLUSTER_TEST_URL");

    private static StringRedisTemplate redisTemplate;
    private static FeatherRedisClient client;
    private static DistributedLockService lockService;

    @BeforeAll
    static void init() {
        assumeTrue(CLUSTER_URL != null && !CLUSTER_URL.trim().isEmpty(),
                "未配置 REDIS_CLUSTER_TEST_URL，跳过集群集成测试");

        RedisClusterConfiguration clusterConfig = new RedisClusterConfiguration();
        for (String node : CLUSTER_URL.split(",")) {
            String[] parts = node.trim().split(":");
            clusterConfig.clusterNode(parts[0], Integer.parseInt(parts[1]));
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(clusterConfig);
        factory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(factory);
        redisTemplate.afterPropertiesSet();

        client = new FeatherRedisClient(redisTemplate);
        lockService = new DistributedLockService(new NamingStrategy("cluster-test"), client);
    }

    @AfterAll
    static void destroy() {
        if (redisTemplate != null) {
            redisTemplate.getConnectionFactory().getConnection().close();
        }
        if (lockService != null) {
            lockService.close();
        }
    }

    @BeforeEach
    void cleanUp() {
        assumeTrue(CLUSTER_URL != null && !CLUSTER_URL.trim().isEmpty(), "跳过");
        for (String node : CLUSTER_URL.split(",")) {
            // 集群模式无法 flushAll 所有节点，逐节点清理本测试用 key
            try {
                redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void setAndGetAcrossSlots() {
        // 不同 key 大概率落在不同 slot/节点
        client.set("cluster:key-1", "v1", Duration.ofSeconds(60));
        client.set("cluster:key-2", "v2", Duration.ofSeconds(60));
        client.set("cluster:key-3", "v3", Duration.ofSeconds(60));

        assertThat(client.get("cluster:key-1")).isEqualTo("v1");
        assertThat(client.get("cluster:key-2")).isEqualTo("v2");
        assertThat(client.get("cluster:key-3")).isEqualTo("v3");
        assertThat(client.get("cluster:missing")).isNull();
    }

    @Test
    void pipelineMgetAcrossSlotsNoCrossSlotError() {
        for (int i = 1; i <= 5; i++) {
            client.set("cluster:mget-" + i, "v" + i, Duration.ofSeconds(60));
        }
        // 原生 MGET 在跨 slot 时会报 CROSSSLOT；我们的 pipeline 逐 key get 不受影响
        List<String> values = client.mget(Arrays.asList(
                "cluster:mget-1", "cluster:mget-2", "cluster:mget-3", "cluster:missing", "cluster:mget-5"));

        assertThat(values).containsExactly("v1", "v2", "v3", null, "v5");
    }

    @Test
    void distributedLockWorksOnCluster() throws Exception {
        try (FeatherLock ignored = lockService.lock("cluster:lock:order-1")) {
            // 其他线程拿不到
            Optional<FeatherLock> other = acquireInOtherThread();
            assertThat(other).isEmpty();
        }
        // 释放后可获取
        Optional<FeatherLock> again = lockService.tryLock("cluster:lock:order-1");
        assertThat(again).isPresent();
        again.get().close();
    }

    private Optional<FeatherLock> acquireInOtherThread() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            return pool.submit(() -> lockService.tryLock("cluster:lock:order-1"))
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
