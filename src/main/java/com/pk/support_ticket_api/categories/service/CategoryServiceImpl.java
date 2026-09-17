package com.pk.support_ticket_api.categories.service;

import com.pk.support_ticket_api.categories.domain.Category;
import com.pk.support_ticket_api.categories.domain.CategorySpecification;
import com.pk.support_ticket_api.categories.dto.CategoryResponse;
import com.pk.support_ticket_api.categories.dto.CategorySummaryResponse;
import com.pk.support_ticket_api.categories.dto.CreateCategoryRequest;
import com.pk.support_ticket_api.categories.dto.UpdateCategoryRequest;
import com.pk.support_ticket_api.categories.repository.CategoryRepository;
import com.pk.support_ticket_api.common.exception.BusinessRuleException;
import com.pk.support_ticket_api.common.exception.ConflictException;
import com.pk.support_ticket_api.common.exception.ResourceNotFoundException;
import com.pk.support_ticket_api.common.response.PageResponse;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepository;

    public CategoryServiceImpl(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "categories", key = "'category:active:list'")
    public PageResponse<CategorySummaryResponse> getActiveCategories(Pageable pageable) {
        Page<Category> page = categoryRepository.findAll(
            CategorySpecification.withFilters(true, null),
            pageable
        );
        return PageResponse.from(page, CategorySummaryResponse::from);
    }

    @Override
    @CacheEvict(value = "categories", allEntries = true)
    public CategoryResponse createCategory(CreateCategoryRequest request) {
        if (categoryRepository.existsByName(request.name())) {
            throw new ConflictException("Category name already exists: " + request.name());
        }

        Category category = new Category();
        category.setName(request.name());
        category.setDescription(request.description());
        category.setSlaHoursLow(request.slaHoursLow());
        category.setSlaHoursMedium(request.slaHoursMedium());
        category.setSlaHoursHigh(request.slaHoursHigh());
        category.setSlaHoursUrgent(request.slaHoursUrgent());

        Category saved = categoryRepository.save(category);
        return CategoryResponse.from(saved);
    }

    @Override
    @CacheEvict(value = "categories", allEntries = true)
    public CategoryResponse updateCategory(UUID id, UpdateCategoryRequest request) {
        Category category = findCategoryById(id);

        if (request.name() != null && !request.name().isBlank()) {
            if (categoryRepository.existsByNameAndIdNot(request.name(), id)) {
                throw new ConflictException("Category name already exists: " + request.name());
            }
            category.setName(request.name());
        }

        if (request.description() != null) {
            category.setDescription(request.description());
        }

        if (request.slaHoursLow() != null) {
            category.setSlaHoursLow(request.slaHoursLow());
        }
        if (request.slaHoursMedium() != null) {
            category.setSlaHoursMedium(request.slaHoursMedium());
        }
        if (request.slaHoursHigh() != null) {
            category.setSlaHoursHigh(request.slaHoursHigh());
        }
        if (request.slaHoursUrgent() != null) {
            category.setSlaHoursUrgent(request.slaHoursUrgent());
        }

        Category saved = categoryRepository.save(category);
        return CategoryResponse.from(saved);
    }

    @Override
    @CacheEvict(value = "categories", allEntries = true)
    public CategoryResponse deactivateCategory(UUID id) {
        Category category = findCategoryById(id);

        if (!category.getActive()) {
            throw new BusinessRuleException("Category is already deactivated");
        }

        category.setActive(false);
        Category saved = categoryRepository.save(category);
        return CategoryResponse.from(saved);
    }

    @Override
    @CacheEvict(value = "categories", allEntries = true)
    public CategoryResponse activateCategory(UUID id) {
        Category category = findCategoryById(id);

        if (category.getActive()) {
            throw new BusinessRuleException("Category is already active");
        }

        category.setActive(true);
        Category saved = categoryRepository.save(category);
        return CategoryResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public CategoryResponse getCategoryById(UUID id) {
        Category category = findCategoryById(id);
        return CategoryResponse.from(category);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CategoryResponse> getAllCategories(
            Boolean active,
            String keyword,
            Pageable pageable
    ) {
        Page<Category> page = categoryRepository.findAll(
            CategorySpecification.withFilters(active, keyword),
            pageable
        );

        return PageResponse.from(
            page,
            CategoryResponse::from
        );
    }

    private Category findCategoryById(UUID id) {
        return categoryRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Category not found with id: " + id
            ));
    }
}
