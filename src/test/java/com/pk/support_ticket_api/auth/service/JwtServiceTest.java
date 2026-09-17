package com.pk.support_ticket_api.auth.service;

import com.pk.support_ticket_api.auth.exception.InvalidTokenException;
import com.pk.support_ticket_api.auth.exception.TokenExpiredException;
import com.pk.support_ticket_api.common.security.CurrentUser;
import com.pk.support_ticket_api.common.domain.enums.Role;
import com.pk.support_ticket_api.users.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private static final String VALID_SECRET = "this-is-a-valid-secret-key-for-testing-jwt-must-be-at-least-256-bits";
    private static final String DIFFERENT_SECRET = "different-secret-key-for-testing-invalid-signature-at-least-256-bits-long";
    private static final Duration DEFAULT_EXPIRATION = Duration.ofHours(2);

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = createUser(
                UUID.fromString("12345678-1234-1234-1234-123456789abc"),
                "test@example.com",
                "Test User",
                Role.AGENT
        );
    }

    private User createUser(UUID id, String email, String displayName, Role role) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setEmail(email);
        user.setPasswordHash("hashedPassword");
        user.setDisplayName(displayName);
        user.setRole(role);
        return user;
    }

    private JwtServiceImpl createJwtService(String secret, Duration expiration) {
        JwtServiceImpl service = new JwtServiceImpl(secret, expiration);
        return service;
    }

    @Nested
    @DisplayName("generateToken")
    class GenerateToken {

        @Test
        @DisplayName("產生有效 Token")
        void generateToken_createsValidToken() {
            JwtServiceImpl service = createJwtService(VALID_SECRET, DEFAULT_EXPIRATION);

            String token = service.generateToken(testUser);

            assertNotNull(token);
            assertFalse(token.isBlank());
            assertTrue(token.split("\\.").length == 3, "JWT 應包含 3 個部分（header.payload.signature）");

            CurrentUser parsed = service.parseToken(token);
            assertEquals(testUser.getId(), parsed.userId());
            assertEquals(testUser.getEmail(), parsed.email());
            assertEquals(testUser.getRole(), parsed.role());
        }

        @Test
        @DisplayName("每次產生不同的 jti")
        void generateToken_eachCallHasDifferentJti() {
            JwtServiceImpl service = createJwtService(VALID_SECRET, DEFAULT_EXPIRATION);

            String token1 = service.generateToken(testUser);
            String token2 = service.generateToken(testUser);

            String jti1 = service.extractJti(token1);
            String jti2 = service.extractJti(token2);

            assertNotEquals(jti1, jti2, "每次產生的 Token 應有不同的 jti");
        }
    }

    @Nested
    @DisplayName("validateToken")
    class ValidateToken {

        @Test
        @DisplayName("有效 Token 驗證成功")
        void validateToken_validToken_returnsTrue() {
            JwtServiceImpl service = createJwtService(VALID_SECRET, DEFAULT_EXPIRATION);
            String token = service.generateToken(testUser);

            boolean result = service.validateToken(token);

            assertTrue(result);
        }

        @Test
        @DisplayName("過期 Token 驗證失敗")
        void validateToken_expiredToken_returnsFalse() {
            Duration expiredDuration = Duration.ofMillis(1);
            JwtServiceImpl service = createJwtService(VALID_SECRET, expiredDuration);
            String token = service.generateToken(testUser);

            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            assertThrows(TokenExpiredException.class, () -> service.validateToken(token));
        }

        @Test
        @DisplayName("錯誤簽章驗證失敗")
        void validateToken_invalidSignature_returnsFalse() {
            JwtServiceImpl validService = createJwtService(VALID_SECRET, DEFAULT_EXPIRATION);
            JwtServiceImpl invalidService = createJwtService(DIFFERENT_SECRET, DEFAULT_EXPIRATION);

            String token = validService.generateToken(testUser);

            assertThrows(InvalidTokenException.class, () -> invalidService.validateToken(token));
        }

        @Test
        @DisplayName("格式錯誤的 Token 驗證失敗")
        void validateToken_malformedToken_returnsFalse() {
            JwtServiceImpl service = createJwtService(VALID_SECRET, DEFAULT_EXPIRATION);

            assertThrows(InvalidTokenException.class, () -> service.validateToken("invalid.token.format"));
        }

        @Test
        @DisplayName("空字串驗證失敗")
        void validateToken_emptyToken_returnsFalse() {
            JwtServiceImpl service = createJwtService(VALID_SECRET, DEFAULT_EXPIRATION);

            assertThrows(InvalidTokenException.class, () -> service.validateToken(""));
        }
    }

    @Nested
    @DisplayName("parseToken")
    class ParseToken {

        @Test
        @DisplayName("解析 Claims 正確")
        void parseToken_extractsClaimsCorrectly() {
            JwtServiceImpl service = createJwtService(VALID_SECRET, DEFAULT_EXPIRATION);
            String token = service.generateToken(testUser);

            CurrentUser currentUser = service.parseToken(token);

            assertEquals(testUser.getId(), currentUser.userId());
            assertEquals(testUser.getEmail(), currentUser.email());
            assertEquals(testUser.getRole(), currentUser.role());
        }

        @Test
        @DisplayName("不同角色正確解析")
        void parseToken_differentRoles_correctlyParsed() {
            JwtServiceImpl service = createJwtService(VALID_SECRET, DEFAULT_EXPIRATION);

            for (Role role : Role.values()) {
                User user = createUser(UUID.randomUUID(), role.name().toLowerCase() + "@test.com", "Test", role);
                String token = service.generateToken(user);

                CurrentUser currentUser = service.parseToken(token);
                assertEquals(role, currentUser.role(), "Role " + role + " 解析失敗");
            }
        }
    }

    @Nested
    @DisplayName("extractJti")
    class ExtractJti {

        @Test
        @DisplayName("提取 JTI 正確")
        void extractJti_returnsCorrectJti() {
            JwtServiceImpl service = createJwtService(VALID_SECRET, DEFAULT_EXPIRATION);
            String token = service.generateToken(testUser);

            String jti = service.extractJti(token);

            assertNotNull(jti);
            assertFalse(jti.isBlank());
            assertDoesNotThrow(() -> UUID.fromString(jti), "JTI 應為有效的 UUID 格式");
        }

        @Test
        @DisplayName("每個 Token 有唯一 JTI")
        void extractJti_eachTokenUnique() {
            JwtServiceImpl service = createJwtService(VALID_SECRET, DEFAULT_EXPIRATION);

            String token1 = service.generateToken(testUser);
            String token2 = service.generateToken(testUser);

            String jti1 = service.extractJti(token1);
            String jti2 = service.extractJti(token2);

            assertNotEquals(jti1, jti2);
        }
    }

    @Nested
    @DisplayName("extractExpiration")
    class ExtractExpiration {

        @Test
        @DisplayName("計算剩餘時間正確")
        void extractExpiration_calculatesRemainingSeconds() {
            Duration expiration = Duration.ofHours(2);
            JwtServiceImpl service = createJwtService(VALID_SECRET, expiration);
            String token = service.generateToken(testUser);

            long remainingSeconds = service.extractExpiration(token);

            assertTrue(remainingSeconds > 7100, "2小時應有約 7200 秒，實際: " + remainingSeconds);
            assertTrue(remainingSeconds <= 7200, "剩餘時間不應超過設定值");
        }

        @Test
        @DisplayName("短效 Token 剩餘時間計算正確")
        void extractExpiration_shortLivedToken_correctCalculation() {
            Duration expiration = Duration.ofMinutes(15);
            JwtServiceImpl service = createJwtService(VALID_SECRET, expiration);
            String token = service.generateToken(testUser);

            long remainingSeconds = service.extractExpiration(token);

            assertTrue(remainingSeconds > 890, "15分鐘應有約 900 秒，實際: " + remainingSeconds);
            assertTrue(remainingSeconds <= 900, "剩餘時間不應超過設定值");
        }

        @Test
        @DisplayName("過期 Token 回傳 0")
        void extractExpiration_expiredToken_returnsZero() {
            Duration expiredDuration = Duration.ofMillis(1);
            JwtServiceImpl service = createJwtService(VALID_SECRET, expiredDuration);
            String token = service.generateToken(testUser);

            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            long remainingSeconds = service.extractExpiration(token);
            assertEquals(0, remainingSeconds, "過期 Token 應回傳 0");
        }
    }
}
