package io.github.sombreknight.feather.cache.autoconfigure;

import io.github.sombreknight.feather.cache.cache.impl.LocalCacheClient;
import io.github.sombreknight.feather.cache.lock.DistributedLockService;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * feather-cache 配置项（前缀 {@code feather.cache}）。
 *
 * <p>连接层不在此配置——Redis 连接完全复用 {@code spring.data.redis.*}。</p>
 *
 * <pre>{@code
 * feather:
 *   cache:
 *     enabled: true
 *     namespace: order-service        # 可选，默认取 spring.application.name
 *     local:
 *       max-size: 4096                # 本地缓存最大条目数
 *       ttl: 10s                      # 本地缓存过期（全局统一，无 per-key TTL）
 *     lock:
 *       default-wait: 3s              # lock() 默认等待超时
 *       default-lock-duration: 30s    # 锁默认时长（看门狗开启时自动续期）
 *       enable-watch-dog: true        # 看门狗续期总开关
 * }</pre>
 *
 * @author sombreknight
 * @since 0.1.0
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

    public Local getLocal() {
        return local;
    }

    public Lock getLock() {
        return lock;
    }

    public static class Local {

        /**
         * 本地缓存最大条目数（超限按 LRU 淘汰最久未访问的条目）。
         */
        private int maxSize = LocalCacheClient.DEFAULT_MAX_SIZE;

        /**
         * 本地缓存过期时间（全局统一，无 per-key TTL；支持 10s / 5m / 1h 等格式）。
         */
        private Duration ttl = LocalCacheClient.DEFAULT_TTL;

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
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
