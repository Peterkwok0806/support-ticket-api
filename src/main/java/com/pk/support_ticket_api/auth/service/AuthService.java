package com.pk.support_ticket_api.auth.service;

import com.pk.support_ticket_api.auth.dto.LoginRequest;
import com.pk.support_ticket_api.auth.dto.LoginResponse;
import com.pk.support_ticket_api.auth.dto.LogoutResponse;

public interface AuthService {

    /**
     * 使用者登入
     * @param request 登入請求（email + password）
     * @return 登入回應（accessToken + expiresIn）
     */
    LoginResponse login(LoginRequest request);

    /**
     * 使用者登出
     * @param token 當前使用的 JWT Token
     * @return 登出回應
     */
    LogoutResponse logout(String token);
}
