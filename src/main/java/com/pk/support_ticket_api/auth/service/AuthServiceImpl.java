package com.pk.support_ticket_api.auth.service;

import com.pk.support_ticket_api.auth.dto.LoginRequest;
import com.pk.support_ticket_api.auth.dto.LoginResponse;
import com.pk.support_ticket_api.auth.dto.LogoutResponse;
import com.pk.support_ticket_api.auth.exception.AccountDisabledException;
import com.pk.support_ticket_api.auth.exception.InvalidCredentialsException;
import com.pk.support_ticket_api.users.domain.User;
import com.pk.support_ticket_api.users.domain.UserStatus;
import com.pk.support_ticket_api.users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;


@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String TOKEN_TYPE = "Bearer";

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;
    private final PasswordEncoder passwordEncoder;


    @Override
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(InvalidCredentialsException::new);

        if (user.getStatus() != UserStatus.ACTIVE) {
            log.info("Login attempt for disabled account: {}", request.email());
            throw new AccountDisabledException();
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.info("Invalid password attempt for email: {}", request.email());
            throw new InvalidCredentialsException();
        }

        String token = jwtService.generateToken(user);
        long expiresInSeconds = jwtService.extractExpiration(token);

        log.info("User logged in successfully: {}", request.email());

        return new LoginResponse(token, TOKEN_TYPE, expiresInSeconds);
    }

    @Override
    public LogoutResponse logout(String token) {
        // 從 Token 解析 JTI（用於黑名單識別）
        String jti = jwtService.extractJti(token);

        // 計算 Token 剩餘有效期，確保黑名單在 Token 真正過期後才失效
        long ttlSeconds = jwtService.extractExpiration(token);

        if (ttlSeconds > 0) {
            tokenBlacklistService.addToBlacklist(jti, ttlSeconds);
            log.info("Token revoked: jti={}, ttl={}s", jti, ttlSeconds);
        }

        return new LogoutResponse("已成功登出");
    }
}
