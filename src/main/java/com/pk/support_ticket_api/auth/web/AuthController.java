package com.pk.support_ticket_api.auth.web;

import com.pk.support_ticket_api.auth.dto.LoginRequest;
import com.pk.support_ticket_api.auth.dto.LoginResponse;
import com.pk.support_ticket_api.auth.dto.LogoutResponse;
import com.pk.support_ticket_api.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "認證 API")
public class AuthController {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;

    @PostMapping("/login")
    @Operation(summary = "使用者登入", description = "使用 email 和密碼登入，回傳 JWT Token")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "登入成功"),
            @ApiResponse(responseCode = "401", description = "帳號或密碼錯誤"),
            @ApiResponse(responseCode = "403", description = "帳號已停用"),
            @ApiResponse(responseCode = "429", description = "請求頻率過高")
    })
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    @Operation(summary = "使用者登出", description = "將當前 Token 加入黑名單")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "登出成功"),
            @ApiResponse(responseCode = "401", description = "未提供有效 Token")
    })
    public ResponseEntity<LogoutResponse> logout(@RequestHeader(value = AUTHORIZATION_HEADER, required = false) String authHeader) {
       
        String token = authHeader.substring(BEARER_PREFIX.length());
        LogoutResponse response = authService.logout(token);
        return ResponseEntity.ok(response);
    }
}
