package com.pk.support_ticket_api.auth.exception;

public class InvalidCredentialsException extends AuthException {

    public InvalidCredentialsException() {
        super("INVALID_CREDENTIALS", "Invalid email or password");
    }
}
