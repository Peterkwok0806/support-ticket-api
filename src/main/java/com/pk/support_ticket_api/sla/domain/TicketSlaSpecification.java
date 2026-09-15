package com.pk.support_ticket_api.sla.domain;

import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import org.springframework.data.jpa.domain.Specification;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class TicketSlaSpecification {

    private static final Duration WARNING_WINDOW = Duration.ofHours(2);

    private static final List<TicketStatus> ACTIVE_STATUSES = List.of(
            TicketStatus.OPEN,
            TicketStatus.IN_PROGRESS
    );

    /**
     * 查詢即將逾期的 Tickets（用於 Warning）
     * 條件：
     * - 狀態為 OPEN, IN_PROGRESS, WAITING_ON_CUSTOMER
     * - SLA 尚未逾期（slaDeadline > now）
     * - 在警告範圍內（slaDeadline <= now + 2h）
     */
    public static Specification<Ticket> isApproachingSlaBreach() {
        Instant now = Instant.now();
        Instant warningThreshold = now.plus(WARNING_WINDOW);

        return (root, query, cb) -> cb.and(
                cb.isTrue(root.get("slaDeadline").isNotNull()),
                root.get("status").in(ACTIVE_STATUSES),
                cb.greaterThan(root.get("slaDeadline"), now),
                cb.lessThanOrEqualTo(root.get("slaDeadline"), warningThreshold)
        );
    }

    /**
     * 查詢已逾期的 Tickets（用於 Breach）
     * 條件：
     * - 狀態為 OPEN, IN_PROGRESS, WAITING_ON_CUSTOMER
     * - SLA 已逾期（slaDeadline < now）
     */
    public static Specification<Ticket> isSlaBreached() {
        Instant now = Instant.now();

        return (root, query, cb) -> cb.and(
                cb.isTrue(root.get("slaDeadline").isNotNull()),
                root.get("status").in(ACTIVE_STATUSES),
                cb.lessThan(root.get("slaDeadline"), now)
        );
    }
}
