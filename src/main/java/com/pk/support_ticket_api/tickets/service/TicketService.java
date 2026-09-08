package com.pk.support_ticket_api.tickets.service;

import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.tickets.dto.*;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface TicketService {

    TicketResponse createTicket(CreateTicketRequest request, UUID createdBy);

    TicketResponse getTicketById(UUID id);

    PageResponse<TicketSummaryResponse> getTickets(
        TicketFilterRequest filter,
        Pageable pageable
    );

    TicketResponse updateTicket(UUID id, UpdateTicketRequest request);

    TicketResponse updateStatus(UUID id, TicketStatusUpdateRequest request);

    TicketResponse assignTicket(UUID id, TicketAssignRequest request);

    void deleteTicket(UUID id);
}
