package com.pk.support_ticket_api.audit.repository;

import com.pk.support_ticket_api.audit.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    Page<AuditLog> findByTicketIdOrderByCreatedAtDesc(UUID ticketId, Pageable pageable);
}
