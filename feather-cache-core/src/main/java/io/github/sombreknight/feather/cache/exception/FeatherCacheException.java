package io.github.sombreknight.feather.cache.exception;

/**
 * Feather Cache 统一异常基类。
 *
 * <p>框架内所有受检/非受检异常的根类型，业务方可按此类型统一捕获与降级。</p>
 *
 * @author sombreknight
 * @since 0.1.0
 */
public class FeatherCacheException extends RuntimeException {

    public FeatherCacheException(String message) {
        super(message);
    }

    public FeatherCacheException(String message, Throwable cause) {
        super(message, cause);
    }

    public FeatherCacheException(Throwable cause) {
        super(cause);
    }
}
