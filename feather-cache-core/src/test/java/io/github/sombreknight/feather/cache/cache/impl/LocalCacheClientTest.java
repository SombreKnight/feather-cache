package io.github.sombreknight.feather.cache.cache.impl;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地缓存客户端单测（纯进程内，无需 Redis）。
 */
class LocalCacheClientTest {

    @Test
    void setAndGet() {
        LocalCacheClient client = new LocalCacheClient();

        client.set("k1", "v1", Duration.ofMinutes(1));

        assertThat(client.get("k1")).isEqualTo("v1");
        assertThat(client.get("missing")).isNull();
    }

    @Test
    void mgetReturnsNullsForMissing() {
        LocalCacheClient client = new LocalCacheClient();
        client.set("k1", "v1", Duration.ofMinutes(1));

        assertThat(client.mget(List.of("k1", "k2"))).containsExactly("v1", null);
    }

    @Test
    void deleteRemoves() {
        LocalCacheClient client = new LocalCacheClient();
        client.set("k1", "v1", Duration.ofMinutes(1));

        client.delete("k1");

        assertThat(client.get("k1")).isNull();
    }

    @Test
    void expiresByDefaultTtlWhenTtlOmitted() throws InterruptedException {
        LocalCacheClient client = new LocalCacheClient(100, Duration.ofMillis(100));

        client.set("k1", "v1", null); // null → 用构造默认 ttl

        Thread.sleep(200);
        assertThat(client.get("k1")).isNull();
    }

    @Test
    void perKeyTtlIsIndependent() throws InterruptedException {
        LocalCacheClient client = new LocalCacheClient();

        client.set("short", "v1", Duration.ofMillis(100));
        client.set("long", "v2", Duration.ofMinutes(1));

        Thread.sleep(200);
        // 短 TTL 的 key 已过期，长 TTL 的 key 仍存活
        assertThat(client.get("short")).isNull();
        assertThat(client.get("long")).isEqualTo("v2");
    }

    @Test
    void overwriteRestartsTtlFromNewValue() throws InterruptedException {
        LocalCacheClient client = new LocalCacheClient();

        client.set("k1", "v1", Duration.ofMillis(100));
        Thread.sleep(150);
        // 覆盖写入（长 TTL）后重新计时
        client.set("k1", "v2", Duration.ofMinutes(1));

        assertThat(client.get("k1")).isEqualTo("v2");
        Thread.sleep(200);
        assertThat(client.get("k1")).isEqualTo("v2");
    }

    @Test
    void respectsMaxSize() {
        LocalCacheClient client = new LocalCacheClient(2, Duration.ofMinutes(1));

        client.set("k1", "v1", Duration.ofMinutes(1));
        client.set("k2", "v2", Duration.ofMinutes(1));
        client.set("k3", "v3", Duration.ofMinutes(1));

        // 最大 2 条，k1 应被淘汰
        assertThat(client.get("k3")).isEqualTo("v3");
        assertThat(client.get("k2")).isEqualTo("v2");
    }
}
