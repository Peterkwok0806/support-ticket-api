package com.pk.support_ticket_api.auth.dto;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {}
