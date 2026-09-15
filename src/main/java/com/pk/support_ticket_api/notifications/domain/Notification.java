package com.pk.support_ticket_api.notifications.domain;

import com.pk.support_ticket_api.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "notifications")
public class Notification extends BaseEntity {

    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private NotificationType type;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "is_read", nullable = false)
    private Boolean isRead = false;

    // ==================== Factory Methods ====================

    public static Notification create(
            UUID recipientId,
            UUID ticketId,
            NotificationType type,
            String title,
            String message
    ) {
        Notification notification = new Notification();
        notification.setRecipientId(recipientId);
        notification.setTicketId(ticketId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setIsRead(false);
        return notification;
    }

    public void markAsRead() {
        this.isRead = true;
    }
}
