# Feather Cache 设计文档

> 记录 v0.1.0 的关键设计决策与取舍。为什么这么设计、不做什么、留了什么逃生舱。
> 使用文档见 [usage.md](../usage.md)。

## 1. 定位

对标 feather-orm / feather-rmq：**干净、opinionated、极简配置**。基于 Spring Data Redis
做**多级缓存 + 分布式锁**两件事，不重造连接层、不引入 Redisson 等重量级依赖。

- 连接完全复用 `spring.data.redis.*`（Lettuce），**零额外连接配置**
- 防击穿 / 防穿透 / 批量回源开箱即用，默认值即最佳实践
- 锁走 Lua 原子 + 看门狗续期，try-with-resources 使用

## 2. 与旧 common-sdk（com.kukespace）的关系

feather-cache 从 common-sdk 抽出 redis/cache/lock 三块重建，**去其糟粕留其精华**：

| 旧设计（历史包袱） | feather-cache 的处理 |
|---|---|
| Jedis 2.9 + 手写连接池 + `returnResource` | Spring Data Redis (Lettuce) 托管连接 |
| `executeWithPool` 无差别吞异常返回 null（Redis 挂 → 静默打穿 DB） | `CacheReadMode` 显式降级：FAIL_FAST / RETURN_NULL / FALLBACK_LOCAL |
| unLock `get`+`del` 两步非原子（误删窗口） | Lua compare-and-delete 原子释放 |
| 无看门狗，长任务锁自动过期 | 看门狗按 `lockDuration/3` 续期，close 即停 |
| ThreadLocal 锁记录永不清理（注释自认妥协） | 注册表计数归零即移除，AutoCloseable 结构性防泄漏 |
| `tryLock` 失败白 sleep 50ms | 单次 SET NX 立即返回 |
| 批量 get 循环单查非 pipeline | mget 走 pipeline 单次往返 |
| `NULL_VALUE = "@@NULL@@"` 字符串可能碰撞 | 独立 sentinel scope key，与业务数据空间隔离 |
| 本地缓存 10s/4096 写死且与配置脱节 | 容量/默认 TTL 可配 + **per-key TTL**（Caffeine Expiry） |
| `Semaphore(10)` 掩盖嵌套回源死锁 | 每 key 单飞（1 许可）且按层隔离，并发测试覆盖 |
| `CacheKeyHolder` 死代码、魔法值遍地 | 全部删除 / 配置化 |
| Spring Boot 2.0.3 + javax | Spring Boot 3.5.x + jakarta + JDK 17 |

**保留的精华**：多级缓存（local-first-then-redis）、信号量+双重检查防击穿、
空值占位防穿透、批量回源（MultiCacheLoader）、锁 value 校验防误删（升级为 Lua 原子）。

## 3. 核心概念模型

```
CacheConfig    每次缓存的策略（per-call）：type / redisTtl / localTtl / cacheNull / readMode
CacheLoader    单 key 回源（缓存未命中时）
MultiCacheLoader 批量回源（只回源未命中的 ids）
CacheType      LOCAL_ONLY / REDIS_ONLY / LOCAL_FIRST_THEN_REDIS
CacheReadMode  FAIL_FAST / RETURN_NULL / FALLBACK_LOCAL
NamingStrategy key 命名单一事实源：feather:{app}:{scope}:{key}
FeatherLock    AutoCloseable 锁句柄（可重入 + 看门狗）
```

**关键设计原则：`CacheConfig` 是 per-call 的**——每次 `get` 传入自己的策略，
天然支持 key 维度的差异化（热点 key 长 TTL、易变 key 短 TTL、个别 key 绕过本地等）。

## 4. 命名约定（NamingStrategy 为单一事实源）

```
cache key    = feather:{app}:cache:{key}
lock key     = feather:{app}:lock:{key}
sentinel key = feather:{app}:sentinel:{key}
```

- `{app}` = `feather.cache.namespace` 或 `spring.application.name`（缺省 default）
- 业务只提供最后的 `{key}` 段，前缀由框架生成，禁止业务手拼
- sentinel 独立 scope：空值占位与业务数据空间隔离，不可能碰撞

## 5. 防击穿（single-flight）

缓存过期瞬间大量并发 miss → 全部回源会打垮 DB。方案：

```
并发 miss → 抢该 key 的信号量许可（默认 1）
  ├─ 拿到许可 → 回源 → 写缓存 → 释放
  └─ 未拿到   → 阻塞等待 → 双重检查 → 命中缓存（不回源）
```

