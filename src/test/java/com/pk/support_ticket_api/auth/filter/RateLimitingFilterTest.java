package com.pk.support_ticket_api.auth.filter;

import com.pk.support_ticket_api.auth.exception.RateLimitExceededException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.servlet.HandlerExceptionResolver;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimitingFilterTest {

    private static final String HEALTH_ENDPOINT = "/actuator/health";
    private static final String LOGIN_ENDPOINT = "/api/v1/auth/login";
    private static final String OTHER_ENDPOINT = "/api/v1/tickets";
    private static final String TEST_IP = "192.168.1.1";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private RedisScript<Long> redisScript;

    @Mock
    private HandlerExceptionResolver resolver;

    @Mock
    private FilterChain filterChain;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private RateLimitingFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitingFilter(redisTemplate, resolver);
    }

    private void mockRequest(String uri, String remoteAddr) {
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
    }

    private void mockNoForwardedHeaders() {
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("X-Real-IP")).thenReturn(null);
    }

    @SuppressWarnings("unchecked")
    private void stubRedisUnderLimit() {
        doReturn(-1L).when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(Object.class), any(Object.class));
    }

    @SuppressWarnings("unchecked")
    private void stubRedisOverLimit(long ttlSeconds) {
        doReturn(ttlSeconds).when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(Object.class), any(Object.class));
    }

    @Nested
    @DisplayName("Health endpoint bypass")
    class HealthEndpointBypass {

        @Test
        @DisplayName("health 端點不受限")
        void doFilter_healthEndpoint_bypassesCheck() throws Exception {
            when(request.getRequestURI()).thenReturn(HEALTH_ENDPOINT);

            filter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("health 端點不執行 Lua script")
        void doFilter_healthEndpoint_skipsRedisCheck() throws Exception {
            when(request.getRequestURI()).thenReturn(HEALTH_ENDPOINT);

            filter.doFilterInternal(request, response, filterChain);

            verify(redisTemplate, never())
                .execute((RedisScript<Long>) any(),anyList(),any(Object[].class));
        }
    }

    @Nested
    @DisplayName("Login endpoint rate limiting")
    class LoginEndpointRateLimiting {

        @Test
        @DisplayName("首次登入請求通過")
        void doFilter_firstLoginRequest_allowsThrough() throws Exception {
            mockRequest(LOGIN_ENDPOINT, TEST_IP);
            mockNoForwardedHeaders();
            stubRedisUnderLimit();

            filter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("限制內登入請求通過")
        void doFilter_underLoginLimit_allowsThrough() throws Exception {
            mockRequest(LOGIN_ENDPOINT, TEST_IP);
            mockNoForwardedHeaders();
            stubRedisUnderLimit();

            filter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("逾限登入請求回傳 429")
        void doFilter_exceedLoginLimit_returns429() throws Exception {
            mockRequest(LOGIN_ENDPOINT, TEST_IP);
            mockNoForwardedHeaders();
            stubRedisOverLimit(300L);

            filter.doFilterInternal(request, response, filterChain);

            verify(resolver).resolveException(eq(request), eq(response), isNull(),
                    argThat(ex -> ex instanceof RateLimitExceededException));
            verify(filterChain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("逾限回傳正確的 retryAfter 秒數")
        void doFilter_exceedLimit_correctRetryAfter() throws Exception {
            mockRequest(LOGIN_ENDPOINT, TEST_IP);
            mockNoForwardedHeaders();
            stubRedisOverLimit(450L);

            ArgumentCaptor<RateLimitExceededException> captor =
                    ArgumentCaptor.forClass(RateLimitExceededException.class);

            filter.doFilterInternal(request, response, filterChain);

            verify(resolver).resolveException(eq(request), eq(response), isNull(), captor.capture());
            assertEquals(450L, captor.getValue().getRetryAfter());
        }
    }

    @Nested
    @DisplayName("General endpoint rate limiting")
    class GeneralEndpointRateLimiting {

        @Test
        @DisplayName("首次一般請求通過")
        void doFilter_firstGeneralRequest_allowsThrough() throws Exception {
            mockRequest(OTHER_ENDPOINT, TEST_IP);
            mockNoForwardedHeaders();
            stubRedisUnderLimit();

            filter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("限制內一般請求通過")
        void doFilter_underGeneralLimit_allowsThrough() throws Exception {
            mockRequest(OTHER_ENDPOINT, TEST_IP);
            mockNoForwardedHeaders();
            stubRedisUnderLimit();

            filter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("逾限一般請求回傳 429")
        void doFilter_exceedGeneralLimit_returns429() throws Exception {
            mockRequest(OTHER_ENDPOINT, TEST_IP);
            mockNoForwardedHeaders();
            stubRedisOverLimit(30L);

            filter.doFilterInternal(request, response, filterChain);

            verify(resolver).resolveException(eq(request), eq(response), isNull(),
                    any(RateLimitExceededException.class));
            verify(filterChain, never()).doFilter(request, response);
        }
    }

    @Nested
    @DisplayName("Client IP extraction")
    class ClientIpExtraction {

        @Test
        @DisplayName("使用 X-Forwarded-For 第一個 IP")
        void doFilter_xForwardedFor_usesFirstIp() throws Exception {
            mockRequest(LOGIN_ENDPOINT, TEST_IP);
            when(request.getHeader("X-Forwarded-For")).thenReturn("10.0.0.1, 10.0.0.2, 10.0.0.3");
            when(request.getHeader("X-Real-IP")).thenReturn(null);
            stubRedisUnderLimit();

            filter.doFilterInternal(request, response, filterChain);

            verify(redisTemplate).execute(any(RedisScript.class), argThat(list ->
                    list.get(0).contains("10.0.0.1")), any(Object.class), any(Object.class));
        }

        @Test
        @DisplayName("使用 X-Real-IP")
        void doFilter_xRealIp_usesRealIp() throws Exception {
            mockRequest(LOGIN_ENDPOINT, TEST_IP);
            when(request.getHeader("X-Forwarded-For")).thenReturn(null);
            when(request.getHeader("X-Real-IP")).thenReturn("172.16.0.1");
            stubRedisUnderLimit();

            filter.doFilterInternal(request, response, filterChain);

            verify(redisTemplate).execute(any(RedisScript.class), argThat(list ->
                    list.get(0).contains("172.16.0.1")), any(Object.class), any(Object.class));
        }

        @Test
        @DisplayName("使用 request.getRemoteAddr()")
        void doFilter_noHeaders_usesRemoteAddr() throws Exception {
            mockRequest(LOGIN_ENDPOINT, TEST_IP);
            mockNoForwardedHeaders();
            stubRedisUnderLimit();

            filter.doFilterInternal(request, response, filterChain);

            verify(redisTemplate).execute(any(RedisScript.class), argThat(list ->
                    list.get(0).contains(TEST_IP)), any(Object.class), any(Object.class));
        }
    }

    @Nested
    @DisplayName("Redis failure handling")
    class RedisFailureHandling {

        @Test
        @DisplayName("Redis 失敗時允許請求通過（Fail-Open）")
        void doFilter_redisFailure_allowsThrough() throws Exception {
            mockRequest(LOGIN_ENDPOINT, TEST_IP);
            mockNoForwardedHeaders();
            doThrow(new RuntimeException("Redis connection failed")).when(redisTemplate)
                    .execute(any(RedisScript.class), anyList(), any(Object.class), any(Object.class));

            filter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("Redis 失敗時不回傳 429")
        void doFilter_redisFailure_doesNotThrow429() throws Exception {
            mockRequest(LOGIN_ENDPOINT, TEST_IP);
            mockNoForwardedHeaders();
            doThrow(new RuntimeException("Redis connection failed")).when(redisTemplate)
                    .execute(any(RedisScript.class), anyList(), any(Object.class), any(Object.class));

            filter.doFilterInternal(request, response, filterChain);

            verify(resolver, never()).resolveException(any(), any(), any(),
                    any(RateLimitExceededException.class));
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Lua script 返回 0 時使用預設 windowSeconds")
        void doFilter_luaReturnsZero_usesDefaultWindow() throws Exception {
            mockRequest(LOGIN_ENDPOINT, TEST_IP);
            mockNoForwardedHeaders();
            doReturn(0L).when(redisTemplate)
                    .execute(any(RedisScript.class), anyList(), any(Object.class), any(Object.class));

            ArgumentCaptor<RateLimitExceededException> captor =
                    ArgumentCaptor.forClass(RateLimitExceededException.class);

            filter.doFilterInternal(request, response, filterChain);

            verify(resolver).resolveException(eq(request), eq(response), isNull(), captor.capture());
            assertEquals(900L, captor.getValue().getRetryAfter());
        }

        @Test
        @DisplayName("Lua script 返回 null 時允許通過")
        void doFilter_luaReturnsNull_allowsThrough() throws Exception {
            mockRequest(LOGIN_ENDPOINT, TEST_IP);
            mockNoForwardedHeaders();
            doReturn(null).when(redisTemplate)
                    .execute(any(RedisScript.class), anyList(), any(Object.class), any(Object.class));

            filter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("Lua script 返回負數（非 -1）時允許通過")
        void doFilter_luaReturnsNegativeAllowsThrough() throws Exception {
            mockRequest(LOGIN_ENDPOINT, TEST_IP);
            mockNoForwardedHeaders();
            doReturn(-5L).when(redisTemplate)
                    .execute(any(RedisScript.class), anyList(), any(Object.class), any(Object.class));

            filter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }
    }
}
