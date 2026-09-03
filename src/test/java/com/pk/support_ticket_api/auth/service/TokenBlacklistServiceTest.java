package com.pk.support_ticket_api.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenBlacklistServiceTest {

    private static final String KEY_PREFIX = "jwt:blk:";
    private static final String BLACKLIST_VALUE = "1";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private TokenBlacklistServiceImpl tokenBlacklistService;

    private TokenBlacklistServiceImpl createService() {
        return new TokenBlacklistServiceImpl(redisTemplate);
    }

    @Nested
    @DisplayName("addToBlacklist")
    class AddToBlacklist {

        @BeforeEach
        void setUp() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        }

        @Test
        @DisplayName("加入黑名單設定正確")
        void addToBlacklist_setsKeyWithTTL() {
            String jti = UUID.randomUUID().toString();
            long ttlSeconds = 3600L;
            tokenBlacklistService = createService();

            tokenBlacklistService.addToBlacklist(jti, ttlSeconds);

            String expectedKey = KEY_PREFIX + jti;
            verify(valueOperations).set(expectedKey, BLACKLIST_VALUE, Duration.ofSeconds(ttlSeconds));
        }

        @Test
        @DisplayName("使用正確的 Key 前綴")
        void addToBlacklist_usesCorrectKeyPrefix() {
            String jti = UUID.randomUUID().toString();
            long ttlSeconds = 1800L;
            tokenBlacklistService = createService();

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);

            tokenBlacklistService.addToBlacklist(jti, ttlSeconds);

            verify(valueOperations).set(keyCaptor.capture(), eq(BLACKLIST_VALUE), any(Duration.class));
            String capturedKey = keyCaptor.getValue();
            assertTrue(capturedKey.startsWith(KEY_PREFIX), "Key 應以 " + KEY_PREFIX + " 開頭");
            assertTrue(capturedKey.endsWith(jti), "Key 應以 jti 結尾");
        }

        @Test
        @DisplayName("設定正確的 TTL")
        void addToBlacklist_setsCorrectTTL() {
            String jti = UUID.randomUUID().toString();
            long ttlSeconds = 7200L;
            tokenBlacklistService = createService();

            ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);

            tokenBlacklistService.addToBlacklist(jti, ttlSeconds);

            verify(valueOperations).set(anyString(), eq(BLACKLIST_VALUE), durationCaptor.capture());
            assertEquals(Duration.ofSeconds(ttlSeconds), durationCaptor.getValue());
        }

        @Test
        @DisplayName("設定正確的黑名單值")
        void addToBlacklist_setsCorrectValue() {
            String jti = UUID.randomUUID().toString();
            long ttlSeconds = 600L;
            tokenBlacklistService = createService();

            tokenBlacklistService.addToBlacklist(jti, ttlSeconds);

            verify(valueOperations).set(anyString(), eq(BLACKLIST_VALUE), any(Duration.class));
        }
    }

    @Nested
    @DisplayName("isBlacklisted")
    class IsBlacklisted {

        @BeforeEach
        void setUp() {
            tokenBlacklistService = createService();
        }

        @Test
        @DisplayName("新 Token 不在黑名單")
        void isBlacklisted_newToken_returnsFalse() {
            String jti = UUID.randomUUID().toString();

            when(redisTemplate.hasKey(KEY_PREFIX + jti)).thenReturn(false);

            boolean result = tokenBlacklistService.isBlacklisted(jti);

            assertFalse(result);
            verify(redisTemplate).hasKey(KEY_PREFIX + jti);
        }

        @Test
        @DisplayName("已撤銷 Token 在黑名單")
        void isBlacklisted_revokedToken_returnsTrue() {
            String jti = UUID.randomUUID().toString();

            when(redisTemplate.hasKey(KEY_PREFIX + jti)).thenReturn(true);

            boolean result = tokenBlacklistService.isBlacklisted(jti);

            assertTrue(result);
            verify(redisTemplate).hasKey(KEY_PREFIX + jti);
        }

        @Test
        @DisplayName("使用正確的 Key 前綴查詢")
        void isBlacklisted_usesCorrectKeyPrefix() {
            String jti = UUID.randomUUID().toString();

            tokenBlacklistService.isBlacklisted(jti);

            verify(redisTemplate).hasKey(KEY_PREFIX + jti);
        }

        @Test
        @DisplayName("TTL 過期後不在黑名單")
        void isBlacklisted_expiredBlacklist_returnsFalse() {
            String jti = UUID.randomUUID().toString();

            when(redisTemplate.hasKey(KEY_PREFIX + jti)).thenReturn(false);

            boolean result = tokenBlacklistService.isBlacklisted(jti);

            assertFalse(result);
        }

        @Test
        @DisplayName("多個不同 JTI 獨立查詢")
        void isBlacklisted_multipleJtis_queriesIndependently() {
            String jti1 = UUID.randomUUID().toString();
            String jti2 = UUID.randomUUID().toString();

            when(redisTemplate.hasKey(KEY_PREFIX + jti1)).thenReturn(true);
            when(redisTemplate.hasKey(KEY_PREFIX + jti2)).thenReturn(false);

            boolean result1 = tokenBlacklistService.isBlacklisted(jti1);
            boolean result2 = tokenBlacklistService.isBlacklisted(jti2);

            assertTrue(result1);
            assertFalse(result2);
            verify(redisTemplate).hasKey(KEY_PREFIX + jti1);
            verify(redisTemplate).hasKey(KEY_PREFIX + jti2);
        }
    }
}
