package com.pk.support_ticket_api.notifications.repository;

import com.pk.support_ticket_api.notifications.domain.Notification;
import com.pk.support_ticket_api.notifications.domain.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * 查詢使用者的通知列表
     */
    Page<Notification> findByRecipientIdOrderByCreatedAtDesc(
            UUID recipientId,
            Pageable pageable
    );

    /**
     * 查詢使用者的未讀通知列表
     */
    Page<Notification> findByRecipientIdAndIsReadFalseOrderByCreatedAtDesc(
            UUID recipientId,
            Pageable pageable
    );

    /**
     * 查詢使用者的未讀通知數量
     */
    long countByRecipientIdAndIsReadFalse(UUID recipientId);

    /**
     * 查詢單筆通知（驗證歸屬）
     */
    Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId);

    /**
     * 查詢特定 Ticket 的特定類型通知（用於 Email 發送）
     */
    List<Notification> findByTicketIdAndType(UUID ticketId, NotificationType type);

    /**
     * 標記所有通知為已讀
     */
    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.recipientId = :recipientId AND n.isRead = false")
    int markAllAsRead(@Param("recipientId") UUID recipientId);
}
