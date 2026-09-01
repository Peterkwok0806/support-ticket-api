package com.pk.support_ticket_api.auth.exception;

public class RateLimitExceededException extends AuthException {

    private final long retryAfter;

    public RateLimitExceededException(long retryAfterSeconds) {
        super("RATE_LIMIT_EXCEEDED", "Rate limit exceeded. Please try again later.");
        this.retryAfter = retryAfterSeconds;
    }

    public long getRetryAfter() {
        return retryAfter;
    }
}
