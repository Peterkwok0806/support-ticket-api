package com.pk.support_ticket_api.notifications.domain;

public enum NotificationType {
    SLA_WARNING,       // 即將逾期（1-2 小時前）
    SLA_BREACH,        // 已逾期
    TICKET_ASSIGNED,   // 被指派新 Ticket
    TICKET_RESOLVED,   // Ticket 被標記為已解決
    TICKET_UPDATED     // Ticket 被更新
}
