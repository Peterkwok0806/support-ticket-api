package com.pk.support_ticket_api.notifications.service;

import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.notifications.domain.NotificationType;
import com.pk.support_ticket_api.notifications.dto.NotificationResponse;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface NotificationService {

    /**
     * 發送 SLA 通知
     * @param ticket 逾期的 Ticket
     * @param type 通知類型（SLA_WARNING 或 SLA_BREACH）
     * @param recipientId 收件人 ID
     * @throws org.springframework.dao.DataIntegrityViolationException 若已發送過（Unique Index 衝突）
     */
    void sendSlaNotification(Ticket ticket, NotificationType type, UUID recipientId);

    /**
     * 查詢使用者的通知列表（分頁）
     */
    PageResponse<NotificationResponse> getNotifications(
            UUID recipientId,
            Pageable pageable,
            boolean unreadOnly
    );

    /**
     * 查詢單筆通知
     */
    NotificationResponse getNotificationById(UUID notificationId, UUID recipientId);

    /**
     * 標記通知為已讀
     */
    void markAsRead(UUID notificationId, UUID recipientId);

    /**
     * 標記所有通知為已讀
     */
    void markAllAsRead(UUID recipientId);

    /**
     * 取得未讀通知數量
     */
    long getUnreadCount(UUID recipientId);
}
