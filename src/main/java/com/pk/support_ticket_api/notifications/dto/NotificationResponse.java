package com.pk.support_ticket_api.notifications.dto;

import com.pk.support_ticket_api.notifications.domain.Notification;
import com.pk.support_ticket_api.notifications.domain.NotificationType;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        UUID ticketId,
        NotificationType type,
        String title,
        String message,
        Boolean isRead,
        Instant createdAt
) {
    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
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
