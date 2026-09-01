package com.pk.support_ticket_api.auth.filter;

import com.pk.support_ticket_api.auth.exception.RateLimitExceededException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.Collections;

@Slf4j
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final String KEY_PREFIX = "ratelimit:";
    private static final String HEALTH_ENDPOINT = "/actuator/health";
    private static final String LOGIN_ENDPOINT = "/api/v1/auth/login";

    private static final long LOGIN_WINDOW_SECONDS = 15 * 60;  // 15 minutes
    private static final int LOGIN_MAX_REQUESTS = 5;

    private static final long GENERAL_WINDOW_SECONDS = 60;     // 1 minute
    private static final int GENERAL_MAX_REQUESTS = 100;

    // 🌟 升級 Lua 腳本：當流量超限時，直接向 Redis 讀取並返回該 Key 剩餘的動態生存秒數 (TTL)。若未超限則返回 -1。
    private static final String RATE_LIMIT_SCRIPT = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            if current > tonumber(ARGV[2]) then
                return redis.call('TTL', KEYS[1])
            end
            return -1
            """;

    private final StringRedisTemplate redisTemplate;
    private final HandlerExceptionResolver resolver; 

    // 移除 Lombok 註解，改為手動建構子注入，並指定全域錯誤解析器
    public RateLimitingFilter(
            StringRedisTemplate redisTemplate,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver
    ) {
        this.redisTemplate = redisTemplate;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String path = request.getRequestURI();

        // Bypass health check endpoint
        if (HEALTH_ENDPOINT.equals(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Determine rate limit rule
        RateLimitRule rule = getMatchingRule(path);
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = getClientIp(request);
        String key = KEY_PREFIX + path + ":" + clientIp;

        try {
            // 🌟 執行升級版 Lua 腳本（改用 Long.class 接收秒數結果）
            Long result = redisTemplate.execute(
                    new DefaultRedisScript<>(RATE_LIMIT_SCRIPT, Long.class),
                    Collections.singletonList(key),
                    String.valueOf(rule.windowSeconds()),
                    String.valueOf(rule.maxRequests())
            );

            // 🌟 只要 result >= 0，代表 Redis 抓到超限壞蛋了，而 result 的值就是精準的剩餘倒數秒數
            if (result != null && result >= 0) {
                // 防呆：若 Redis 剛好在極端臨界點返回 0 或是負數 TTL，則預設給予視窗壽命
                long retryAfter = result <= 0 ? rule.windowSeconds() : result;
                
                //  拋出你精心設計的自定義例外，把動態秒數塞進去
                throw new RateLimitExceededException(retryAfter);
            }
            
        } catch (RateLimitExceededException e) {
            // 將 Filter 攔截到的例外，透過總機轉寄給你的 ApiExceptionHandler 處理！
            log.info("Rate limit exceeded for IP: {} on path: {}, redirecting to ApiExceptionHandler", clientIp, path);
            resolver.resolveException(request, response, null, e);
            return; // 立即中斷 Filter 鏈，不往下走
            
        } catch (Exception e) {
            // If Redis fails, allow the request (fail-open for availability)
            log.warn("Rate limiting check failed (Fail-Open), allowing request: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private RateLimitRule getMatchingRule(String path) {
        if (path.startsWith(LOGIN_ENDPOINT)) {
            return new RateLimitRule(LOGIN_MAX_REQUESTS, LOGIN_WINDOW_SECONDS);
        }
        // Default rule for all other endpoints
        return new RateLimitRule(GENERAL_MAX_REQUESTS, GENERAL_WINDOW_SECONDS);
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }
        return request.getRemoteAddr();
    }

    private record RateLimitRule(int maxRequests, long windowSeconds) {}
}
