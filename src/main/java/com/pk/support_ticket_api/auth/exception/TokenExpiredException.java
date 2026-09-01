package com.pk.support_ticket_api.auth.exception;

public class TokenExpiredException extends AuthException {

    public TokenExpiredException() {
        super("TOKEN_EXPIRED", "Token has expired");
    }
}
