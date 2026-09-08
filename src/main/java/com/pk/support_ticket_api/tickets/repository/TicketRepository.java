package com.pk.support_ticket_api.tickets.repository;

import com.pk.support_ticket_api.tickets.domain.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TicketRepository extends
        JpaRepository<Ticket, UUID>,
        JpaSpecificationExecutor<Ticket> {
}
