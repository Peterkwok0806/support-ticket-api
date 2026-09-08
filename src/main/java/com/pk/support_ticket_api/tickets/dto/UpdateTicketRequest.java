package com.pk.support_ticket_api.tickets.dto;

import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record UpdateTicketRequest(
    @Size(min = 1, max = 200, message = "標題長度需 1-200 字元")
    String title,

    @Size(max = 10000, message = "描述最大 10000 字元")
    String description,

    UUID categoryId,

    TicketPriority priority
) {}
