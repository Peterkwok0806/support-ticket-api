package com.pk.support_ticket_api.common.cache;

import org.springframework.core.ParameterizedTypeReference;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 快取服務介面
 * 定義快取操作的標準方法
 */
public interface CacheService {

    /**
     * 取得快取值，不存在時透過 loader 載入並快取
     *
     * @param key   快取 Key
     * @param type  目標類型
     * @param loader 資料載入器（當快取未命中時呼叫）
     * @param <T>   回傳類型
     * @return 快取值或載入的資料
     */
    <T> T get(String key, Class<T> type, Supplier<T> loader);

    /**
     * 取得快取值（含自訂 TTL），不存在時透過 loader 載入並快取
     *
     * @param key   快取 Key
     * @param type  目標類型
     * @param ttl   快取存活時間
     * @param loader 資料載入器
     * @param <T>   回傳類型
     * @return 快取值或載入的資料
     */
    <T> T get(String key, Class<T> type, Duration ttl, Supplier<T> loader);

    /**
     * 取得快取值（使用 ParameterizedTypeReference）
     */
    <T> T get(
            String key,
            ParameterizedTypeReference<T> type,
            Supplier<T> loader
    );

    /**
     * 取得快取值（含自訂 TTL，使用 ParameterizedTypeReference）
     */
    <T> T get(
            String key,
            ParameterizedTypeReference<T> type,
            Duration ttl,
            Supplier<T> loader
    );

    /**
     * 刪除單一快取 key
     *
     * @param key 快取 Key
     */
    void evict(String key);

    /**
     * 刪除符合 pattern 的所有 key
     *
     * @param pattern Redis key pattern（如 "ticket:*"）
     */
    void evictByPattern(String pattern);
}
