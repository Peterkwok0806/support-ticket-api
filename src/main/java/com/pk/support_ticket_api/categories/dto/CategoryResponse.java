package com.pk.support_ticket_api.categories.dto;

import com.pk.support_ticket_api.categories.domain.Category;

import java.time.Instant;

public record CategoryResponse(
    String id,
    String name,
    String description,
    Integer slaHoursLow,
    Integer slaHoursMedium,
    Integer slaHoursHigh,
    Integer slaHoursUrgent,
    Boolean active,
    Instant createdAt,
    Instant updatedAt
) {

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(
            category.getId().toString(),
            category.getName(),
            category.getDescription(),
            category.getSlaHoursLow(),
            category.getSlaHoursMedium(),
            category.getSlaHoursHigh(),
            category.getSlaHoursUrgent(),
            category.getActive(),
            category.getCreatedAt(),
            category.getUpdatedAt()
        );
    }
}
