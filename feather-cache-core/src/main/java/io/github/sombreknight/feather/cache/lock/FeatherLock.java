package io.github.sombreknight.feather.cache.lock;

/**
 * 分布式锁句柄（AutoCloseable）。
 *
 * <p>使用 try-with-resources，编译器保证释放必然执行，从结构上杜绝锁泄漏：</p>
 * <pre>{@code
 * try (FeatherLock ignored = lockService.lock("order:123")) {
 *     // 临界区
 * }
 * }</pre>
 *
 * <p>锁对象内部持有持有者 value，{@link #close()} 走 Lua 原子 compare-and-delete，
 * 不存在"key 拼错解错锁"的路径。同一线程重复 lock 同一 key 返回同一实例（可重入），
 * close 次数与 lock 次数对齐。</p>
 *
 * @author sombreknight
 * @since 0.1.0
 */
public interface FeatherLock extends AutoCloseable {

    /**
     * 业务锁 key（不含框架前缀）。
     */
    String getKey();

    /**
     * 释放锁（幂等，可多次调用；同时停止看门狗续期）。
     */
    @Override
    void close();
}
