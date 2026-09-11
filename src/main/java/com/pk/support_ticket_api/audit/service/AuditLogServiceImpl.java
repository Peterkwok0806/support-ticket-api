package com.pk.support_ticket_api.audit.service;

import com.pk.support_ticket_api.audit.domain.AuditLog;
import com.pk.support_ticket_api.audit.dto.AuditLogResponse;
import com.pk.support_ticket_api.audit.repository.AuditLogRepository;
import com.pk.support_ticket_api.common.exception.ForbiddenOperationException;
import com.pk.support_ticket_api.common.exception.ResourceNotFoundException;
import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.common.security.CurrentUser;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import com.pk.support_ticket_api.tickets.repository.TicketRepository;
import com.pk.support_ticket_api.users.domain.User;
import com.pk.support_ticket_api.users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;

    @Override
    public PageResponse<AuditLogResponse> getAuditLogsByTicketId(
            UUID ticketId,
            Pageable pageable,
            CurrentUser currentUser
    ) {
        Ticket ticket = ticketRepository.findById(ticketId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Ticket not found: " + ticketId));

        if (!hasReadPermission(ticket, currentUser)) {
            throw new ForbiddenOperationException(
                "No permission to view audit logs for this ticket");
        }

        Page<AuditLog> page = auditLogRepository.findByTicketIdOrderByCreatedAtDesc(
            ticketId, pageable);

        return PageResponse.from(page, this::enrichResponse);
    }

    private boolean hasReadPermission(Ticket ticket, CurrentUser currentUser) {
        return switch (currentUser.role()) {
            case "ADMIN" -> true;
            case "AGENT" -> ticket.getAssignedTo() != null
                    && ticket.getAssignedTo().equals(currentUser.userId());
            case "CUSTOMER" -> ticket.getCreatedBy().equals(currentUser.userId());
            default -> false;
        };
    }

    private AuditLogResponse enrichResponse(AuditLog auditLog) {
        AuditLogResponse response = AuditLogResponse.from(auditLog);

        String actorName = userRepository.findById(auditLog.getActorId())
            .map(User::getDisplayName)
            .orElse(null);

        return new AuditLogResponse(
            response.id(),
            response.actorId(),
            actorName,
            response.ticketId(),
            response.action(),
            response.fieldName(),
            response.oldValue(),
            response.newValue(),
            response.internal(),
            response.createdAt()
        );
    }
}
