package com.pk.support_ticket_api.auth.filter;

import com.pk.support_ticket_api.auth.exception.AuthException;
import com.pk.support_ticket_api.auth.exception.TokenRevokedException;
import com.pk.support_ticket_api.auth.service.JwtService;
import com.pk.support_ticket_api.auth.service.TokenBlacklistService;
import com.pk.support_ticket_api.common.security.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;
    private final HandlerExceptionResolver resolver;

    // 透過建構子注入，並使用 @Qualifier 指定 Spring Boot 預設的全域錯誤解析器
    public JwtAuthenticationFilter(
            JwtService jwtService,
            TokenBlacklistService tokenBlacklistService,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver
    ) {
        this.jwtService = jwtService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // 1. 擷取 HTTP Header 中的 Authorization 欄位
        String authHeader = request.getHeader(AUTHORIZATION_HEADER);

        // 2. 檢查是否沒帶 Token，或是格式不符合 Bearer 規範
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response); // 直接放行，交給後續的 Spring Security 權限控制
            return;
        }

        // 3. 裁切字串，拿到真正的純 JWT Token
        String token = authHeader.substring(BEARER_PREFIX.length());

        try {
            // 4.
            // 這裡會取得你的 CurrentUser(userId, email, role)，驗證失敗則會拋出對應例外
            CurrentUser currentUser = jwtService.parseToken(token);

            // 5.
            // 我們改由 jwtService 另外提供的方法，直接從 token 中提煉出識別碼 (jti)
            String jti = jwtService.extractJti(token);

            // 6. 秒查 Redis，檢查這個 jti 是否已被記錄在黑名單內（防範登出用戶）
            if (tokenBlacklistService.isBlacklisted(jti)) {
                throw new TokenRevokedException(); // 在黑名單內，大膽拋出 Token 已撤銷例外
            }

            // 7. 通過所有安檢，幫該次請求核發 Spring Security 的「臨時通行證」
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            currentUser,
                            null, // 密碼/憑證在有無狀態 JWT 中設為 null
                            List.of(new SimpleGrantedAuthority("ROLE_" + currentUser.role().name())) // 讀取你的 Record 欄位
                    );
            
            // 注入當前請求的詳細資訊（例如客戶端 IP、Session ID）
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            // 將臨時通行證塞進 Spring 安全上下文中，正式承認其登入身分
            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (AuthException e) {
            // 如果是我們自定義的認證錯誤，手動交給全域錯誤處理器（ApiExceptionHandler）
            log.info("JWT Auth failed, redirecting to ApiExceptionHandler: {}", e.getMessage());
            resolver.resolveException(request, response, null, e);
            return; // 攔截請求，不再往下走
            
        } catch (Exception e) {
            // 捕捉未知的系統錯誤，避免破壞 Filter 鏈
            log.error("Unexpected JWT authentication error", e);
            resolver.resolveException(request, response, null, e);
            return;
        }

        // 8. 順利過關，放行前往下一個 Filter 或 Controller
        filterChain.doFilter(request, response);
    }
}
