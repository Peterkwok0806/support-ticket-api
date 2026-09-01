package com.pk.support_ticket_api.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class TokenBlacklistServiceImpl implements TokenBlacklistService {

    private static final String KEY_PREFIX = "jwt:blk:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public void addToBlacklist(String jti, long ttlSeconds) {
        String key = KEY_PREFIX + jti;
        redisTemplate.opsForValue().set(key, "1", Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public boolean isBlacklisted(String jti) {
        String key = KEY_PREFIX + jti;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }
}
