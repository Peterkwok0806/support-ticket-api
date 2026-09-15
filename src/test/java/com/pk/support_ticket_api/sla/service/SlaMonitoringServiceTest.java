package com.pk.support_ticket_api.sla.service;

import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import com.pk.support_ticket_api.notifications.domain.NotificationType;
import com.pk.support_ticket_api.notifications.service.NotificationService;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import com.pk.support_ticket_api.tickets.repository.TicketRepository;
import com.pk.support_ticket_api.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SlaMonitoringService 測試")
class SlaMonitoringServiceTest {

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailNotificationService emailNotificationService;

    @InjectMocks
    private SlaMonitoringService slaMonitoringService;

    private Ticket breachedTicket;
    private Ticket warningTicket;
    private UUID adminId;
    private UUID agentId;

    @BeforeEach
    void setUp() {
        adminId = UUID.randomUUID();
        agentId = UUID.randomUUID();

        breachedTicket = createTicket(Instant.now().minus(1, ChronoUnit.HOURS));
        breachedTicket.setTitle("Breached Ticket");
        breachedTicket.setStatus(TicketStatus.OPEN);
        breachedTicket.setPriority(TicketPriority.HIGH);
        breachedTicket.setAssignedTo(agentId);

        warningTicket = createTicket(Instant.now().plus(1, ChronoUnit.HOURS));
        warningTicket.setTitle("Warning Ticket");
        warningTicket.setStatus(TicketStatus.IN_PROGRESS);
        warningTicket.setPriority(TicketPriority.MEDIUM);
        warningTicket.setAssignedTo(agentId);
    }

    @Nested
    @DisplayName("processSlaBreaches 處理 SLA 逾期")
    class ProcessSlaBreaches {

        @Test
        @DisplayName("應處理已逾期的 Tickets 並發送通知")
        void shouldProcessBreachedTickets() {
            when(ticketRepository.findAll(any(Specification.class)))
                    .thenReturn(List.of(breachedTicket));
            when(userRepository.findAllAdminIds())
                    .thenReturn(List.of(adminId));

            slaMonitoringService.processSlaBreaches();

            // 驗證發送通知給 Agent 和 Admin
            verify(notificationService, times(2)).sendSlaNotification(
                    eq(breachedTicket),
                    eq(NotificationType.SLA_BREACH),
                    any(UUID.class));
        }

        @Test
        @DisplayName("應觸發 Email 通知")
        void shouldTriggerEmailNotification() {
            when(ticketRepository.findAll(any(Specification.class)))
                    .thenReturn(List.of(breachedTicket));
            when(userRepository.findAllAdminIds())
                    .thenReturn(List.of(adminId));

            slaMonitoringService.processSlaBreaches();

            verify(emailNotificationService)
                    .sendSlaAlertEmail(breachedTicket.getId(), NotificationType.SLA_BREACH);
        }

        @Test
        @DisplayName("無已逾期的 Tickets 時不發送通知")
        void shouldNotSendWhenNoBreachedTickets() {
            when(ticketRepository.findAll(any(Specification.class)))
                    .thenReturn(List.of());

            slaMonitoringService.processSlaBreaches();

            verifyNoInteractions(notificationService);
            verifyNoInteractions(emailNotificationService);
        }

        @Test
        @DisplayName("Unique Index 衝突時應跳過而不拋出例外")
        void shouldSkipOnUniqueConstraintViolation() {
            when(ticketRepository.findAll(any(Specification.class)))
                    .thenReturn(List.of(breachedTicket));
            when(userRepository.findAllAdminIds())
                    .thenReturn(List.of(adminId));

            doThrow(new DataIntegrityViolationException("Unique constraint"))
                    .when(notificationService).sendSlaNotification(any(), any(), any());

            // 不應拋出例外
            slaMonitoringService.processSlaBreaches();

            verify(notificationService, atLeast(1)).sendSlaNotification(
                    eq(breachedTicket),
                    eq(NotificationType.SLA_BREACH),
                    any(UUID.class));
        }

        @Test
        @DisplayName("Ticket 無 Agent 時只通知 Admin")
        void shouldOnlyNotifyAdminWhenNoAgent() {
            breachedTicket.setAssignedTo(null);

            when(ticketRepository.findAll(any(Specification.class)))
                    .thenReturn(List.of(breachedTicket));
            when(userRepository.findAllAdminIds())
                    .thenReturn(List.of(adminId));

            slaMonitoringService.processSlaBreaches();

            // 只應發送 1 次通知（給 Admin）
            verify(notificationService, times(1)).sendSlaNotification(
                    eq(breachedTicket),
                    eq(NotificationType.SLA_BREACH),
                    eq(adminId));
        }
    }

    @Nested
    @DisplayName("processSlaWarnings 處理 SLA 警告")
    class ProcessSlaWarnings {

