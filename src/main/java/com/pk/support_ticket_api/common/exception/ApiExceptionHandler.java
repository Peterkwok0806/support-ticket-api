package com.pk.support_ticket_api.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;


import java.time.Clock;
import java.time.Instant;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    private final Clock clock;

    public ApiExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            ResourceNotFoundException ex,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                ex.getMessage(),
                request.getRequestURI(),
                List.of()
        );
    }

    @ExceptionHandler(ForbiddenOperationException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(
            ForbiddenOperationException ex,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.FORBIDDEN,
                "FORBIDDEN_OPERATION",
                ex.getMessage(),
                request.getRequestURI(),
                List.of()
        );
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ErrorResponse> handleBusinessRule(
            BusinessRuleException ex,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "BUSINESS_RULE_VIOLATION",
                ex.getMessage(),
                request.getRequestURI(),
                List.of()
        );
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(
            ConflictException ex,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.CONFLICT,
                "CONFLICT",
                ex.getMessage(),
                request.getRequestURI(),
                List.of()
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableMessage(
        HttpMessageNotReadableException ex,
        HttpServletRequest request
    ) {
        return buildResponse(
            HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST_BODY",
            "Request body is invalid or contains unsupported values",
            request.getRequestURI(),
            List.of()
        );
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(
        DataIntegrityViolationException ex,
        HttpServletRequest request
    ) {
    return buildResponse(
            HttpStatus.CONFLICT,
            "DATA_CONFLICT",
            "The request conflicts with existing data",
            request.getRequestURI(),
            List.of()
        );
    }

    @ExceptionHandler(OptimisticLockException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(
        OptimisticLockException ex,
        HttpServletRequest request
    ) {
    return buildResponse(
            HttpStatus.CONFLICT,
            "OPTIMISTIC_LOCK_CONFLICT",
            "The ticket was updated by another user. Please refresh and try again.",
            request.getRequestURI(),
            List.of()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception ex,
            HttpServletRequest request
    ) {
        log.error(
                "Unexpected error. traceId={}, path={}",
                MDC.get("traceId"),
                request.getRequestURI(),
                ex
        );

        return buildResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_SERVER_ERROR",
                "An unexpected error occurred",
                request.getRequestURI(),
                List.of()
        );
    }

    private ResponseEntity<ErrorResponse> buildResponse(
            HttpStatus status,
            String code,
            String message,
            String path,
            List<FieldErrorResponse> fieldErrors
    ) {
        ErrorResponse response = new ErrorResponse(
                Instant.now(clock),
                status.value(),
                code,
                message,
                path,
                fieldErrors,
                MDC.get("traceId")
        );

        return ResponseEntity.status(status).body(response);
    }


}