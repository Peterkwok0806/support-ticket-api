package com.pk.support_ticket_api.categories.dto;

import com.pk.support_ticket_api.categories.domain.Category;

public record CategorySummaryResponse(
    String id,
    String name
) {

    public static CategorySummaryResponse from(Category category) {
        return new CategorySummaryResponse(
            category.getId().toString(),
            category.getName()
        );
    }
}
