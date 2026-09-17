package com.pk.support_ticket_api.reporting.service;

import com.pk.support_ticket_api.categories.repository.CategoryRepository;
import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import com.pk.support_ticket_api.reporting.dto.DashboardSummaryResponse;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import com.pk.support_ticket_api.tickets.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class DashboardServiceImpl implements DashboardService {

    private final TicketRepository ticketRepository;
    private final CategoryRepository categoryRepository;

    @Override
    @Cacheable(value = "dashboard", key = "'dashboard:summary'")
    public DashboardSummaryResponse getSummary(UUID currentUserId) {
        return loadSummary();
    }

    private DashboardSummaryResponse loadSummary() {
        Instant now = Instant.now();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Instant startOfToday = today.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant endOfToday = today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        var tickets = ticketRepository.findAll();

        long openCount = 0;
        long inProgressCount = 0;
        long overdueCount = 0;
        long resolvedTodayCount = 0;
        Map<String, Long> byPriority = new HashMap<>();
        Map<String, Long> byCategory = new HashMap<>();

        for (Ticket ticket : tickets) {
            switch (ticket.getStatus()) {
                case OPEN -> openCount++;
                case IN_PROGRESS -> inProgressCount++;
                case RESOLVED -> {
                    if (ticket.getResolvedAt() != null &&
                        !ticket.getResolvedAt().isBefore(startOfToday) &&
                        ticket.getResolvedAt().isBefore(endOfToday)) {
                        resolvedTodayCount++;
                    }
                }
                default -> { /* CLOSED 不計入 */ }
            }

            if (ticket.getStatus() != TicketStatus.RESOLVED &&
                ticket.getStatus() != TicketStatus.CLOSED &&
                ticket.getSlaDeadline() != null &&
                ticket.getSlaDeadline().isBefore(now)) {
                overdueCount++;
            }

            String priorityName = ticket.getPriority().name();
            byPriority.merge(priorityName, 1L, Long::sum);

            String categoryName = categoryRepository.findById(ticket.getCategoryId())
                .map(c -> c.getName())
                .orElse("Unknown");
            byCategory.merge(categoryName, 1L, Long::sum);
        }

        return new DashboardSummaryResponse(
            openCount,
            inProgressCount,
            overdueCount,
            resolvedTodayCount,
            byPriority,
            byCategory
        );
    }
}
