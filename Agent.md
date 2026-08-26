# Feather Cache — Agent 开发手册

> 本文件是给 Agent（及后续维护者）的**长期迭代参考**：项目结构、设计约定、构建/测试命令、踩坑记录。
> 用户视角文档见 [README.md](README.md)；设计决策见 [docs/design.md](docs/design.md)。

## 1. 项目速览

| 项 | 值 |
|---|---|
| 定位 | 基于 Lettuce 自建连接的轻量级多级缓存 + 分布式锁框架（从 common-sdk 抽出，去技术债重建） |
| 仓库 | https://github.com/SombreKnight/feather-cache（默认分支 `main`） |
| 包名 | `io.github.sombreknight.feather.cache` |
| 坐标 | `io.github.sombreknight:feather-cache-spring-boot-starter` |
| 版本 | 1.0.0 |
| License | Apache 2.0 |
| 技术栈 | Java 17、Spring Boot 3.5.x BOM、Lettuce（自建连接）、Caffeine、Jackson |
| 设计哲学 | 干净、opinionated、极简配置；连接自闭环（`feather.cache.redis.*`，不依赖 Spring Data Redis）；锁走 Lua + 看门狗 |

## 2. 本地开发环境

- **默认 JDK 17**：`/Users/zhangchenxi/Library/Java/JavaVirtualMachines/jdk-17.0.20.jdk`（Zulu 17）；
  构建前 `export JAVA_HOME=/Users/zhangchenxi/Library/Java/JavaVirtualMachines/jdk-17.0.20.jdk/Contents/Home`
  （系统默认 java 是 1.8，mvn 直接跑会编译失败）
- 本地 Redis 用 docker 起：`docker run -d --name redis -p 6379:6379 redis:7`

## 3. 模块与架构速览

```
feather-cache
├── feather-cache-core                   # 核心（lettuce-core + caffeine + jackson + slf4j，零 boot）
│   └── io/github/sombreknight/feather/cache
│       ├── redis/       FeatherRedisConnectionFactory（lettuce 连接工厂，standalone/sentinel/cluster，懒连接）+ FeatherRedisClient（薄封装 lettuce 同步命令）+ RedisConnectionConfig
│       ├── cache/       FeatherCache（核心服务）/ CacheConfig / CacheLoader / MultiCacheLoader / CacheType / CacheReadMode / CacheClient
│       ├── lock/        FeatherLock（AutoCloseable）/ RedisFeatherLock / DistributedLockService / LockScripts（Lua）
│       ├── support/     NamingStrategy（key 命名单一事实源）/ JsonCodec
│       └── exception/   FeatherCacheException
├── feather-cache-spring-boot-starter    # FeatherCacheAutoConfiguration + FeatherCacheProperties
│   └── META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
└── feather-cache-samples                # 可运行示例（连本地 redis，端口 9090，不发布）
```

**关键类职责**：
- `FeatherRedisConnectionFactory`：lettuce 连接工厂（standalone/sentinel/cluster，懒连接，close 统一释放）
- `FeatherRedisClient`：String/Hash 薄封装；mget 单机走原生 MGET、集群逐 key（多路复用流水线，无 CROSSSLOT）；Lua eval 整数返回（M1）
- `FeatherCache`：多级缓存（LOCAL_ONLY / REDIS_ONLY / LOCAL_FIRST_THEN_REDIS）+ 防击穿（信号量+双重检查）+ 防穿透（空值 sentinel）（M2）
- `RedisFeatherLock`：Lua 原子加锁/释放 + 看门狗续期 + 重入计数，`FeatherLock implements AutoCloseable`（M3）
- `DistributedLockService`：execute 模板方法（Runnable/Callback）
- `NamingStrategy`：key 命名单一事实源

## 4. 核心设计约定（改代码必须遵守）

1. **连接自闭环**：FeatherRedisConnectionFactory 基于 lettuce 自建（`feather.cache.redis.*` 配置），
   **懒连接**（构造不连，首次命令才连；失败不缓存，Redis 恢复自动重连——保证 Redis 不可达时应用可启动并按 CacheReadMode 降级）；连接生命周期由工厂统一关闭
2. **key 命名**：`feather:{app}:{scope}:{key}`，scope 为 `cache` / `lock` / `sentinel`（见 NamingStrategy，单一事实源）
3. **异常策略显式化**：`CacheReadMode.FAIL_FAST / RETURN_NULL / FALLBACK_LOCAL`，默认 FAIL_FAST；
   禁止 common-sdk 那种"无差别吞异常返回 null"——Redis 故障必须可感知
4. **锁 API**：`FeatherLock implements AutoCloseable`，try-with-resources 使用；unLock 用 Lua
   compare-and-delete；默认看门狗续期（可关）；不引入 ThreadLocal
5. **core 零 boot 依赖**；starter 只做装配；samples 不发布
6. **v0.1 明确不做**：注解式缓存（@Cacheable 风格）、Spring Cache CacheManager 适配、
   布隆过滤器、多级缓存一致性广播（pub/sub 失效 v0.2）、限流

## 5. 构建与测试

```bash
export JAVA_HOME=/Users/zhangchenxi/Library/Java/JavaVirtualMachines/jdk-17.0.20.jdk/Contents/Home
mvn clean install          # 全量编译 + 单测
mvn test -pl feather-cache-core
mvn spring-boot:run -pl feather-cache-samples   # 需要本地 redis
```

## 6. 踩坑记录

（随迭代补充，见 docs/design.md）

## 7. 发布（Maven Central 已上线）

对齐 feather-orm / feather-rmq 的 Maven Central 发布流程（Central Portal +
central-publishing-maven-plugin + GPG 无口令密钥 + GitHub Actions，tag `v*` 触发 release.yml）：
commit → bump 版本（4 pom + README + usage + Agent.md 共 7 处）→ 全量测试（REDIS_TEST_URL 指向本地/CI redis）→
tag vX.Y.Z → push → 验证 repo1 HTTP 200 + CI/Release workflow success。

## 8. 待办

- [x] M1：FeatherRedisClient + pipeline mget + 异常策略（commit 见 git log）
- [x] M2：FeatherCache 三模式 + 防击穿/穿透 + 批量回源
- [x] M3：Lua 锁 + 看门狗 + AutoCloseable + execute 模板
- [x] M4：starter 装配完整化 + FeatherCacheProperties 全属性化
- [x] M5：samples + usage.md + docs/design.md
- [x] M6：发布（0.1.x 已上线；1.0.0 连接自闭环 + 配置归一化）
