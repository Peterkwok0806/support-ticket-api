package com.pk.support_ticket_api.notifications.service;

import com.pk.support_ticket_api.common.exception.ResourceNotFoundException;
import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.notifications.domain.Notification;
import com.pk.support_ticket_api.notifications.domain.NotificationType;
import com.pk.support_ticket_api.notifications.dto.NotificationResponse;
import com.pk.support_ticket_api.notifications.repository.NotificationRepository;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationServiceImpl 測試")
class NotificationServiceImplTest {

    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    private UUID recipientId;
    private UUID ticketId;
    private Ticket ticket;

    @BeforeEach
    void setUp() {
        recipientId = UUID.randomUUID();
        ticketId = UUID.randomUUID();

        ticket = new Ticket();
        ReflectionTestUtils.setField(ticket, "id", ticketId);
        ticket.setTitle("Test Ticket");
        ticket.setSlaDeadline(Instant.now().minus(1, ChronoUnit.HOURS));
    }

    @Nested
    @DisplayName("sendSlaNotification 發送 SLA 通知")
    class SendSlaNotification {

        @Test
        @DisplayName("應發送 SLA_BREACH 通知並儲存")
        void shouldSendSlaBreachNotification() {
            when(notificationRepository.save(any(Notification.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            notificationService.sendSlaNotification(
                    ticket, NotificationType.SLA_BREACH, recipientId);

            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(captor.capture());

            Notification saved = captor.getValue();
            assertThat(saved.getRecipientId()).isEqualTo(recipientId);
            assertThat(saved.getTicketId()).isEqualTo(ticketId);
            assertThat(saved.getType()).isEqualTo(NotificationType.SLA_BREACH);
            assertThat(saved.getTitle()).contains("[SLA 逾期]");
            assertThat(saved.getIsRead()).isFalse();
        }

        @Test
        @DisplayName("應發送 SLA_WARNING 通知並儲存")
        void shouldSendSlaWarningNotification() {
            when(notificationRepository.save(any(Notification.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            ticket.setSlaDeadline(Instant.now().plus(1, ChronoUnit.HOURS));

            notificationService.sendSlaNotification(
                    ticket, NotificationType.SLA_WARNING, recipientId);

            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(captor.capture());

            Notification saved = captor.getValue();
            assertThat(saved.getType()).isEqualTo(NotificationType.SLA_WARNING);
            assertThat(saved.getTitle()).contains("[SLA 警告]");
        }

        @Test
        @DisplayName("非 SLA 類型應拋出例外")
        void shouldThrowForNonSlaType() {
            assertThatThrownBy(() ->
                    notificationService.sendSlaNotification(
                            ticket, NotificationType.TICKET_ASSIGNED, recipientId)
            ).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported type for SLA notification");
        }
    }

    @Nested
    @DisplayName("getNotifications 查詢通知列表")
    class GetNotifications {

        @Test
        @DisplayName("應回傳所有通知分頁")
        void shouldReturnAllNotifications() {
            Notification notification = createNotification();
            Page<Notification> page = new PageImpl<>(List.of(notification));
            Pageable pageable = PageRequest.of(0, 20);

            when(notificationRepository.findByRecipientIdOrderByCreatedAtDesc(recipientId, pageable))
                    .thenReturn(page);

            PageResponse<NotificationResponse> result =
                    notificationService.getNotifications(recipientId, pageable, false);

            assertThat(result.content()).hasSize(1);
            assertThat(result.content().get(0).id()).isEqualTo(notification.getId());
            verify(notificationRepository)
                    .findByRecipientIdOrderByCreatedAtDesc(recipientId, pageable);
        }

        @Test
        @DisplayName("unreadOnly=true 時應只回傳未讀通知")
        void shouldReturnUnreadNotificationsOnly() {
            Notification notification = createNotification();
            Page<Notification> page = new PageImpl<>(List.of(notification));
            Pageable pageable = PageRequest.of(0, 20);

            when(notificationRepository
                    .findByRecipientIdAndIsReadFalseOrderByCreatedAtDesc(recipientId, pageable))
                    .thenReturn(page);

            PageResponse<NotificationResponse> result =
                    notificationService.getNotifications(recipientId, pageable, true);

            assertThat(result.content()).hasSize(1);
            verify(notificationRepository)
                    .findByRecipientIdAndIsReadFalseOrderByCreatedAtDesc(recipientId, pageable);
        }

        @Test
        @DisplayName("空結果應回傳空的分頁")
        void shouldReturnEmptyPage() {
            Page<Notification> emptyPage = new PageImpl<>(List.of());
            Pageable pageable = PageRequest.of(0, 20);

            when(notificationRepository.findByRecipientIdOrderByCreatedAtDesc(recipientId, pageable))
                    .thenReturn(emptyPage);

            PageResponse<NotificationResponse> result =
                    notificationService.getNotifications(recipientId, pageable, false);

            assertThat(result.content()).isEmpty();
            assertThat(result.totalElements()).isZero();
        }
    }

    @Nested
    @DisplayName("getNotificationById 查詢單筆通知")
    class GetNotificationById {

        @Test
        @DisplayName("應回傳指定的通知")
        void shouldReturnNotification() {
            Notification notification = createNotification();
            UUID notificationId = notification.getId();

            when(notificationRepository.findByIdAndRecipientId(notificationId, recipientId))
                    .thenReturn(Optional.of(notification));

            NotificationResponse result =
                    notificationService.getNotificationById(notificationId, recipientId);

            assertThat(result.id()).isEqualTo(notificationId);
            assertThat(result.ticketId()).isEqualTo(notification.getTicketId());
            assertThat(result.type()).isEqualTo(notification.getType());
        }

        @Test
        @DisplayName("通知不存在應拋出 ResourceNotFoundException")
        void shouldThrowWhenNotFound() {
            UUID notificationId = UUID.randomUUID();

            when(notificationRepository.findByIdAndRecipientId(notificationId, recipientId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    notificationService.getNotificationById(notificationId, recipientId)
            ).isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Notification not found");
        }
    }

    @Nested
    @DisplayName("markAsRead 標記已讀")
    class MarkAsRead {

        @Test
        @DisplayName("應標記通知為已讀")
        void shouldMarkAsRead() {
            Notification notification = createNotification();
            UUID notificationId = notification.getId();
            notification.setIsRead(false);

            when(notificationRepository.findByIdAndRecipientId(notificationId, recipientId))
                    .thenReturn(Optional.of(notification));
            when(notificationRepository.save(any(Notification.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            notificationService.markAsRead(notificationId, recipientId);

            assertThat(notification.getIsRead()).isTrue();
            verify(notificationRepository).save(notification);
        }

        @Test
        @DisplayName("通知不存在應拋出例外")
        void shouldThrowWhenNotFound() {
            UUID notificationId = UUID.randomUUID();

            when(notificationRepository.findByIdAndRecipientId(notificationId, recipientId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    notificationService.markAsRead(notificationId, recipientId)
            ).isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("markAllAsRead 標記全部已讀")
    class MarkAllAsRead {

        @Test
        @DisplayName("應呼叫 Repository 標記全部已讀")
        void shouldMarkAllAsRead() {
            notificationService.markAllAsRead(recipientId);

            verify(notificationRepository).markAllAsRead(recipientId);
        }
    }

    @Nested
    @DisplayName("getUnreadCount 取得未讀數量")
    class GetUnreadCount {

        @Test
        @DisplayName("應回傳未讀通知數量")
        void shouldReturnUnreadCount() {
            when(notificationRepository.countByRecipientIdAndIsReadFalse(recipientId))
                    .thenReturn(5L);

            long count = notificationService.getUnreadCount(recipientId);

            assertThat(count).isEqualTo(5);
            verify(notificationRepository).countByRecipientIdAndIsReadFalse(recipientId);
        }

        @Test
        @DisplayName("無未讀通知時回傳 0")
        void shouldReturnZeroWhenNoUnread() {
            when(notificationRepository.countByRecipientIdAndIsReadFalse(recipientId))
                    .thenReturn(0L);

            long count = notificationService.getUnreadCount(recipientId);

            assertThat(count).isZero();
        }
    }

    // ==================== Helper Methods ====================

    private Notification createNotification() {
        Notification notification = new Notification();
        ReflectionTestUtils.setField(notification, "id", UUID.randomUUID());
        notification.setRecipientId(recipientId);
        notification.setTicketId(ticketId);
        notification.setType(NotificationType.SLA_BREACH);
        notification.setTitle("[SLA 逾期] Ticket 已逾期！");
        notification.setMessage("Ticket Test Ticket 已於 2026-09-15 10:00 逾期");
        notification.setIsRead(false);
        ReflectionTestUtils.setField(notification, "createdAt", Instant.now());
        return notification;
    }
}
