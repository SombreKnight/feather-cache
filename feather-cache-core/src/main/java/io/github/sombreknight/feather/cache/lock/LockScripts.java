package io.github.sombreknight.feather.cache.lock;

/**
 * 分布式锁 Lua 脚本（单一事实源）。
 *
 * <p>加锁不在此列——SET NX EX 本身是原子命令（见 {@code FeatherRedisClient#setIfAbsent}）；
 * 这里只放必须原子、无法用单命令完成的释放与续期。</p>
 *
 * @author sombreknight
 * @since 0.1.0
 */
public final class LockScripts {

    private LockScripts() {
    }

    /**
     * 原子释放（compare-and-delete）：
     * 仅当 key 的当前值等于持有者 value 时才删除，否则返回 0（锁已易主，不做任何操作）。
     *
     * <p>KEYS[1] = lockKey，ARGV[1] = value</p>
     */
    public static final String UNLOCK = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """;

    /**
     * 原子续期（看门狗）：
     * 仅当 key 的当前值等于持有者 value 时才续期，否则返回 0（锁已易主/过期，停止续期）。
     *
     * <p>KEYS[1] = lockKey，ARGV[1] = value，ARGV[2] = 续期秒数</p>
     */
    public static final String RENEW = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('expire', KEYS[1], ARGV[2])
            else
                return 0
            end
            """;
}
