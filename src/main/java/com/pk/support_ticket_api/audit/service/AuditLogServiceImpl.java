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

import com.pk.support_ticket_api.common.domain.enums.Role;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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

        // 批次查詢所有演員資訊（避免 N+1 查詢）
        List<UUID> actorIds = page.getContent().stream()
            .map(AuditLog::getActorId)
            .distinct()
            .toList();

        Map<UUID, User> actorsMap = userRepository.findAllById(actorIds).stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));

        return PageResponse.from(page, auditLog -> enrichResponse(auditLog, actorsMap));
    }

    private boolean hasReadPermission(Ticket ticket, CurrentUser currentUser) {
        return switch (currentUser.role()) {
            case ADMIN -> true;
            case AGENT -> ticket.getAssignedTo() != null
                    && ticket.getAssignedTo().equals(currentUser.userId());
            case CUSTOMER -> ticket.getCreatedBy().equals(currentUser.userId());
        };
    }

    private AuditLogResponse enrichResponse(AuditLog auditLog, Map<UUID, User> actorsMap) {
        String actorName = actorsMap.get(auditLog.getActorId()) != null
            ? actorsMap.get(auditLog.getActorId()).getDisplayName()
            : null;

        return new AuditLogResponse(
            auditLog.getId(),
            auditLog.getActorId(),
            actorName,
            auditLog.getTicketId(),
            auditLog.getAction(),
            auditLog.getFieldName(),
            auditLog.getOldValue(),
            auditLog.getNewValue(),
            auditLog.getInternal(),
            auditLog.getCreatedAt()
        );
    }
}
