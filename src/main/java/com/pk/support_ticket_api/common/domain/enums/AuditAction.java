package com.pk.support_ticket_api.common.domain.enums;

public enum AuditAction {
    TICKET_CREATED,
    STATUS_CHANGED,
    PRIORITY_CHANGED,
    ASSIGNED,
    UNASSIGNED,
    COMMENT_ADDED,
    ATTACHMENT_ADDED,
    RESOLVED,
    CLOSED,
    REOPENED
}