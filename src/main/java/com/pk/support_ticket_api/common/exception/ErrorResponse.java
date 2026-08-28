package com.pk.support_ticket_api.common.exception;

import java.time.Instant;
import java.util.List;

public record ErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        List<FieldErrorResponse> fieldErrors,
        String traceId
) {
}