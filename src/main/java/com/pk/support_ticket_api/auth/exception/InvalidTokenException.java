package com.pk.support_ticket_api.auth.exception;

public class InvalidTokenException extends AuthException {

    public InvalidTokenException() {
        super("INVALID_TOKEN", "Token is invalid");
    }

    public InvalidTokenException(String message) {
        super("INVALID_TOKEN", message);
    }
}
