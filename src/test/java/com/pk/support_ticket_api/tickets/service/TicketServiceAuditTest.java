package com.pk.support_ticket_api.tickets.service;

import com.pk.support_ticket_api.audit.domain.AuditFieldName;
import com.pk.support_ticket_api.audit.domain.AuditLog;
import com.pk.support_ticket_api.audit.repository.AuditLogRepository;
import com.pk.support_ticket_api.categories.domain.Category;
import com.pk.support_ticket_api.categories.repository.CategoryRepository;
import com.pk.support_ticket_api.categories.service.SlaCalculator;
import com.pk.support_ticket_api.common.domain.enums.AuditAction;
import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import com.pk.support_ticket_api.common.exception.ForbiddenOperationException;
import com.pk.support_ticket_api.common.security.CurrentUser;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import com.pk.support_ticket_api.tickets.dto.*;
import com.pk.support_ticket_api.tickets.repository.TicketRepository;
import com.pk.support_ticket_api.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TicketService Audit Log 寫入測試")
class TicketServiceAuditTest {

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Spy
    private TicketStateMachine stateMachine = new TicketStateMachine();

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SlaCalculator slaCalculator;

    @Mock
    private Clock clock;

    @InjectMocks
    private TicketServiceImpl ticketService;

    @Captor
    private ArgumentCaptor<AuditLog> auditLogCaptor;

    private UUID customerId;
    private UUID agentId;
    private UUID categoryId;
    private UUID adminId;
    private Category testCategory;
    private CurrentUser adminUser;
    private CurrentUser agentUser;

    @BeforeEach
    void setUp() {
        customerId = UUID.randomUUID();
        agentId = UUID.randomUUID();
        categoryId = UUID.randomUUID();
        adminId = UUID.randomUUID();

        adminUser = new CurrentUser(adminId, "admin@test.com", "ADMIN");
        agentUser = new CurrentUser(agentId, "agent@test.com", "AGENT");

        testCategory = new Category();
        ReflectionTestUtils.setField(testCategory, "id", categoryId);
        testCategory.setName("Technical Support");
        testCategory.setSlaHoursLow(24);
        testCategory.setSlaHoursMedium(48);
        testCategory.setSlaHoursHigh(24);
        testCategory.setSlaHoursUrgent(4);

        Clock fixedClock = Clock.fixed(Instant.parse("2026-09-11T10:00:00Z"), ZoneId.of("UTC"));
        when(clock.instant()).thenReturn(fixedClock.instant());
        when(clock.getZone()).thenReturn(fixedClock.getZone());
    }

    @Nested
    @DisplayName("createTicket 應寫入 TICKET_CREATED")
    class CreateTicketAuditTests {

        @Test
        @DisplayName("建立 Ticket 時應寫入 TICKET_CREATED AuditLog")
        void createTicketShouldWriteAuditLog() {
            // Given
            CreateTicketRequest request = new CreateTicketRequest(
                "Test Title",
                "Test Description",
                categoryId,
                TicketPriority.MEDIUM
            );

            when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(testCategory));
            when(slaCalculator.calculateSlaDueAt(any(), any(), any())).thenReturn(Instant.now());
            when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> {
                Ticket ticket = invocation.getArgument(0);
                ReflectionTestUtils.setField(ticket, "id", UUID.randomUUID());
                return ticket;
            });

            // When
            ticketService.createTicket(request, customerId);

            // Then
            verify(auditLogRepository).save(auditLogCaptor.capture());
            AuditLog savedLog = auditLogCaptor.getValue();

