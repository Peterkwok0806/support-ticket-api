package com.pk.support_ticket_api.tickets.dto;

import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import jakarta.validation.constraints.NotNull;

public record TicketStatusUpdateRequest(
    @NotNull(message = "狀態為必填")
    TicketStatus status
) {}