- **许可数固定 1（单飞）**，不开放配置——放大回源并发属极少数场景，配置化增加心智负担
- 信号量实例放 Caffeine LRU（1024）自动淘汰，key 无限增长不泄漏
- **按层隔离**（`local:` / `redis:` 前缀）：多级缓存嵌套回源时，本地层与 Redis 层各自单飞，
  避免外层持锁、内层等同一把锁死锁（common-sdk 的 `Semaphore(10)` 恰好掩盖了此问题）

## 6. 防穿透（sentinel 空值占位）

回源结果为 null 且 `cacheNull(true)` 时，在**独立 sentinel key** 写入占位（默认 30s），
后续请求直接判空不再回源；批量场景同样批量短路。

- sentinel key 空间与业务数据隔离 → 无字符串碰撞问题
- 空值 TTL 独立（30s < 业务 TTL），快速恢复
- `evict` / `put` 会同时清理 sentinel

## 7. 分层 TTL（per-key 时效控制）

- `redisTtl`：Redis 层 TTL（默认 2min）
- `localTtl`：本地层 TTL（默认 10s），本地缓存支持 **per-key TTL**
  （Caffeine `Expiry` 接口，每次 set 的 ttl 决定该 entry 过期时间）
- 多级缓存下两层各自独立计时：热点 key 可 `localTtl(30s)`，易变 key 可 `localTtl(2s)`

## 8. 分布式锁（Lua 原子 + 看门狗 + 可重入）

### 加锁（SET NX EX，原子）

```
SET feather:{app}:lock:{key} {randomValue} NX EX {lockDuration}
```

- value = 随机 UUID：释放/续期时校验归属
- 阻塞版自旋轮询（50ms），超时抛类型化 `LockTimeoutException`
- 非阻塞版（tryLock）单次尝试返回 `Optional`

### 释放（Lua compare-and-delete，原子）

```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
```

value 不匹配（锁已过期易主）→ 不做任何操作，**绝不误删他人锁**。

### 看门狗续期（Lua compare-and-expire）

持有期间每 `lockDuration/3` 续期一次；value 不匹配立即停止。
业务执行超过锁时长不提前失锁；`close()` 先停续期再释放，避免"释放后又续期复活"。

### 可重入

同线程重复 lock 同一 key → 返回同一实例（计数 +1）；close 对齐递减，归零才真正释放。
线程注册表（ThreadLocal）计数归零即移除，线程销毁整体回收，无累积泄漏。

### 为什么用 AutoCloseable 而非 lock/unLock 命令式

| 命令式（common-sdk） | AutoCloseable（feather） |
|---|---|
| 漏写 finally → 锁泄漏 | 编译器保证 close 必然执行，结构上不可能泄漏 |
| unLock 靠字符串 key，拼错解错锁 | 锁对象自带 value，Lua 校验，无解错路径 |
| 重入靠 ThreadLocal 魔法 | 锁对象 + 注册表计数，天然嵌套安全 |
| 看门狗需全局跟踪持有者 | 续期任务绑定锁对象生命周期 |

## 9. 异常与降级策略

- **不吞异常**：底层 `DataAccessException` 统一转译 `FeatherCacheException` 抛出
- 缓存读取故障按 `CacheReadMode` 显式降级（见 usage 2.6）
- 锁获取失败抛 `LockTimeoutException`（带 key + wait，可捕获走降级逻辑）

## 10. 明确不做的（v0.1）

- ❌ 注解式缓存（`@Cacheable` 风格）——命令式 API 打磨稳定后 v0.2 候选
- ❌ Spring Cache `CacheManager` 适配——v0.2 候选
- ❌ 多级缓存一致性广播——本地缓存无跨实例失效，**最终一致窗口 = localTtl**
  （需要跨实例即时失效的场景：用 `REDIS_ONLY`；pub/sub 广播失效 v0.2 候选）
- ❌ 布隆过滤器 / 分布式限流 / 分布式信号量
- ❌ 锁的公平性 / 等待队列 —— 自旋轮询已满足 99% 场景

## 11. 逃生舱

- 自定义本地缓存实现：实现 `CacheClient` SPI 替换默认 Caffeine 实现
- `DistributedLockService` 可整体关闭看门狗（`feather.cache.lock.enable-watch-dog=false`）
- 自定义 `CacheReadMode` 之外的高级降级：捕获 `FeatherCacheException` 自行处理
