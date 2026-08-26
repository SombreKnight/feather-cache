package io.github.sombreknight.feather.cache.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sombreknight.feather.cache.cache.FeatherCache;
import io.github.sombreknight.feather.cache.cache.impl.FeatherCacheImpl;
import io.github.sombreknight.feather.cache.cache.impl.LocalCacheClient;
import io.github.sombreknight.feather.cache.cache.impl.RedisCacheClient;
import io.github.sombreknight.feather.cache.lock.DistributedLockService;
import io.github.sombreknight.feather.cache.redis.FeatherRedisClient;
import io.github.sombreknight.feather.cache.redis.FeatherRedisConnectionFactory;
import io.github.sombreknight.feather.cache.redis.RedisConnectionConfig;
import io.github.sombreknight.feather.cache.support.JsonCodec;
import io.github.sombreknight.feather.cache.support.NamingStrategy;
import io.lettuce.core.RedisClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * feather-cache 自动配置。
 *
 * <p>当配置 {@code feather.cache.enabled=true}（默认）时，基于 lettuce 自建 Redis 连接
 * （配置统一收在 {@code feather.cache.redis.*}），装配：
 * <ul>
 *     <li>{@code FeatherRedisConnectionFactory}：lettuce 连接工厂（standalone/sentinel/cluster）</li>
 *     <li>{@code NamingStrategy}：key 命名单一事实源</li>
 *     <li>{@code FeatherRedisClient}：Redis 薄封装</li>
 *     <li>{@code FeatherCache}：多级缓存服务</li>
 *     <li>{@code DistributedLockService}：分布式锁</li>
 * </ul>
 * 不依赖 Spring Data Redis / {@code spring.data.redis.*}，连接生命周期由本配置管理
 * （容器销毁时自动关闭）。</p>
 *
 * @author sombreknight
 * @since 1.0.0
 */
@AutoConfiguration
@EnableConfigurationProperties(FeatherCacheProperties.class)
@ConditionalOnClass(RedisClient.class)
@ConditionalOnProperty(prefix = "feather.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FeatherCacheAutoConfiguration {

    /**
     * key 命名单一事实源；namespace 优先取 {@code feather.cache.namespace}，否则回退
     * {@code spring.application.name}，再缺省 default。
     */
    @Bean
    @ConditionalOnMissingBean
    public NamingStrategy featherNamingStrategy(FeatherCacheProperties properties, Environment environment) {
        String namespace = properties.getNamespace();
        if (StringUtils.hasText(namespace)) {
            return new NamingStrategy(namespace);
        }
        return new NamingStrategy(environment.getProperty("spring.application.name"));
    }

    /**
     * Redis 连接工厂（lettuce 自建，配置收口 {@code feather.cache.redis.*}；
     * 容器销毁时自动关闭连接与客户端）。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public FeatherRedisConnectionFactory featherRedisConnectionFactory(FeatherCacheProperties properties) {
        FeatherCacheProperties.Redis redis = properties.getRedis();
        RedisConnectionConfig.Builder builder = RedisConnectionConfig.builder()
                .mode(redis.getMode())
                .host(redis.getHost())
                .port(redis.getPort())
                .password(redis.getPassword())
                .database(redis.getDatabase())
                .ssl(redis.isSsl())
                .timeout(redis.getTimeout());
        if (StringUtils.hasText(redis.getCluster().getNodes())) {
            builder.clusterNodes(splitNodes(redis.getCluster().getNodes()));
        }
        if (StringUtils.hasText(redis.getSentinel().getNodes())) {
            builder.sentinelNodes(splitNodes(redis.getSentinel().getNodes()));
            builder.sentinelMaster(redis.getSentinel().getMaster());
        }
        return new FeatherRedisConnectionFactory(builder.build());
    }

    private List<String> splitNodes(String nodes) {
        return Arrays.stream(nodes.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    /**
     * Redis 薄封装（连接由 {@link FeatherRedisConnectionFactory} 管理）。
     */
    @Bean
    @ConditionalOnMissingBean
    public FeatherRedisClient featherRedisClient(FeatherRedisConnectionFactory factory) {
        return new FeatherRedisClient(factory);
    }

    /**
     * JSON 编解码：优先复用 Spring 的 ObjectMapper（web 应用等）；
     * 无则自建（starter 不强制依赖 spring-boot 本体，JacksonAutoConfiguration 未必生效）。
     */
    @Bean
    @ConditionalOnMissingBean
    public JsonCodec featherJsonCodec(ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper mapper = objectMapperProvider.getIfAvailable();
        return mapper == null ? new JsonCodec() : new JsonCodec(mapper);
    }

    /**
     * 本地缓存客户端（Caffeine，容量可经 {@code feather.cache.local.max-size} 配置；
     * 过期时间由调用方 per-key TTL 传入）。
     */
    @Bean
    @ConditionalOnMissingBean
    public LocalCacheClient featherLocalCacheClient(FeatherCacheProperties properties) {
        return new LocalCacheClient(properties.getLocal().getMaxSize(), LocalCacheClient.DEFAULT_TTL);
    }

    /**
     * Redis 缓存客户端。
     */
    @Bean
    @ConditionalOnMissingBean
    public RedisCacheClient featherRedisCacheClient(FeatherRedisClient redisClient) {
        return new RedisCacheClient(redisClient);
    }

    /**
     * 多级缓存服务（防击穿 single-flight 默认单飞，不开放配置）。
     */
    @Bean
    @ConditionalOnMissingBean
    public FeatherCache featherCache(NamingStrategy namingStrategy, LocalCacheClient localCacheClient,
                                     RedisCacheClient redisCacheClient, JsonCodec codec) {
        return new FeatherCacheImpl(namingStrategy, localCacheClient, redisCacheClient, codec);
    }

    /**
     * 分布式锁服务（等待/锁时长/看门狗可经 {@code feather.cache.lock.*} 配置；
     * AutoCloseable bean，容器销毁时自动关闭看门狗调度器）。
     */
    @Bean
    @ConditionalOnMissingBean
    public DistributedLockService distributedLockService(NamingStrategy namingStrategy,
                                                         FeatherRedisClient redisClient,
                                                         FeatherCacheProperties properties) {
        FeatherCacheProperties.Lock lock = properties.getLock();
        return new DistributedLockService(namingStrategy, redisClient,
                lock.isEnableWatchDog(), lock.getDefaultWait(), lock.getDefaultLockDuration());
    }
}
