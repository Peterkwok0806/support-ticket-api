package com.pk.support_ticket_api.notifications.service;

import com.pk.support_ticket_api.common.exception.ResourceNotFoundException;
import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.notifications.domain.Notification;
import com.pk.support_ticket_api.notifications.domain.NotificationType;
import com.pk.support_ticket_api.notifications.dto.NotificationContent;
import com.pk.support_ticket_api.notifications.dto.NotificationResponse;
import com.pk.support_ticket_api.notifications.dto.NotificationSummaryResponse;
import com.pk.support_ticket_api.notifications.repository.NotificationRepository;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;

    @Override
    public void sendSlaNotification(Ticket ticket, NotificationType type, UUID recipientId) {
        NotificationContent content = buildContent(ticket, type);

        Notification notification = Notification.create(
                recipientId,
                ticket.getId(),
                type,
                content.title(),
                content.message()
        );

        notificationRepository.save(notification);
        log.info("Sent {} notification for ticket {} to user {}",
                type, ticket.getId(), recipientId);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NotificationSummaryResponse> getNotifications(
            UUID recipientId,
            Pageable pageable,
            boolean unreadOnly
    ) {
        Page<Notification> page;

        if (unreadOnly) {
            page = notificationRepository.findByRecipientIdAndIsReadFalseOrderByCreatedAtDesc(
                    recipientId, pageable);
        } else {
            page = notificationRepository.findByRecipientIdOrderByCreatedAtDesc(
                    recipientId, pageable);
        }

        return PageResponse.from(page, NotificationSummaryResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationResponse getNotificationById(UUID notificationId, UUID recipientId) {
        Notification notification = notificationRepository
                .findByIdAndRecipientId(notificationId, recipientId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Notification not found: " + notificationId));

        return NotificationResponse.from(notification);
    }

    @Override
    public void markAsRead(UUID notificationId, UUID recipientId) {
        Notification notification = notificationRepository
                .findByIdAndRecipientId(notificationId, recipientId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Notification not found: " + notificationId));

        notification.markAsRead();
        notificationRepository.save(notification);
    }

    @Override
    public void markAllAsRead(UUID recipientId) {
        notificationRepository.markAllAsRead(recipientId);
    }

    @Override
    @Transactional(readOnly = true)
    public long getUnreadCount(UUID recipientId) {
        return notificationRepository.countByRecipientIdAndIsReadFalse(recipientId);
    }

    // ==================== Private Methods ====================

    private NotificationContent buildContent(Ticket ticket, NotificationType type) {
        return switch (type) {
            case SLA_WARNING -> NotificationContent.forSlaWarning(ticket);
            case SLA_BREACH -> NotificationContent.forSlaBreach(ticket);
            default -> throw new IllegalArgumentException("Unsupported type for SLA notification: " + type);
        };
    }
}
