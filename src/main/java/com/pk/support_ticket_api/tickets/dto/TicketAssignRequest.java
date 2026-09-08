package com.pk.support_ticket_api.tickets.dto;

import jakarta.validation.constraints.Null;

import java.util.UUID;

public record TicketAssignRequest(
    @Null(message = "指派 ID 格式錯誤，請使用 null 表示取消指派")
    UUID assigneeId
) {}
