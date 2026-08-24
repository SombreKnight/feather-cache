# Feather Cache 使用指南

> 完整 API 与最佳实践。设计决策见 [docs/design.md](docs/design.md)；可运行示例见 `feather-cache-samples`。

## 1. 引入与配置

### 引入依赖

```xml
<dependency>
    <groupId>io.github.sombreknight</groupId>
    <artifactId>feather-cache-spring-boot-starter</artifactId>
    <version>0.1.1</version>
</dependency>
```

### 配置（连接完全复用 `spring.data.redis.*`）

```yaml
spring:
  application:
    name: order-service        # 用于 key 前缀 {app}，如 feather:order-service:cache:user:123
  data:
    redis:
      host: localhost
      port: 6379

feather:
  cache:
    enabled: true              # 默认启用
    # namespace: order-service # 可选，覆盖 spring.application.name 作为 key 前缀
    local:
      max-size: 4096           # 本地缓存最大条目数
      ttl: 10s                 # 本地缓存默认过期（per-key TTL 未指定时兜底）
    lock:
      default-wait: 3s         # lock() 默认等待超时
      default-lock-duration: 30s  # 锁默认时长（看门狗自动续期）
      enable-watch-dog: true   # 看门狗续期总开关
```

> 框架**不创建连接工厂**——零额外连接配置。`spring.data.redis.*` 怎么配，框架就怎么连。

### 集群 / 哨兵模式

feather-cache 不感知部署形态——连接完全由 `spring.data.redis.*` 决定，单机/哨兵/集群/分片集群**改配置即切换，零代码改动**（底层 Lettuce 自动拓扑发现与路由）：

```yaml
spring:
  data:
    redis:
      cluster:
        nodes: redis-1:6379,redis-2:6379,redis-3:6379   # 集群：列出任意/全部节点
#      sentinel:                                        # 哨兵（二选一）
#        master: mymaster
#        nodes: sentinel-1:26379,sentinel-2:26379
```

**集群兼容性说明**（已实测验证）：
- 批量读走 **pipeline 逐 key get**（非原生 MGET）——key 跨 slot 时无 CROSSSLOT 错误，Lettuce 按 slot 自动路由
- 锁的 Lua 脚本为**单 key**（`KEYS[1]`）——Redis Cluster 要求脚本 keys 同 slot，单 key 天然满足
- 集群故障转移窗口（主节点宕机未同步到从节点时的锁丢失）是单实例锁的固有限制，非 Redlock 实现均存在

> 集成测试：`REDIS_CLUSTER_TEST_URL=127.0.0.1:7001,127.0.0.1:7002,...` 启用（未配置自动跳过，同 REDIS_TEST_URL 策略）。

```yaml
spring:
  application:
    name: order-service        # 用于 key 前缀 {app}，如 feather:order-service:cache:user:123
  data:
    redis:
      host: localhost
      port: 6379

feather:
  cache:
    enabled: true              # 默认启用
    # namespace: order-service # 可选，覆盖 spring.application.name 作为 key 前缀
    local:
      max-size: 4096           # 本地缓存最大条目数
      ttl: 10s                 # 本地缓存默认过期（per-key TTL 未指定时兜底）
    lock:
      default-wait: 3s         # lock() 默认等待超时
      default-lock-duration: 30s  # 锁默认时长（看门狗自动续期）
      enable-watch-dog: true   # 看门狗续期总开关
```

> 框架**不创建连接工厂**——零额外连接配置。`spring.data.redis.*` 怎么配，框架就怎么连。

## 2. 缓存（FeatherCache）

### 2.1 注入与类型引用

```java
@Resource
private FeatherCache cache;

// 泛型反序列化用 TypeReference
private static final TypeReference<Order> ORDER_TYPE = new TypeReference<>() {};
```

### 2.2 缓存配置（CacheConfig）

```java
import static io.github.sombreknight.feather.cache.cache.CacheConfig.*;

CacheConfig redis  = redis();                                     // Redis 单层，2min
CacheConfig redis5 = redis(Duration.ofMinutes(5));                // Redis 单层，自定义 TTL
CacheConfig local  = local();                                     // 仅本地
CacheConfig multi  = multi()                                      // 多级：Redis + 本地
        .redisTtl(Duration.ofMinutes(5))                          //   Redis 层 5min
        .localTtl(Duration.ofSeconds(10));                        //   本地层 10s（per-key 独立）
CacheConfig safe   = redis(Duration.ofMinutes(1)).cacheNull(true) // 空值防穿透
        .readMode(CacheReadMode.RETURN_NULL);                     // Redis 故障降级返回 null
```

**TTL 语义**：`redisTtl` 作用于 Redis 层、`localTtl` 作用于本地层（本地缓存支持 per-key TTL，
每个 key 通过自己的 `CacheConfig` 独立控制时效）。

