package com.pk.support_ticket_api.common.config;

import com.pk.support_ticket_api.auth.filter.JwtAuthenticationFilter;
import com.pk.support_ticket_api.auth.filter.RateLimitingFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 設定。
 *
 * JWT + Rate Limiting 整合：
 * - RateLimitingFilter (順序 100)：請求頻率限制
 * - JwtAuthenticationFilter (順序 200)：JWT 認證
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity  // 讓 @PreAuthorize 註解生效
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitingFilter rateLimitingFilter;

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 停用 CSRF（因為使用 JWT，無需 CSRF Token）
            .csrf(AbstractHttpConfigurer::disable)

            // 設定為無狀態（JWT stateless session）
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // 設定 Filter 順序：RateLimiting → JwtAuthentication → UsernamePasswordAuthentication
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(rateLimitingFilter, JwtAuthenticationFilter.class)

            // 設定授權規則
            .authorizeHttpRequests(auth -> auth
                // 允許匿名存取的端點
                .requestMatchers("/actuator/health").permitAll()
                // Swagger UI / OpenAPI
                .requestMatchers("/swagger-ui.html").permitAll()
                .requestMatchers("/swagger-ui/**").permitAll()
                .requestMatchers("/v3/api-docs/**").permitAll()
                .requestMatchers("/swagger-resources/**").permitAll()
                // API 端點
                .requestMatchers("/v1/auth/login").permitAll()
                .requestMatchers("/v1/notifications/**").permitAll()
                // Admin 端點需要 ADMIN 角色
                .requestMatchers("/v1/admin/**").hasRole("ADMIN")
                // 其他所有端點需要已登入
                .anyRequest().authenticated()
            );

        return http.build();
    }
}
