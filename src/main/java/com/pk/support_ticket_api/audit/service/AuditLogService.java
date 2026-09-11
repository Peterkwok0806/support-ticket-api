package com.pk.support_ticket_api.audit.service;

import com.pk.support_ticket_api.audit.dto.AuditLogResponse;
import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.common.security.CurrentUser;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface AuditLogService {

    PageResponse<AuditLogResponse> getAuditLogsByTicketId(
        UUID ticketId,
        Pageable pageable,
        CurrentUser currentUser
    );
}
