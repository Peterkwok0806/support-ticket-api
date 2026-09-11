package com.pk.support_ticket_api.audit.web;

import com.pk.support_ticket_api.audit.dto.AuditLogResponse;
import com.pk.support_ticket_api.audit.service.AuditLogService;
import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/audit-logs")
@RequiredArgsConstructor
@Tag(name = "Audit Logs", description = "稽核日誌 API")
public class TicketAuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "查詢 Ticket 的稽核日誌")
    public ResponseEntity<PageResponse<AuditLogResponse>> findAll(
            @PathVariable UUID ticketId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        PageResponse<AuditLogResponse> response =
            auditLogService.getAuditLogsByTicketId(ticketId, pageable, currentUser);
        return ResponseEntity.ok(response);
    }
}
