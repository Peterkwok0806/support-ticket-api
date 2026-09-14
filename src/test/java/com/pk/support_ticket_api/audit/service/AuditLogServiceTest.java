package com.pk.support_ticket_api.audit.service;

import com.pk.support_ticket_api.audit.domain.AuditLog;
import com.pk.support_ticket_api.audit.domain.AuditFieldName;
import com.pk.support_ticket_api.audit.dto.AuditLogResponse;
import com.pk.support_ticket_api.audit.repository.AuditLogRepository;
import com.pk.support_ticket_api.common.domain.enums.AuditAction;
import com.pk.support_ticket_api.common.exception.ForbiddenOperationException;
import com.pk.support_ticket_api.common.exception.ResourceNotFoundException;
import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.common.security.CurrentUser;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import com.pk.support_ticket_api.tickets.repository.TicketRepository;
import com.pk.support_ticket_api.users.domain.User;
import com.pk.support_ticket_api.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditLogService 單元測試")
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AuditLogServiceImpl auditLogService;

    private UUID ticketId;
    private UUID customerId;
    private UUID agentId;
    private UUID adminId;
    private Ticket testTicket;

    @BeforeEach
    void setUp() {
        ticketId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        agentId = UUID.randomUUID();
        adminId = UUID.randomUUID();

        testTicket = new Ticket();
        ReflectionTestUtils.setField(testTicket, "id", ticketId);
        testTicket.setCreatedBy(customerId);
    }

    @Nested
    @DisplayName("getAuditLogsByTicketId 權限測試")
    class PermissionTests {

        @Test
        @DisplayName("Customer 查詢自己建立的 Ticket 應成功")
        void customerCanQueryOwnTicket() {
            // Given
            CurrentUser customer = new CurrentUser(customerId, "customer@test.com", "CUSTOMER");
            Pageable pageable = PageRequest.of(0, 20);
            AuditLog auditLog = createAuditLog(AuditAction.TICKET_CREATED);
            Page<AuditLog> page = new PageImpl<>(List.of(auditLog));

            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(testTicket));
            when(auditLogRepository.findByTicketIdOrderByCreatedAtDesc(ticketId, pageable)).thenReturn(page);
            when(userRepository.findById(customerId)).thenReturn(Optional.of(createUser(customerId)));

            // When
            PageResponse<AuditLogResponse> response = auditLogService.getAuditLogsByTicketId(
                ticketId, pageable, customer);

            // Then
            assertThat(response.content()).hasSize(1);
            verify(auditLogRepository).findByTicketIdOrderByCreatedAtDesc(ticketId, pageable);
        }

        @Test
        @DisplayName("Customer 查詢他人 Ticket 應拋出 ForbiddenOperationException")
        void customerCannotQueryOthersTicket() {
            // Given
            CurrentUser customer = new CurrentUser(UUID.randomUUID(), "other@test.com", "CUSTOMER");
            Pageable pageable = PageRequest.of(0, 20);

            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(testTicket));

            // When/Then
            assertThatThrownBy(() -> auditLogService.getAuditLogsByTicketId(ticketId, pageable, customer))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("No permission to view audit logs");
        }

        @Test
        @DisplayName("Agent 查詢被指派的 Ticket 應成功")
        void agentCanQueryAssignedTicket() {
            // Given
            testTicket.setAssignedTo(agentId);
            CurrentUser agent = new CurrentUser(agentId, "agent@test.com", "AGENT");
            Pageable pageable = PageRequest.of(0, 20);
            AuditLog auditLog = createAuditLog(AuditAction.STATUS_CHANGED);
            ReflectionTestUtils.setField(auditLog, "actorId", agentId);
            Page<AuditLog> page = new PageImpl<>(List.of(auditLog));

            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(testTicket));
            when(auditLogRepository.findByTicketIdOrderByCreatedAtDesc(ticketId, pageable)).thenReturn(page);
            when(userRepository.findById(any())).thenReturn(Optional.of(createUser(agentId)));

            // When
            PageResponse<AuditLogResponse> response = auditLogService.getAuditLogsByTicketId(
                ticketId, pageable, agent);

            // Then
            assertThat(response.content()).hasSize(1);
        }

        @Test
        @DisplayName("Agent 查詢未被指派的 Ticket 應拋出 ForbiddenOperationException")
        void agentCannotQueryUnassignedTicket() {
            // Given
            testTicket.setAssignedTo(UUID.randomUUID());  // 指派給別人
            CurrentUser agent = new CurrentUser(agentId, "agent@test.com", "AGENT");
            Pageable pageable = PageRequest.of(0, 20);

            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(testTicket));

            // When/Then
            assertThatThrownBy(() -> auditLogService.getAuditLogsByTicketId(ticketId, pageable, agent))
                .isInstanceOf(ForbiddenOperationException.class);
        }

        @Test
        @DisplayName("Admin 查詢任何 Ticket 應成功")
        void adminCanQueryAnyTicket() {
            // Given
            CurrentUser admin = new CurrentUser(adminId, "admin@test.com", "ADMIN");
            Pageable pageable = PageRequest.of(0, 20);
            AuditLog auditLog = createAuditLog(AuditAction.COMMENT_ADDED);
            ReflectionTestUtils.setField(auditLog, "actorId", adminId);
            Page<AuditLog> page = new PageImpl<>(List.of(auditLog));

            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(testTicket));
            when(auditLogRepository.findByTicketIdOrderByCreatedAtDesc(ticketId, pageable)).thenReturn(page);
            when(userRepository.findById(any())).thenReturn(Optional.of(createUser(adminId)));

            // When
            PageResponse<AuditLogResponse> response = auditLogService.getAuditLogsByTicketId(
                ticketId, pageable, admin);

            // Then
            assertThat(response.content()).hasSize(1);
        }

        @Test
        @DisplayName("Ticket 不存在應拋出 ResourceNotFoundException")
        void ticketNotFoundShouldThrowException() {
            // Given
            UUID nonExistentTicketId = UUID.randomUUID();
            CurrentUser admin = new CurrentUser(adminId, "admin@test.com", "ADMIN");
            Pageable pageable = PageRequest.of(0, 20);

            when(ticketRepository.findById(nonExistentTicketId)).thenReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> auditLogService.getAuditLogsByTicketId(nonExistentTicketId, pageable, admin))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Ticket not found");
        }
    }

    @Nested
    @DisplayName("getAuditLogsByTicketId 回應格式測試")
    class ResponseFormatTests {

        @Test
        @DisplayName("應正確填充 actorName")
        void shouldEnrichActorName() {
            // Given
            CurrentUser admin = new CurrentUser(adminId, "admin@test.com", "ADMIN");
            Pageable pageable = PageRequest.of(0, 20);

            AuditLog auditLog = createAuditLog(AuditAction.TICKET_CREATED);
            ReflectionTestUtils.setField(auditLog, "actorId", adminId);
            Page<AuditLog> page = new PageImpl<>(List.of(auditLog));

            User actor = createUser(adminId);
            actor.setDisplayName("Test Admin");

            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(testTicket));
            when(auditLogRepository.findByTicketIdOrderByCreatedAtDesc(ticketId, pageable)).thenReturn(page);
            when(userRepository.findById(adminId)).thenReturn(Optional.of(actor));

            // When
            PageResponse<AuditLogResponse> response = auditLogService.getAuditLogsByTicketId(
                ticketId, pageable, admin);

            // Then
            assertThat(response.content().get(0).actorName()).isEqualTo("Test Admin");
        }
    }

    // ==================== Helper Methods ====================

    private AuditLog createAuditLog(AuditAction action) {
        AuditLog log = AuditLog.create(customerId, ticketId, action);
        ReflectionTestUtils.setField(log, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(log, "createdAt", Instant.now());
        return log;
    }

    private User createUser(UUID userId) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setDisplayName("Test User");
        return user;
    }
}
