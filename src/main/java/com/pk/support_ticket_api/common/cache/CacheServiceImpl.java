package com.pk.support_ticket_api.common.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 快取服務實作
 * 採用 Cache-Aside 模式，先查快取，未命中再查 DB 並寫入快取
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CacheServiceImpl implements CacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private ValueOperations<String, String> getValueOps() {
        return redisTemplate.opsForValue();
    }

    // ==================== get() 實作 ====================

    @Override
    public <T> T get(String key, Class<T> type, Supplier<T> loader) {
        return get(key, type, null, loader);
    }

    @Override
    public <T> T get(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        try {
            String cached = getValueOps().get(key);

            if (cached != null) {
                log.debug("[CACHE] HIT: {}", key);
                return deserialize(cached, type);
            }

            log.debug("[CACHE] MISS: {}", key);
            T value = loader.get();

            if (value != null) {
                String serialized = serialize(value);
                if (ttl != null) {
                    getValueOps().set(key, serialized, ttl);
                } else {
                    getValueOps().set(key, serialized);
                }
            }

            return value;
        } catch (RedisConnectionFailureException e) {
            log.warn("[CACHE] Redis unavailable, falling back to DB: {}", e.getMessage());
            return loader.get();
        } catch (JsonProcessingException e) {
            log.warn("[CACHE] Deserialize failed for key {}, evicting: {}", key, e.getMessage());
            evict(key);
            return loader.get();
        }
    }

    @Override
    public <T> T get(String key, ParameterizedTypeReference<T> type, Supplier<T> loader) {
        return get(key, type, null, loader);
    }

    @Override
    public <T> T get(
            String key,
            ParameterizedTypeReference<T> type,
            Duration ttl,
            Supplier<T> loader
    ) {
        try {
            String cached = getValueOps().get(key);

            if (cached != null) {
                log.debug("[CACHE] HIT: {}", key);
                return deserialize(cached, type);
            }

            log.debug("[CACHE] MISS: {}", key);
            T value = loader.get();

            if (value != null) {
                String serialized = serialize(value);
                if (ttl != null) {
                    getValueOps().set(key, serialized, ttl);
                } else {
                    getValueOps().set(key, serialized);
                }
            }

            return value;
        } catch (RedisConnectionFailureException e) {
            log.warn("[CACHE] Redis unavailable, falling back to DB: {}", e.getMessage());
            return loader.get();
        } catch (JsonProcessingException e) {
            log.warn("[CACHE] Deserialize failed for key {}, evicting: {}", key, e.getMessage());
            evict(key);
            return loader.get();
        }
    }

    // ==================== evict() 實作 ====================

    @Override
    public void evict(String key) {
        try {
            redisTemplate.delete(key);
            log.debug("[CACHE] EVICT: {}", key);
        } catch (RedisConnectionFailureException e) {
            log.warn("[CACHE] Failed to evict key {}, Redis may be unavailable: {}", key, e.getMessage());
        }
    }

    @Override
    public void evictByPattern(String pattern) {
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.debug("[CACHE] EVICT PATTERN: {} ({} keys)", pattern, keys.size());
            }
        } catch (RedisConnectionFailureException e) {
            log.warn("[CACHE] Failed to evict pattern {}, Redis may be unavailable: {}", pattern, e.getMessage());
        }
    }

    // ==================== 序列化輔助方法 ====================

    private <T> String serialize(T value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }

    private <T> T deserialize(String json, Class<T> type) throws JsonProcessingException {
        return objectMapper.readValue(json, type);
    }

    private <T> T deserialize(String json, ParameterizedTypeReference<T> type)
            throws JsonProcessingException {
        return objectMapper.readValue(json, objectMapper.constructType(type.getType()));
    }
}
