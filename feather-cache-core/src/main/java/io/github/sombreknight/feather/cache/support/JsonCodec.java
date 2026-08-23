package io.github.sombreknight.feather.cache.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sombreknight.feather.cache.exception.FeatherCacheException;

import java.io.IOException;

/**
 * JSON 编解码（Jackson）。
 *
 * @author sombreknight
 * @since 0.1.0
 */
public class JsonCodec {

    private final ObjectMapper objectMapper;

    public JsonCodec() {
        this(new ObjectMapper());
    }

    public JsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new FeatherCacheException("对象序列化失败: " + value, e);
        }
    }

    public <T> T toObject(String json, TypeReference<T> typeReference) {
        try {
            return objectMapper.readValue(json, typeReference);
        } catch (IOException e) {
            throw new FeatherCacheException("JSON 反序列化失败: " + json, e);
        }
    }
}
