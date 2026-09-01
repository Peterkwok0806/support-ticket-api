package com.pk.support_ticket_api.auth.exception;

public class AccountDisabledException extends AuthException {

    public AccountDisabledException() {
        super("ACCOUNT_DISABLED", "Account is disabled");
    }
}
