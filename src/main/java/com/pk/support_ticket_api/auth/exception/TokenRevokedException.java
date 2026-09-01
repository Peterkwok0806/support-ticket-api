package com.pk.support_ticket_api.auth.exception;

public class TokenRevokedException extends AuthException {

    public TokenRevokedException() {
        super("TOKEN_REVOKED", "Token has been revoked");
    }
}
