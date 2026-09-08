package com.pk.support_ticket_api.tickets.dto;

import com.pk.support_ticket_api.categories.dto.CategorySummaryResponse;
import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import com.pk.support_ticket_api.tickets.domain.Ticket;

import java.time.Instant;
import java.util.UUID;

public record TicketResponse(
    UUID id,
    String title,
    String description,
    TicketStatus status,
    TicketPriority priority,
    CategorySummaryResponse category,
    String createdByName,
    String assignedToName,
    Instant slaDeadline,
    Instant resolvedAt,
    Instant closedAt,
    Instant firstResponseAt,
    Instant createdAt,
    Instant updatedAt
) {
    public static TicketResponse from(Ticket ticket) {
        return new TicketResponse(
            ticket.getId(),
            ticket.getTitle(),
            ticket.getDescription(),
            ticket.getStatus(),
            ticket.getPriority(),
            null,
            null,
            null,
            ticket.getSlaDeadline(),
            ticket.getResolvedAt(),
            ticket.getClosedAt(),
            ticket.getFirstResponseAt(),
            ticket.getCreatedAt(),
            ticket.getUpdatedAt()
        );
    }
}
