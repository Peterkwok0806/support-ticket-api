package com.pk.support_ticket_api.audit.dto;

import com.pk.support_ticket_api.audit.domain.AuditFieldName;
import com.pk.support_ticket_api.audit.domain.AuditLog;
import com.pk.support_ticket_api.common.domain.enums.AuditAction;

import java.time.Instant;
import java.util.UUID;

public record AuditLogResponse(
    UUID id,
    UUID actorId,
    String actorName,
    UUID ticketId,
    AuditAction action,
    AuditFieldName fieldName,
    String oldValue,
    String newValue,
    Boolean internal,
    Instant createdAt
) {

    public static AuditLogResponse from(AuditLog auditLog) {
        return new AuditLogResponse(
            auditLog.getId(),
            auditLog.getActorId(),
            null,  // actorName 由 Service 層填充
            auditLog.getTicketId(),
            auditLog.getAction(),
            auditLog.getFieldName(),
            auditLog.getOldValue(),
            auditLog.getNewValue(),
            auditLog.getInternal(),
            auditLog.getCreatedAt()
        );
    }
}
