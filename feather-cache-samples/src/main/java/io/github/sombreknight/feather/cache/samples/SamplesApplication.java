package io.github.sombreknight.feather.cache.samples;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Feather Cache 示例应用入口。
 *
 * <p>本地运行前启动 Redis：{@code docker run -d --name redis -p 6379:6379 redis:7}。</p>
 *
 * @author sombreknight
 * @since 0.1.0
 */
@SpringBootApplication
public class SamplesApplication {

    public static void main(String[] args) {
        SpringApplication.run(SamplesApplication.class, args);
    }
}
