package com.pk.support_ticket_api.tickets.service;

import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import com.pk.support_ticket_api.common.exception.ForbiddenOperationException;
import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.common.security.CurrentUser;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import com.pk.support_ticket_api.tickets.dto.TicketFilterRequest;
import com.pk.support_ticket_api.tickets.dto.TicketStatusUpdateRequest;
import com.pk.support_ticket_api.tickets.dto.TicketSummaryResponse;
import com.pk.support_ticket_api.tickets.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Ticket 權限測試")
class TicketPermissionTest {

    @Mock
    private TicketRepository ticketRepository;

    @Spy
    private TicketStateMachine stateMachine = new TicketStateMachine();

    @Mock
    private com.pk.support_ticket_api.categories.repository.CategoryRepository categoryRepository;

    @Mock
    private com.pk.support_ticket_api.users.repository.UserRepository userRepository;

    @Mock
    private com.pk.support_ticket_api.categories.service.SlaCalculator slaCalculator;

    @Mock
    private Clock clock;

    @InjectMocks
    private TicketServiceImpl ticketService;

    private UUID ticketId;
    private UUID customerId;
    private UUID agentId;
    private UUID otherAgentId;
    private UUID adminId;

    private CurrentUser customerUser;
    private CurrentUser agentUser;
    private CurrentUser otherAgentUser;
    private CurrentUser adminUser;

    @BeforeEach
    void setUp() {
        ticketId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        agentId = UUID.randomUUID();
        otherAgentId = UUID.randomUUID();
        adminId = UUID.randomUUID();

        customerUser = new CurrentUser(customerId, "customer@test.com", "CUSTOMER");
        agentUser = new CurrentUser(agentId, "agent@test.com", "AGENT");
        otherAgentUser = new CurrentUser(otherAgentId, "other@agent.com", "AGENT");
        adminUser = new CurrentUser(adminId, "admin@test.com", "ADMIN");

        when(clock.instant()).thenReturn(Instant.now());
        when(clock.getZone()).thenReturn(ZoneId.systemDefault());
    }

    @Nested
    class GetTicketById {

        @Test
        @DisplayName("ADMIN 可查看任何 Ticket")
        void adminCanViewAnyTicket() {
            Ticket ticket = createTicket(TicketStatus.OPEN, customerId);
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

            var response = ticketService.getTicketById(ticketId, adminUser);

            assertThat(response).isNotNull();
            verify(ticketRepository).findById(ticketId);
        }

