package com.pk.support_ticket_api.categories.service;

import com.pk.support_ticket_api.categories.dto.CategoryResponse;
import com.pk.support_ticket_api.categories.dto.CategorySummaryResponse;
import com.pk.support_ticket_api.categories.dto.CreateCategoryRequest;
import com.pk.support_ticket_api.categories.dto.UpdateCategoryRequest;
import com.pk.support_ticket_api.common.response.PageResponse;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface CategoryService {

    CategoryResponse createCategory(CreateCategoryRequest request);

    CategoryResponse updateCategory(UUID id, UpdateCategoryRequest request);

    CategoryResponse deactivateCategory(UUID id);

    CategoryResponse activateCategory(UUID id);

    CategoryResponse getCategoryById(UUID id);

    PageResponse<CategoryResponse> getAllCategories(
            Boolean active,
            String keyword,
            Pageable pageable
    );

    PageResponse<CategorySummaryResponse> getActiveCategories(Pageable pageable);
}
