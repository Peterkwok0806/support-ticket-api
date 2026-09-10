package com.pk.support_ticket_api.tickets.service;

import com.pk.support_ticket_api.categories.domain.Category;
import com.pk.support_ticket_api.categories.repository.CategoryRepository;
import com.pk.support_ticket_api.categories.service.SlaCalculator;
import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import com.pk.support_ticket_api.common.exception.ForbiddenOperationException;
import com.pk.support_ticket_api.common.security.CurrentUser;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import com.pk.support_ticket_api.tickets.dto.TicketStatusUpdateRequest;
import com.pk.support_ticket_api.tickets.repository.TicketRepository;
import com.pk.support_ticket_api.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TicketService 商業邏輯測試")
class TicketServiceTest {

    @Mock
    private TicketRepository ticketRepository;

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

    private UUID ticketId;
    private UUID categoryId;
    private UUID userId;
    private UUID agentId;
    private UUID adminId;
    private Category testCategory;
    private CurrentUser adminUser;
    private CurrentUser agentUser;

    @BeforeEach
    void setUp() {
        ticketId = UUID.randomUUID();
        categoryId = UUID.randomUUID();
        userId = UUID.randomUUID();
        agentId = UUID.randomUUID();
        adminId = UUID.randomUUID();

        adminUser = new CurrentUser(adminId, "admin@test.com", "ADMIN");
        agentUser = new CurrentUser(agentId, "agent@test.com", "AGENT");

        testCategory = new Category();
        ReflectionTestUtils.setField(testCategory, "id", categoryId);
        testCategory.setName("技術支援");
        testCategory.setSlaHoursLow(72);
        testCategory.setSlaHoursMedium(48);
        testCategory.setSlaHoursHigh(24);
        testCategory.setSlaHoursUrgent(4);

        when(clock.instant()).thenReturn(Instant.now());
        when(clock.getZone()).thenReturn(ZoneId.systemDefault());
    }

    @Nested
    class UpdateStatus {

        @Test
        @DisplayName("ADMIN 可變更任何 Ticket 狀態")
        void updateStatus_adminCanChangeAnyStatus() {
            Ticket ticket = createTicket(TicketStatus.OPEN, userId);
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

            TicketStatusUpdateRequest request = new TicketStatusUpdateRequest(TicketStatus.IN_PROGRESS);
            ticketService.updateStatus(ticketId, request, adminUser);

            verify(ticketRepository).save(argThat(t ->
                t.getStatus() == TicketStatus.IN_PROGRESS &&
                t.getFirstResponseAt() != null
            ));
        }

        @Test
        @DisplayName("AGENT 可變更加給自己的 Ticket 狀態")
        void updateStatus_agentCanChangeAssignedStatus() {
            Ticket ticket = createTicket(TicketStatus.OPEN, userId);
            ticket.setAssignedTo(agentId);
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

            TicketStatusUpdateRequest request = new TicketStatusUpdateRequest(TicketStatus.IN_PROGRESS);
            ticketService.updateStatus(ticketId, request, agentUser);

            verify(ticketRepository).save(argThat(t -> t.getStatus() == TicketStatus.IN_PROGRESS));
        }

        @Test
        @DisplayName("狀態從 CLOSED 轉換到 OPEN 應拋出 ForbiddenOperationException")
        void updateStatus_closedToOpen_shouldThrowException() {
            Ticket ticket = createTicket(TicketStatus.CLOSED, userId);
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

            TicketStatusUpdateRequest request = new TicketStatusUpdateRequest(TicketStatus.OPEN);

            assertThatThrownBy(() -> ticketService.updateStatus(ticketId, request, adminUser))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Cannot transition");
        }

        @Test
        @DisplayName("轉換到 RESOLVED 時應設定 resolvedAt")
        void updateStatus_toResolved_shouldSetResolvedAt() {
            Ticket ticket = createTicket(TicketStatus.IN_PROGRESS, userId);
            ticket.setAssignedTo(agentId);
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

            TicketStatusUpdateRequest request = new TicketStatusUpdateRequest(TicketStatus.RESOLVED);
            ticketService.updateStatus(ticketId, request, agentUser);

            verify(ticketRepository).save(argThat(t ->
                t.getStatus() == TicketStatus.RESOLVED &&
                t.getResolvedAt() != null
            ));
        }