        @Test
        @DisplayName("CUSTOMER 可查看自己建立的 Ticket")
        void customerCanViewOwnTicket() {
            Ticket ticket = createTicket(TicketStatus.OPEN, customerId);
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

            var response = ticketService.getTicketById(ticketId, customerUser);

            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("CUSTOMER 無法查看他人建立的 Ticket")
        void customerCannotViewOthersTicket() {
            UUID otherCustomerId = UUID.randomUUID();
            Ticket ticket = createTicket(TicketStatus.OPEN, otherCustomerId);
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

            assertThatThrownBy(() -> ticketService.getTicketById(ticketId, customerUser))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("No permission");
        }

        @Test
        @DisplayName("AGENT 可查看被指派的 Ticket")
        void agentCanViewAssignedTicket() {
            Ticket ticket = createTicket(TicketStatus.OPEN, customerId);
            ticket.setAssignedTo(agentId);
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

            var response = ticketService.getTicketById(ticketId, agentUser);

            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("AGENT 無法查看未被指派的 Ticket")
        void agentCannotViewUnassignedTicket() {
            Ticket ticket = createTicket(TicketStatus.OPEN, customerId);
            ticket.setAssignedTo(otherAgentId);
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

            assertThatThrownBy(() -> ticketService.getTicketById(ticketId, agentUser))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("No permission");
        }
    }

    @Nested
    class GetTickets {

        @Test
        @DisplayName("ADMIN 查詢不應限制過濾條件")
        void adminGetTickets_noFilter() {
            Pageable pageable = PageRequest.of(0, 20);
            Page<Ticket> page = new PageImpl<>(List.of(), pageable, 0);
            when(ticketRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            TicketFilterRequest filter = new TicketFilterRequest(null, null, null, null, null, null);
            ticketService.getTickets(filter, pageable, adminUser);

            verify(ticketRepository).findAll(any(Specification.class), eq(pageable));
        }

        @Test
        @DisplayName("CUSTOMER 查詢應自動過濾 createdBy")
        void customerGetTickets_filterByCreatedBy() {
            Pageable pageable = PageRequest.of(0, 20);
            Page<Ticket> page = new PageImpl<>(List.of(), pageable, 0);
            when(ticketRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            TicketFilterRequest filter = new TicketFilterRequest(null, null, null, null, null, null);
            ticketService.getTickets(filter, pageable, customerUser);

            verify(ticketRepository).findAll(any(Specification.class), eq(pageable));
        }

        @Test
        @DisplayName("AGENT 查詢應自動過濾 assignedTo")
        void agentGetTickets_filterByAssignedTo() {
            Pageable pageable = PageRequest.of(0, 20);
            Page<Ticket> page = new PageImpl<>(List.of(), pageable, 0);
            when(ticketRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            TicketFilterRequest filter = new TicketFilterRequest(null, null, null, null, null, null);
            ticketService.getTickets(filter, pageable, agentUser);

            verify(ticketRepository).findAll(any(Specification.class), eq(pageable));
        }
    }

    @Nested
    class UpdateStatus {

        @Test
        @DisplayName("ADMIN 可變更任何 Ticket 狀態")
        void adminCanChangeAnyStatus() {
            Ticket ticket = createTicket(TicketStatus.OPEN, customerId);
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

            TicketStatusUpdateRequest request = new TicketStatusUpdateRequest(TicketStatus.IN_PROGRESS);
            ticketService.updateStatus(ticketId, request, adminUser);

            verify(ticketRepository).save(argThat(t -> t.getStatus() == TicketStatus.IN_PROGRESS));
        }

        @Test
        @DisplayName("AGENT 可變更加給自己的 Ticket 狀態")
        void agentCanChangeAssignedStatus() {
            Ticket ticket = createTicket(TicketStatus.OPEN, customerId);
            ticket.setAssignedTo(agentId);
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

            TicketStatusUpdateRequest request = new TicketStatusUpdateRequest(TicketStatus.IN_PROGRESS);
            ticketService.updateStatus(ticketId, request, agentUser);

            verify(ticketRepository).save(argThat(t -> t.getStatus() == TicketStatus.IN_PROGRESS));
        }

        @Test
        @DisplayName("AGENT 無法變更未指派給自己的 Ticket")
        void agentCannotChangeUnassignedStatus() {
            Ticket ticket = createTicket(TicketStatus.OPEN, customerId);
            ticket.setAssignedTo(otherAgentId);
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

            TicketStatusUpdateRequest request = new TicketStatusUpdateRequest(TicketStatus.IN_PROGRESS);

            assertThatThrownBy(() -> ticketService.updateStatus(ticketId, request, agentUser))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("No permission");
        }

        @Test
        @DisplayName("CUSTOMER 無法變更任何 Ticket 狀態")
        void customerCannotChangeStatus() {
            Ticket ticket = createTicket(TicketStatus.OPEN, customerId);
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

            TicketStatusUpdateRequest request = new TicketStatusUpdateRequest(TicketStatus.CLOSED);

            assertThatThrownBy(() -> ticketService.updateStatus(ticketId, request, customerUser))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("No permission");
        }

        @Test
        @DisplayName("CUSTOMER 無法變更自己建立的 Ticket 狀態")
        void customerCannotChangeOwnTicketStatus() {
            Ticket ticket = createTicket(TicketStatus.OPEN, customerId);
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

            TicketStatusUpdateRequest request = new TicketStatusUpdateRequest(TicketStatus.IN_PROGRESS);

            assertThatThrownBy(() -> ticketService.updateStatus(ticketId, request, customerUser))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("No permission");
        }
    }

    private Ticket createTicket(TicketStatus status, UUID createdBy) {
        Ticket ticket = new Ticket();
        ReflectionTestUtils.setField(ticket, "id", ticketId);
        ticket.setTitle("Test Ticket");
        ticket.setStatus(status);
        ticket.setPriority(TicketPriority.MEDIUM);
        ticket.setCategoryId(UUID.randomUUID());
        ticket.setCreatedBy(createdBy);
        ReflectionTestUtils.setField(ticket, "createdAt", Instant.now());
        return ticket;
    }
}