        @Test
        @DisplayName("應處理即將逾期的 Tickets 並發送通知")
        void shouldProcessWarningTickets() {
            when(ticketRepository.findAll(any(Specification.class)))
                    .thenReturn(List.of(warningTicket));

            slaMonitoringService.processSlaWarnings();

            verify(notificationService, times(1)).sendSlaNotification(
                    eq(warningTicket),
                    eq(NotificationType.SLA_WARNING),
                    eq(agentId));
        }

        @Test
        @DisplayName("應觸發 Warning Email 通知")
        void shouldTriggerWarningEmailNotification() {
            when(ticketRepository.findAll(any(Specification.class)))
                    .thenReturn(List.of(warningTicket));

            slaMonitoringService.processSlaWarnings();

            verify(emailNotificationService)
                    .sendSlaAlertEmail(warningTicket.getId(), NotificationType.SLA_WARNING);
        }

        @Test
        @DisplayName("無即將逾期的 Tickets 時不發送通知")
        void shouldNotSendWhenNoWarningTickets() {
            when(ticketRepository.findAll(any(Specification.class)))
                    .thenReturn(List.of());

            slaMonitoringService.processSlaWarnings();

            verifyNoInteractions(notificationService);
            verifyNoInteractions(emailNotificationService);
        }

        @Test
        @DisplayName("Ticket 無 Agent 時不發送 Warning 通知")
        void shouldNotNotifyWhenNoAgent() {
            warningTicket.setAssignedTo(null);

            when(ticketRepository.findAll(any(Specification.class)))
                    .thenReturn(List.of(warningTicket));

            slaMonitoringService.processSlaWarnings();

            verifyNoInteractions(notificationService);
            verifyNoInteractions(emailNotificationService);
        }

        @Test
        @DisplayName("Warning 時不通知 Admin")
        void shouldNotNotifyAdminForWarning() {
            when(ticketRepository.findAll(any(Specification.class)))
                    .thenReturn(List.of(warningTicket));

            slaMonitoringService.processSlaWarnings();

            ArgumentCaptor<UUID> recipientCaptor = ArgumentCaptor.forClass(UUID.class);
            verify(notificationService).sendSlaNotification(
                    eq(warningTicket),
                    eq(NotificationType.SLA_WARNING),
                    recipientCaptor.capture());

            assertThat(recipientCaptor.getValue()).isEqualTo(agentId);
            verify(userRepository, never()).findAllAdminIds();
        }
    }

    @Nested
    @DisplayName("多個 Tickets 處理")
    class MultipleTickets {

        @Test
        @DisplayName("應處理多個已逾期的 Tickets")
        void shouldProcessMultipleBreachedTickets() {
            Ticket anotherBreached = createTicket(Instant.now().minus(2, ChronoUnit.HOURS));
            anotherBreached.setTitle("Another Breached");
            anotherBreached.setStatus(TicketStatus.IN_PROGRESS);
            anotherBreached.setPriority(TicketPriority.MEDIUM);
            anotherBreached.setAssignedTo(UUID.randomUUID());

            when(ticketRepository.findAll(any(Specification.class)))
                    .thenReturn(List.of(breachedTicket, anotherBreached));
            when(userRepository.findAllAdminIds())
                    .thenReturn(List.of(adminId));

            slaMonitoringService.processSlaBreaches();

            // 每個 Ticket 應發送 2 次通知（Agent + Admin）
            verify(notificationService, times(4)).sendSlaNotification(
                    any(Ticket.class),
                    eq(NotificationType.SLA_BREACH),
                    any(UUID.class));
        }
    }

    @Nested
    @DisplayName("錯誤處理")
    class ErrorHandling {

        @Test
        @DisplayName("非 Unique Index 例外應被記錄但繼續處理")
        void shouldContinueOnNonUniqueException() {
            when(ticketRepository.findAll(any(Specification.class)))
                    .thenReturn(List.of(breachedTicket));
            when(userRepository.findAllAdminIds())
                    .thenReturn(List.of(adminId));

            // 第一次成功，第二次拋出非衝突例外
            doNothing()
                    .doThrow(new RuntimeException("Database error"))
                    .when(notificationService).sendSlaNotification(any(), any(), any());

            // 不應拋出例外，應繼續處理
            slaMonitoringService.processSlaBreaches();

            verify(notificationService, atLeast(1)).sendSlaNotification(any(), any(), any());
        }
    }

    // ==================== Helper Methods ====================

    private Ticket createTicket(Instant slaDeadline) {
        Ticket ticket = new Ticket();
        ReflectionTestUtils.setField(ticket, "id", UUID.randomUUID());
        ticket.setSlaDeadline(slaDeadline);
        return ticket;
    }
}
