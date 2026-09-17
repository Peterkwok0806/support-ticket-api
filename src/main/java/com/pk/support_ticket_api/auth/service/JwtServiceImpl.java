package com.pk.support_ticket_api.auth.service;

import com.pk.support_ticket_api.auth.exception.InvalidTokenException;
import com.pk.support_ticket_api.auth.exception.TokenExpiredException;
import com.pk.support_ticket_api.common.domain.enums.Role;
import com.pk.support_ticket_api.common.security.CurrentUser;
import com.pk.support_ticket_api.users.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SignatureException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtServiceImpl implements JwtService {

    private final SecretKey secretKey;
    private final long expirationMs;

    public JwtServiceImpl(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-expiration:PT2H}") Duration accessExpiration
    ) {
        this.secretKey = io.jsonwebtoken.security.Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = accessExpiration.toMillis();
    }

    @Override
    public String generateToken(User user) {
        Instant now = Instant.now();
        Instant expiration = now.plusMillis(expirationMs);

        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .id(UUID.randomUUID().toString())
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    @Override
    public boolean validateToken(String token) {
        parseClaims(token); 
        return true;
    }

    @Override
    public CurrentUser parseToken(String token) {
        Claims claims = parseClaims(token);
        String subject = claims.getSubject();
        String email = claims.get("email", String.class);
        String role = claims.get("role", String.class);

        if (subject == null || subject.isBlank()) {
            throw new InvalidTokenException("Token subject is missing");
        }
        if (email == null || email.isBlank()) {
            throw new InvalidTokenException("Token email claim is missing");
        }
        if (role == null || role.isBlank()) {
            throw new InvalidTokenException("Token role claim is missing");
        }

        UUID userId;
        try {
            userId = UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("Token subject is not a valid UUID");
        }

        return new CurrentUser(userId, email, Role.valueOf(role));
    }

    @Override
    public String extractJti(String token) {
        Claims claims = parseClaims(token);
        return claims.getId();
    }

    @Override
    public long extractExpiration(String token) {
        try {
            Claims claims = parseClaims(token);
            Date expiration = claims.getExpiration();
            if (expiration == null) {
                return 0;
            }
            long remainingMs = expiration.getTime() - System.currentTimeMillis();
            return Math.max(0, remainingMs / 1000);
        } catch (TokenExpiredException e) {
            return 0;
        }
    }

    private Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new TokenExpiredException();
        } catch (SignatureException e) {
            throw new InvalidTokenException("Token signature is invalid");
        } catch (IllegalArgumentException | JwtException e) {
            throw new InvalidTokenException(e.getMessage());
        }
    }

}
