package io.github.sombreknight.feather.cache.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sombreknight.feather.cache.cache.FeatherCache;
import io.github.sombreknight.feather.cache.cache.impl.FeatherCacheImpl;
import io.github.sombreknight.feather.cache.cache.impl.LocalCacheClient;
import io.github.sombreknight.feather.cache.cache.impl.RedisCacheClient;
import io.github.sombreknight.feather.cache.lock.DistributedLockService;
import io.github.sombreknight.feather.cache.redis.FeatherRedisClient;
import io.github.sombreknight.feather.cache.support.JsonCodec;
import io.github.sombreknight.feather.cache.support.NamingStrategy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

/**
 * feather-cache 自动配置。
 *
 * <p>当配置 {@code feather.cache.enabled=true}（默认）时，基于用户已有的
 * {@code spring.data.redis.*} 连接（Spring Data Redis / Lettuce），装配：
 * <ul>
 *     <li>{@code NamingStrategy}：key 命名单一事实源（M1）</li>
 *     <li>{@code FeatherRedisClient}：Redis 薄封装（M1）</li>
 *     <li>{@code FeatherCache}：多级缓存服务（M2）</li>
 *     <li>{@code DistributedLockService}：分布式锁（M3）</li>
 * </ul>
 * 不创建任何 Redis 连接工厂——直接复用 Spring Boot 的 RedisAutoConfiguration。</p>
 *
 * @author sombreknight
 * @since 0.1.0
 */
@AutoConfiguration
@EnableConfigurationProperties(FeatherCacheProperties.class)
@ConditionalOnClass(StringRedisTemplate.class)
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
     * Redis 薄封装（StringRedisTemplate 由 Spring Boot RedisAutoConfiguration 提供）。
     */
    @Bean
    @ConditionalOnMissingBean
    public FeatherRedisClient featherRedisClient(StringRedisTemplate redisTemplate) {
        return new FeatherRedisClient(redisTemplate);
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
     * 本地缓存客户端（Caffeine，容量/过期可经 {@code feather.cache.local.*} 配置）。
     */
    @Bean
    @ConditionalOnMissingBean
    public LocalCacheClient featherLocalCacheClient(FeatherCacheProperties properties) {
        FeatherCacheProperties.Local local = properties.getLocal();
        return new LocalCacheClient(local.getMaxSize(), local.getTtl());
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
