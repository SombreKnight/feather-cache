# Feather Cache

[![Maven Central](https://img.shields.io/maven-central/v/io.github.sombreknight/feather-cache-spring-boot-starter)](https://search.maven.org/artifact/io.github.sombreknight/feather-cache-spring-boot-starter)
[![CI](https://github.com/SombreKnight/feather-cache/actions/workflows/ci.yml/badge.svg)](https://github.com/SombreKnight/feather-cache/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

基于 Spring Data Redis 的轻量级**多级缓存 + 分布式锁**框架。对标 [feather-orm](https://github.com/SombreKnight/feather-orm) / [feather-rmq](https://github.com/SombreKnight/feather-rmq) 的极简设计哲学：
**不重造连接层，复用 `spring.data.redis.*` 配置；缓存防击穿/防穿透开箱即用；分布式锁 Lua 原子 + 看门狗续期。**

## 特性

- **多级缓存**：`LOCAL_ONLY` / `REDIS_ONLY` / `LOCAL_FIRST_THEN_REDIS` 三种模式，Caffeine 本地 + Redis 远端
- **防击穿**：每 key 单飞信号量 + 双重检查，缓存重建只放行 1 个回源（按层隔离，无死锁）
- **防穿透**：空值 sentinel 占位（独立 key 空间，不可能碰撞），可选开关
- **分层 TTL**：`redisTtl` / `localTtl` 双 TTL，本地缓存支持 **per-key 时效**（每个 key 独立控制）
- **批量回源**：`MultiCacheLoader` 按 ids 批量加载，mget 走 pipeline
- **分布式锁**：Lua 原子加锁/释放（compare-and-delete）、看门狗自动续期、重入计数、
  try-with-resources 使用，杜绝锁泄漏与误删
- **异常策略显式化**：Redis 故障 fail-fast 或显式降级（RETURN_NULL / FALLBACK_LOCAL），绝不静默吞异常
- **零配置接入**：只需 `spring.data.redis.*` 连接配置，无额外连接工厂

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>io.github.sombreknight</groupId>
    <artifactId>feather-cache-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

### 2. 配置（复用 `spring.data.redis.*`）

```yaml
spring:
  application:
    name: order-service
  data:
    redis:
      host: localhost
      port: 6379

feather:
  cache:
    enabled: true        # 默认启用
```

### 3. 使用缓存

```java
@Resource
private FeatherCache cache;

private static final TypeReference<Order> ORDER_TYPE = new TypeReference<>() {};

// 多级缓存：本地 10s → Redis 5min → DB 回源（自动防击穿，可选防穿透）
Order order = cache.get("order:123", ORDER_TYPE,
        CacheConfig.multi()
                .redisTtl(Duration.ofMinutes(5))
                .localTtl(Duration.ofSeconds(10)),
        key -> orderDao.findById(123));
```

### 4. 使用分布式锁

```java
// try-with-resources：编译器保证释放必然执行，超时抛 LockTimeoutException
@Resource
private DistributedLockService lockService;

try (FeatherLock ignored = lockService.lock("pay:123")) {
    // 临界区（看门狗自动续期，长任务不担心失锁）
}

// 或模板方法
lockService.execute("pay:123", () -> doSomething());
```

## 模块

| 模块 | 说明 |
|---|---|
| `feather-cache-core` | 核心：Redis 封装、多级缓存、分布式锁（零 boot 依赖） |
| `feather-cache-spring-boot-starter` | Spring Boot 自动配置 |
| `feather-cache-samples` | 可运行示例（不发布） |

## 文档

- [使用指南](usage.md)
- [设计文档](docs/design.md)

## License

[Apache License 2.0](LICENSE)
