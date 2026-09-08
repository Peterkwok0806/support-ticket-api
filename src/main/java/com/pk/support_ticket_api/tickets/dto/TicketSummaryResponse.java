package com.pk.support_ticket_api.tickets.dto;

import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import com.pk.support_ticket_api.tickets.domain.Ticket;

import java.time.Instant;
import java.util.UUID;

public record TicketSummaryResponse(
    UUID id,
    String title,
    TicketStatus status,
    TicketPriority priority,
    String categoryName,
    String assignedToName,
    Instant slaDeadline,
    Instant createdAt
) {
    public static TicketSummaryResponse from(Ticket ticket) {
        return new TicketSummaryResponse(
            ticket.getId(),
            ticket.getTitle(),
            ticket.getStatus(),
            ticket.getPriority(),
            null,
            null,
            ticket.getSlaDeadline(),
            ticket.getCreatedAt()
        );
    }
}
