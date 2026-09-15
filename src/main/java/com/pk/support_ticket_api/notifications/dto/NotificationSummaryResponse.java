package com.pk.support_ticket_api.notifications.dto;

import com.pk.support_ticket_api.notifications.domain.Notification;
import com.pk.support_ticket_api.notifications.domain.NotificationType;

import java.time.Instant;
import java.util.UUID;

public record NotificationSummaryResponse(
        UUID id,
        UUID ticketId,
        NotificationType type,
        String title,
        String message,
        Boolean isRead,
        Instant createdAt
) {
    public static NotificationSummaryResponse from(Notification notification) {
        return new NotificationSummaryResponse(
                notification.getId(),
                notification.getTicketId(),
                notification.getType(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getIsRead(),
                notification.getCreatedAt()
        );
    }
}