        @Test
        @DisplayName("轉換到 CLOSED 時應設定 closedAt")
        void updateStatus_toClosed_shouldSetClosedAt() {
            Ticket ticket = createTicket(TicketStatus.RESOLVED, userId);
            ticket.setAssignedTo(agentId);
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

            TicketStatusUpdateRequest request = new TicketStatusUpdateRequest(TicketStatus.CLOSED);
            ticketService.updateStatus(ticketId, request, agentUser);

            verify(ticketRepository).save(argThat(t ->
                t.getStatus() == TicketStatus.CLOSED &&
                t.getClosedAt() != null
            ));
        }

        @Test
        @DisplayName("OPEN 無法直接轉換到 RESOLVED")
        void updateStatus_openToResolved_shouldThrowException() {
            Ticket ticket = createTicket(TicketStatus.OPEN, userId);
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

            TicketStatusUpdateRequest request = new TicketStatusUpdateRequest(TicketStatus.RESOLVED);

            assertThatThrownBy(() -> ticketService.updateStatus(ticketId, request, adminUser))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Cannot transition");
        }

        @Test
        @DisplayName("IN_PROGRESS 無法直接轉換到 CLOSED")
        void updateStatus_inProgressToClosed_shouldThrowException() {
            Ticket ticket = createTicket(TicketStatus.IN_PROGRESS, userId);
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

            TicketStatusUpdateRequest request = new TicketStatusUpdateRequest(TicketStatus.CLOSED);

            assertThatThrownBy(() -> ticketService.updateStatus(ticketId, request, adminUser))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Cannot transition");
        }
    }

    @Nested
    class UpdateTicket {

        @Test
        @DisplayName("更新已關閉的工單應拋出例外")
        void updateTicket_closedTicket_shouldThrowException() {
            Ticket ticket = createTicket(TicketStatus.CLOSED, userId);
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

            assertThatThrownBy(() -> ticketService.updateTicket(ticketId,
                new com.pk.support_ticket_api.tickets.dto.UpdateTicketRequest("新標題", null, null, null)))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Cannot update closed ticket");
        }

        @Test
        @DisplayName("更新 OPEN 狀態的工單應成功")
        void updateTicket_openTicket_shouldSucceed() {
            Ticket ticket = createTicket(TicketStatus.OPEN, userId);
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
            when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(testCategory));
            when(slaCalculator.calculateSlaDueAt(any(), any(), any())).thenReturn(Instant.now().plusSeconds(86400));

            var request = new com.pk.support_ticket_api.tickets.dto.UpdateTicketRequest("更新標題", "更新描述", null, TicketPriority.HIGH);
            ticketService.updateTicket(ticketId, request);

            verify(ticketRepository).save(argThat(t ->
                "更新標題".equals(t.getTitle()) &&
                "更新描述".equals(t.getDescription()) &&
                t.getPriority() == TicketPriority.HIGH
            ));
        }
    }

    @Nested
    class AssignTicket {

        @Test
        @DisplayName("指派已關閉的工單應拋出例外")
        void assignTicket_closedTicket_shouldThrowException() {
            Ticket ticket = createTicket(TicketStatus.CLOSED, userId);
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

            assertThatThrownBy(() -> ticketService.assignTicket(ticketId,
                new com.pk.support_ticket_api.tickets.dto.TicketAssignRequest(null)))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Cannot assign closed ticket");
        }

        @Test
        @DisplayName("指派有效的使用者應成功")
        void assignTicket_validUser_shouldSucceed() {
            Ticket ticket = createTicket(TicketStatus.OPEN, userId);
            UUID assigneeId = UUID.randomUUID();
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
            when(userRepository.existsById(assigneeId)).thenReturn(true);

            var request = new com.pk.support_ticket_api.tickets.dto.TicketAssignRequest(assigneeId);
            ticketService.assignTicket(ticketId, request);

            verify(ticketRepository).save(argThat(t -> assigneeId.equals(t.getAssignedTo())));
        }
    }

    private Ticket createTicket(TicketStatus status, UUID createdBy) {
        Ticket ticket = new Ticket();
        ReflectionTestUtils.setField(ticket, "id", ticketId);
        ticket.setTitle("Test Ticket");
        ticket.setStatus(status);
        ticket.setPriority(TicketPriority.MEDIUM);
        ticket.setCategoryId(categoryId);
        ticket.setCreatedBy(createdBy);
        ReflectionTestUtils.setField(ticket, "createdAt", Instant.now());
        return ticket;
    }
}