### 2.3 单查（未命中自动回源 + 防击穿）

```java
Order order = cache.get("order:123", ORDER_TYPE,
        multi().redisTtl(Duration.ofMinutes(5)).localTtl(Duration.ofSeconds(10)),
        key -> orderDao.findById(123));   // CacheLoader：缓存未命中时的回源
```

- **防击穿**：同一 key 并发 miss 时，single-flight 只放行 1 个回源，其余线程等待后命中缓存
- **防穿透**：`cacheNull(true)` 时回源 null 写独立 sentinel 占位（默认 30s），后续请求直接判空

### 2.4 批量查（pipeline + 批量回源）

```java
Map<String, Order> orders = cache.gets(
        List.of("1", "2", "3"),
        id -> "order:" + id,                                  // 业务 id → 缓存 key
        ORDER_TYPE,
        multi(),
        missingIds -> orderDao.findByIds(missingIds));        // 只回源未命中的 ids
```

### 2.5 主动刷新与删除

```java
cache.put("order:123", order, multi());   // 业务更新后刷新缓存（同时清空值占位）
cache.evict("order:123");                 // 删除缓存（本地 + Redis + 空值占位）
```

### 2.6 故障降级（CacheReadMode）

| 模式 | 行为 | 适用 |
|---|---|---|
| `FAIL_FAST`（默认） | Redis 故障抛 `FeatherCacheException` | 数据正确性优先 |
| `RETURN_NULL` | 故障按未命中返回 null + WARN 日志 | 可容忍短暂空窗 |
| `FALLBACK_LOCAL` | 故障回退本地缓存 | 读多写少场景 |

```java
CacheConfig config = redis().readMode(CacheReadMode.FALLBACK_LOCAL);
```

## 3. 分布式锁（DistributedLockService）

### 3.1 try-with-resources（推荐，结构性防泄漏）

```java
@Resource
private DistributedLockService lockService;

try (FeatherLock ignored = lockService.lock("pay:123")) {      // 阻塞，默认等 3s
    // 临界区：扣款、状态流转……
}
// 编译器保证 close() 必然执行，不可能漏释放
```

### 3.2 自定义等待与锁时长

```java
try (FeatherLock ignored = lockService.lock("pay:123",
        Duration.ofSeconds(5),      // 等待获取超时
        Duration.ofSeconds(30))) {  // 锁时长（看门狗自动续期，长任务不担心失锁）
    ...
}
```

### 3.3 非阻塞尝试

```java
Optional<FeatherLock> lock = lockService.tryLock("pay:123");
if (lock.isPresent()) {
    try (FeatherLock ignored = lock.get()) { ... }
} else {
    return "系统繁忙";   // 拿不到立即降级
}
```

### 3.4 模板方法

```java
lockService.execute("pay:123", () -> doSomething());       // Runnable
String result = lockService.execute("pay:123", () -> doAndReturn());  // 带返回值
```

### 3.5 锁语义保证

- **原子加锁**：`SET key value NX EX seconds`
- **原子释放**：Lua compare-and-delete，value 不匹配（锁已易主）绝不误删
- **可重入**：同线程重复 lock 同一 key 返回同一实例，close 次数对齐才真正释放
- **看门狗**：默认每 `lockDuration/3` 续期一次，业务执行超过锁时长不提前失锁；可整体关闭
- **超时异常**：拿不到锁抛类型化 `LockTimeoutException`（可捕获处理），非字符串异常

## 4. 缓存 key 命名约定（NamingStrategy）

所有 key 统一 `feather:{app}:{scope}:{key}`：

```
feather:order-service:cache:user:123       缓存数据（scope=cache）
feather:order-service:lock:pay:123         分布式锁（scope=lock）
feather:order-service:sentinel:user:123    空值占位（scope=sentinel，与业务数据隔离）
```

业务只提供最后的 `key` 段（如 `user:123`），前缀由框架生成。

## 5. 明确不做的（v0.1）

- ❌ 注解式缓存（`@Cacheable` 风格）——v0.2 候选
- ❌ Spring Cache `CacheManager` 适配——v0.2 候选
- ❌ 多级缓存一致性广播（本地缓存无跨实例失效，最终一致窗口 = localTtl）——v0.2 候选
- ❌ 布隆过滤器 / 分布式限流

## 6. 示例（samples 模块）

本地运行：

```bash
docker run -d --name redis -p 6379:6379 redis:7
mvn spring-boot:run -pl feather-cache-samples   # 端口 9090
```

```bash
curl -X POST localhost:9090/demo/order/1 -H 'Content-Type: application/json' -d '{"status":"CREATED"}'
curl localhost:9090/demo/order/1                # 第一次回源（~50ms），后续缓存命中
curl "localhost:9090/demo/orders?ids=1,2,3"
curl -X POST localhost:9090/demo/pay/1          # 分布式锁
```
