package io.github.sombreknight.feather.cache.samples.service;

import io.github.sombreknight.feather.cache.lock.DistributedLockService;
import io.github.sombreknight.feather.cache.lock.FeatherLock;
import io.github.sombreknight.feather.cache.lock.LockTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.util.Optional;

/**
 * 分布式锁演示：FeatherLock 的三种使用方式。
 */
@Service
public class LockDemoService {

    private static final Logger log = LoggerFactory.getLogger(LockDemoService.class);

    @Resource
    private DistributedLockService lockService;

    /**
     * 方式一：try-with-resources（推荐）。阻塞获取，拿不到抛 LockTimeoutException。
     */
    public String payWithLock(String orderId) {
        try (FeatherLock ignored = lockService.lock("pay:" + orderId)) {
            // 临界区：幂等扣款、状态流转等
            log.info("支付处理中: orderId={}", orderId);
            return "PAID";
        } catch (LockTimeoutException e) {
            return "BUSY";
        }
    }

    /**
     * 方式二：tryLock 非阻塞，拿不到立即降级。
     */
    public String tryPayWithLock(String orderId) {
        Optional<FeatherLock> lock = lockService.tryLock("pay:" + orderId, Duration.ofSeconds(5));
        if (lock.isEmpty()) {
            return "SYSTEM_BUSY";
        }
        try (FeatherLock ignored = lock.get()) {
            return "PAID";
        }
    }

    /**
     * 方式三：execute 模板方法，锁内执行并返回结果。
     */
    public String executePay(String orderId) {
        return lockService.execute("pay:" + orderId, () -> {
            log.info("execute 模板锁内执行: orderId={}", orderId);
            return "PAID";
        });
    }
}
