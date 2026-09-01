package com.pk.support_ticket_api.auth.service;

import com.pk.support_ticket_api.common.security.CurrentUser;
import com.pk.support_ticket_api.users.domain.User;

public interface JwtService {

    String generateToken(User user);

    boolean validateToken(String token);

    CurrentUser parseToken(String token);

    String extractJti(String token);

    long extractExpiration(String token);
}
