package io.github.sombreknight.feather.cache.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * feather-cache 自动配置。
 *
 * <p>当配置 {@code feather.cache.enabled=true}（默认）时，基于用户已有的
 * {@code spring.data.redis.*} 连接（Spring Data Redis / Lettuce），装配：
 * <ul>
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

    // Bean 装配在 M1（redis）/ M2（cache）/ M3（lock）里程碑逐步加入
}