            assertThat(savedLog.getActorId()).isEqualTo(customerId);
            assertThat(savedLog.getAction()).isEqualTo(AuditAction.TICKET_CREATED);
            assertThat(savedLog.getOldValue()).isNull();
            assertThat(savedLog.getNewValue()).isNull();
        }

        @Test
        @DisplayName("createTicket 失敗時不應寫入 AuditLog")
        void createTicketFailureShouldNotWriteAuditLog() {
            // Given
            CreateTicketRequest request = new CreateTicketRequest(
                "Test Title",
                "Test Description",
                categoryId,
                TicketPriority.MEDIUM
            );

            when(categoryRepository.findById(categoryId)).thenReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> ticketService.createTicket(request, customerId))
                .isInstanceOf(Exception.class);

            // AuditLog 不應被寫入
            verify(auditLogRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateStatus 應寫入 STATUS_CHANGED")
    class UpdateStatusAuditTests {

        @Test
        @DisplayName("變更狀態時應寫入 STATUS_CHANGED AuditLog")
        void updateStatusShouldWriteAuditLog() {
            // Given
            UUID ticketId = UUID.randomUUID();
            Ticket ticket = createTestTicket(ticketId, TicketStatus.OPEN);
            ticket.setAssignedTo(agentId);
            CurrentUser agent = new CurrentUser(agentId, "agent@test.com", "AGENT");

            TicketStatusUpdateRequest request = new TicketStatusUpdateRequest(TicketStatus.IN_PROGRESS);

            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(stateMachine.canTransition(TicketStatus.OPEN, TicketStatus.IN_PROGRESS)).thenReturn(true);

            Ticket savedTicket = createTestTicket(ticketId, TicketStatus.IN_PROGRESS);
            when(ticketRepository.save(any(Ticket.class))).thenReturn(savedTicket);

            // When
            ticketService.updateStatus(ticketId, request, agent);

            // Then
            verify(auditLogRepository).save(auditLogCaptor.capture());
            AuditLog savedLog = auditLogCaptor.getValue();

            assertThat(savedLog.getActorId()).isEqualTo(agentId);
            assertThat(savedLog.getAction()).isEqualTo(AuditAction.STATUS_CHANGED);
            assertThat(savedLog.getFieldName()).isEqualTo(AuditFieldName.STATUS);
            assertThat(savedLog.getOldValue()).isEqualTo("OPEN");
            assertThat(savedLog.getNewValue()).isEqualTo("IN_PROGRESS");
        }

        @Test
        @DisplayName("非 Agent/Admin 無法變更狀態")
        void customerCannotChangeStatus() {
            // Given
            UUID ticketId = UUID.randomUUID();
            Ticket ticket = createTestTicket(ticketId, TicketStatus.OPEN);
            CurrentUser customer = new CurrentUser(customerId, "customer@test.com", "CUSTOMER");

            TicketStatusUpdateRequest request = new TicketStatusUpdateRequest(TicketStatus.IN_PROGRESS);

            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

            // When/Then
            assertThatThrownBy(() -> ticketService.updateStatus(ticketId, request, customer))
                .isInstanceOf(ForbiddenOperationException.class);

            verify(auditLogRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateTicket 應寫入 PRIORITY_CHANGED")
    class UpdateTicketPriorityAuditTests {

        @Test
        @DisplayName("變更 Priority 時應寫入 PRIORITY_CHANGED AuditLog")
        void updatePriorityShouldWriteAuditLog() {
            // Given
            UUID ticketId = UUID.randomUUID();
            Ticket ticket = createTestTicket(ticketId, TicketStatus.OPEN);
            ticket.setPriority(TicketPriority.LOW);

            UpdateTicketRequest request = new UpdateTicketRequest(null, null, null, TicketPriority.HIGH);

            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(testCategory));
            when(slaCalculator.calculateSlaDueAt(any(), any(), any())).thenReturn(Instant.now());

            Ticket savedTicket = createTestTicket(ticketId, TicketStatus.OPEN);
            savedTicket.setPriority(TicketPriority.HIGH);
            when(ticketRepository.save(any(Ticket.class))).thenReturn(savedTicket);

            // When
            ticketService.updateTicket(ticketId, request, adminUser);

            // Then
            verify(auditLogRepository).save(auditLogCaptor.capture());
            AuditLog savedLog = auditLogCaptor.getValue();

            assertThat(savedLog.getAction()).isEqualTo(AuditAction.PRIORITY_CHANGED);
            assertThat(savedLog.getFieldName()).isEqualTo(AuditFieldName.PRIORITY);
            assertThat(savedLog.getOldValue()).isEqualTo("LOW");
            assertThat(savedLog.getNewValue()).isEqualTo("HIGH");
        }
    }

    @Nested
    @DisplayName("assignTicket 應寫入 ASSIGNED/UNASSIGNED")
    class AssignTicketAuditTests {

        @Test
        @DisplayName("指派 Agent 時應寫入 ASSIGNED AuditLog")
        void assignAgentShouldWriteAssignedAuditLog() {
            // Given
            UUID ticketId = UUID.randomUUID();
            UUID newAgentId = UUID.randomUUID();
            Ticket ticket = createTestTicket(ticketId, TicketStatus.OPEN);
            CurrentUser admin = new CurrentUser(adminId, "admin@test.com", "ADMIN");

            TicketAssignRequest request = new TicketAssignRequest(newAgentId.toString());

            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(userRepository.existsById(newAgentId)).thenReturn(true);

            Ticket savedTicket = createTestTicket(ticketId, TicketStatus.OPEN);
            savedTicket.setAssignedTo(newAgentId);
            when(ticketRepository.save(any(Ticket.class))).thenReturn(savedTicket);

            // When
            ticketService.assignTicket(ticketId, request, admin);

            // Then
            verify(auditLogRepository).save(auditLogCaptor.capture());
            AuditLog savedLog = auditLogCaptor.getValue();

            assertThat(savedLog.getAction()).isEqualTo(AuditAction.ASSIGNED);
            assertThat(savedLog.getFieldName()).isEqualTo(AuditFieldName.ASSIGNEE);
            assertThat(savedLog.getOldValue()).isNull();
            assertThat(savedLog.getNewValue()).isEqualTo(newAgentId.toString());
        }

        @Test
        @DisplayName("取消指派時應寫入 UNASSIGNED AuditLog")
        void unassignShouldWriteUnassignedAuditLog() {
            // Given
            UUID ticketId = UUID.randomUUID();
            Ticket ticket = createTestTicket(ticketId, TicketStatus.OPEN);
            ticket.setAssignedTo(agentId);

            CurrentUser admin = new CurrentUser(adminId, "admin@test.com", "ADMIN");
            TicketAssignRequest request = new TicketAssignRequest(null);

            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

            Ticket savedTicket = createTestTicket(ticketId, TicketStatus.OPEN);
            savedTicket.setAssignedTo(null);
            when(ticketRepository.save(any(Ticket.class))).thenReturn(savedTicket);

            // When
            ticketService.assignTicket(ticketId, request, admin);

            // Then
            verify(auditLogRepository).save(auditLogCaptor.capture());
            AuditLog savedLog = auditLogCaptor.getValue();

            assertThat(savedLog.getAction()).isEqualTo(AuditAction.UNASSIGNED);
        }
    }

    // ==================== Helper Methods ====================

    private Ticket createTestTicket(UUID ticketId, TicketStatus status) {
        Ticket ticket = new Ticket();
        ReflectionTestUtils.setField(ticket, "id", ticketId);
        ticket.setTitle("Test Ticket");
        ticket.setDescription("Test Description");
        ticket.setStatus(status);
        ticket.setPriority(TicketPriority.MEDIUM);
        ticket.setCategoryId(categoryId);
        ticket.setCreatedBy(customerId);
        ReflectionTestUtils.setField(ticket, "createdAt", Instant.now());
        return ticket;
    }
}
