package com.pk.support_ticket_api.common.exception;

public record FieldErrorResponse(
        String field,
        String message
) {
}