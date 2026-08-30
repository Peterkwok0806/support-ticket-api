package com.pk.support_ticket_api.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 設定。
 *
 * 目前為預留設定，待 JWT 實作後需整合：
 * - JwtAuthenticationFilter
 * - 移除 .permitAll()（改為需要認證）
 * - 設定 JWT 相關例外處理
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity  // 讓 @PreAuthorize 註解生效
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 停用 CSRF（因為使用 JWT，無需 CSRF Token）
            .csrf(AbstractHttpConfigurer::disable)

            // 設定為無狀態（JWT  stateless session）
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // 設定授權規則
            .authorizeHttpRequests(auth -> auth
                //  actuator 健康檢查端點允許所有人存取
                .requestMatchers("/actuator/health").permitAll()
                // TODO: JWT 實作後，替换為 .anyRequest().authenticated()
                .anyRequest().permitAll()  // 暫時全部放行，JWT 實作後關閉
            );

        return http.build();
    }
}
