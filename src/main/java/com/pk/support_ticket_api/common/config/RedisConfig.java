package com.pk.support_ticket_api.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 設定配置類
 * 設定 StringRedisTemplate 作為預設的 Redis 操作模板
 */
@Configuration
public class RedisConfig {

    /**
     * 建立 StringRedisTemplate Bean
     * 使用 String 序列化，方便除錯與跨語言溝通
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(
            RedisConnectionFactory connectionFactory
    ) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        return template;
    }
}
