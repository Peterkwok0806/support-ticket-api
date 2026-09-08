package com.pk.support_ticket_api.tickets.dto;

import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import com.pk.support_ticket_api.common.domain.enums.TicketStatus;

import java.util.List;
import java.util.UUID;

public record TicketFilterRequest(
    List<TicketStatus> statuses,
    TicketPriority priority,
    UUID categoryId,
    UUID assignedTo,
    UUID createdBy,
    String keyword
) {}
