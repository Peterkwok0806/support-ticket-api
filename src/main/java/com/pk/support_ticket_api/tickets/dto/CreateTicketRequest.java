package com.pk.support_ticket_api.tickets.dto;

import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateTicketRequest(
    @NotBlank(message = "標題為必填")
    @Size(min = 1, max = 200, message = "標題長度需 1-200 字元")
    String title,

    @Size(max = 10000, message = "描述最大 10000 字元")
    String description,

    @NotNull(message = "分類為必填")
    UUID categoryId,

    TicketPriority priority
) {}
