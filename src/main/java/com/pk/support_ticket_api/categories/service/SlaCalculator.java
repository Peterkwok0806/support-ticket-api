package com.pk.support_ticket_api.categories.service;

import com.pk.support_ticket_api.categories.domain.Category;
import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class SlaCalculator {

    public Instant calculateSlaDueAt(
            Category category,
            TicketPriority priority,
            Instant createdAt
    ) {
        int slaHours = getSlaHours(category, priority);
        return createdAt.plus(slaHours, ChronoUnit.HOURS);
    }

    private int getSlaHours(Category category, TicketPriority priority) {
        return switch (priority) {
            case LOW -> category.getSlaHoursLow();
            case MEDIUM -> category.getSlaHoursMedium();
            case HIGH -> category.getSlaHoursHigh();
            case URGENT -> category.getSlaHoursUrgent();
        };
    }
}
