package com.pk.support_ticket_api.categories.service;

import com.pk.support_ticket_api.categories.domain.Category;
import com.pk.support_ticket_api.categories.dto.CategoryResponse;
import com.pk.support_ticket_api.categories.dto.CategorySummaryResponse;
import com.pk.support_ticket_api.categories.dto.CreateCategoryRequest;
import com.pk.support_ticket_api.categories.dto.UpdateCategoryRequest;
import com.pk.support_ticket_api.categories.repository.CategoryRepository;
import com.pk.support_ticket_api.common.cache.CacheKeys;
import com.pk.support_ticket_api.common.cache.CacheService;
import com.pk.support_ticket_api.common.exception.BusinessRuleException;
import com.pk.support_ticket_api.common.exception.ConflictException;
import com.pk.support_ticket_api.common.exception.ResourceNotFoundException;
import com.pk.support_ticket_api.common.response.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private CacheService cacheService;

    @InjectMocks
    private CategoryServiceImpl categoryService;

    private UUID testId;
    private Category testCategory;

    @BeforeEach
    void setUp() {
        testId = UUID.randomUUID();
        testCategory = createCategory(testId, "技術問題", "技術相關問題", true);
    }

    @Nested
    class CreateCategory {

        @Test
        void createCategory_Success() {
            CreateCategoryRequest request = new CreateCategoryRequest(
                    "新分類", "描述", 72, 48, 24, 4
            );
            when(categoryRepository.existsByName("新分類")).thenReturn(false);
            when(categoryRepository.save(any(Category.class))).thenAnswer(invocation -> {
                Category category = invocation.getArgument(0);
                ReflectionTestUtils.setField(category, "id", testId);
                return category;
            });

            CategoryResponse response = categoryService.createCategory(request);

            assertNotNull(response);
            assertEquals("新分類", response.name());
            verify(categoryRepository).existsByName("新分類");
            verify(categoryRepository).save(any(Category.class));
            verify(cacheService).evictByPattern(CacheKeys.ACTIVE_CATEGORIES_PATTERN);
        }

        @Test
        void createCategory_DuplicateName_ThrowsConflictException() {
            CreateCategoryRequest request = new CreateCategoryRequest(
                    "技術問題", "描述", 72, 48, 24, 4
            );
            when(categoryRepository.existsByName("技術問題")).thenReturn(true);

            ConflictException exception = assertThrows(ConflictException.class,
                    () -> categoryService.createCategory(request));

            assertTrue(exception.getMessage().contains("already exists"));
            verify(categoryRepository, never()).save(any());
            verify(cacheService, never()).evictByPattern(any());
        }
    }

    @Nested
    class UpdateCategory {

        @Test
        void updateCategory_Success() {
            UpdateCategoryRequest request = new UpdateCategoryRequest(
                    "更新分類", "更新描述", 96, 72, 48, 8
            );
            when(categoryRepository.findById(testId)).thenReturn(Optional.of(testCategory));
            when(categoryRepository.existsByNameAndIdNot("更新分類", testId)).thenReturn(false);
            when(categoryRepository.save(any(Category.class))).thenReturn(testCategory);

            CategoryResponse response = categoryService.updateCategory(testId, request);

            assertNotNull(response);
            assertEquals("更新分類", testCategory.getName());
            assertEquals("更新描述", testCategory.getDescription());
            verify(categoryRepository).save(testCategory);
            verify(cacheService).evictByPattern(CacheKeys.ACTIVE_CATEGORIES_PATTERN);
        }

        @Test
        void updateCategory_UpdateNameOnly() {
            UpdateCategoryRequest request = new UpdateCategoryRequest(
                    "新名稱", null, null, null, null, null
            );
            when(categoryRepository.findById(testId)).thenReturn(Optional.of(testCategory));
            when(categoryRepository.existsByNameAndIdNot("新名稱", testId)).thenReturn(false);
            when(categoryRepository.save(any(Category.class))).thenReturn(testCategory);

            CategoryResponse response = categoryService.updateCategory(testId, request);

            assertNotNull(response);
            assertEquals("新名稱", testCategory.getName());
            verify(categoryRepository, never()).existsByNameAndIdNot(eq("技術問題"), any());
            verify(cacheService).evictByPattern(CacheKeys.ACTIVE_CATEGORIES_PATTERN);
        }

        @Test
        void updateCategory_DuplicateName_ThrowsConflictException() {
            UpdateCategoryRequest request = new UpdateCategoryRequest(
                    "已存在", null, null, null, null, null
            );
            when(categoryRepository.findById(testId)).thenReturn(Optional.of(testCategory));
            when(categoryRepository.existsByNameAndIdNot("已存在", testId)).thenReturn(true);

            ConflictException exception = assertThrows(ConflictException.class,
                    () -> categoryService.updateCategory(testId, request));

            assertTrue(exception.getMessage().contains("already exists"));
            verify(categoryRepository, never()).save(any());
            verify(cacheService, never()).evictByPattern(any());
        }

        @Test
        void updateCategory_SameName_NoConflict() {
            UpdateCategoryRequest request = new UpdateCategoryRequest(
                    "技術問題", "新描述", null, null, null, null
            );
            when(categoryRepository.findById(testId)).thenReturn(Optional.of(testCategory));
            when(categoryRepository.existsByNameAndIdNot("技術問題", testId)).thenReturn(false);
            when(categoryRepository.save(any(Category.class))).thenReturn(testCategory);

            CategoryResponse response = categoryService.updateCategory(testId, request);

            assertNotNull(response);
            verify(categoryRepository).existsByNameAndIdNot("技術問題", testId);
            verify(categoryRepository).save(testCategory);
            verify(cacheService).evictByPattern(CacheKeys.ACTIVE_CATEGORIES_PATTERN);
        }
    }

    @Nested
    class DeactivateCategory {

        @Test
        void deactivateCategory_Success() {
            when(categoryRepository.findById(testId)).thenReturn(Optional.of(testCategory));
            when(categoryRepository.save(any(Category.class))).thenReturn(testCategory);

            CategoryResponse response = categoryService.deactivateCategory(testId);

            assertNotNull(response);
            assertFalse(testCategory.getActive());
            verify(categoryRepository).save(testCategory);
            verify(cacheService).evictByPattern(CacheKeys.ACTIVE_CATEGORIES_PATTERN);
        }

        @Test
        void deactivateCategory_AlreadyDeactivated_ThrowsException() {
            testCategory.setActive(false);
            when(categoryRepository.findById(testId)).thenReturn(Optional.of(testCategory));

            BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                    () -> categoryService.deactivateCategory(testId));

            assertTrue(exception.getMessage().contains("already deactivated"));
            verify(categoryRepository, never()).save(any());
            verify(cacheService, never()).evictByPattern(any());
        }

        @Test
        void deactivateCategory_NotFound_ThrowsException() {
            UUID nonExistentId = UUID.randomUUID();
            when(categoryRepository.findById(nonExistentId)).thenReturn(Optional.empty());

            ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
                    () -> categoryService.deactivateCategory(nonExistentId));

            assertTrue(exception.getMessage().contains("not found"));
        }
    }

    @Nested
    class ActivateCategory {

        @Test
        void activateCategory_Success() {
            testCategory.setActive(false);
            when(categoryRepository.findById(testId)).thenReturn(Optional.of(testCategory));
            when(categoryRepository.save(any(Category.class))).thenReturn(testCategory);

            CategoryResponse response = categoryService.activateCategory(testId);

            assertNotNull(response);
            assertTrue(testCategory.getActive());
            verify(categoryRepository).save(testCategory);
            verify(cacheService).evictByPattern(CacheKeys.ACTIVE_CATEGORIES_PATTERN);
        }

        @Test
        void activateCategory_AlreadyActive_ThrowsException() {
            when(categoryRepository.findById(testId)).thenReturn(Optional.of(testCategory));

            BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                    () -> categoryService.activateCategory(testId));

            assertTrue(exception.getMessage().contains("already active"));
            verify(categoryRepository, never()).save(any());
            verify(cacheService, never()).evictByPattern(any());
        }

        @Test
        void activateCategory_NotFound_ThrowsException() {
            UUID nonExistentId = UUID.randomUUID();
            when(categoryRepository.findById(nonExistentId)).thenReturn(Optional.empty());

            ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
                    () -> categoryService.activateCategory(nonExistentId));

            assertTrue(exception.getMessage().contains("not found"));
        }
    }

    @Nested
    class GetCategoryById {

        @Test
        void getCategoryById_Success() {
            when(categoryRepository.findById(testId)).thenReturn(Optional.of(testCategory));

            CategoryResponse response = categoryService.getCategoryById(testId);

            assertNotNull(response);
            assertEquals(testId.toString(), response.id());
            assertEquals("技術問題", response.name());
        }

        @Test
        void getCategoryById_NotFound_ThrowsException() {
            UUID nonExistentId = UUID.randomUUID();
            when(categoryRepository.findById(nonExistentId)).thenReturn(Optional.empty());

            ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
                    () -> categoryService.getCategoryById(nonExistentId));

            assertTrue(exception.getMessage().contains("not found"));
        }
    }

    @Nested
    class GetAllCategories {

        @Test
        void getAllCategories_WithFilters() {
            Pageable pageable = PageRequest.of(0, 20);
            Page<Category> page = new PageImpl<>(List.of(testCategory), pageable, 1);
            when(categoryRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            var response = categoryService.getAllCategories(true, "技術", pageable);

            assertNotNull(response);
            assertEquals(1, response.content().size());
            assertEquals(0, response.page());
            assertEquals(1, response.totalElements());
        }

        @Test
        void getAllCategories_NoFilters() {
            Pageable pageable = PageRequest.of(0, 20);
            Page<Category> page = new PageImpl<>(List.of(testCategory), pageable, 1);
            when(categoryRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            var response = categoryService.getAllCategories(null, null, pageable);

            assertNotNull(response);
            assertEquals(1, response.content().size());
        }
    }

    @Nested
    class GetActiveCategories {

        @Test
        void getActiveCategories_Success() {
            Pageable pageable = PageRequest.of(0, 20, Sort.by("createdAt").descending());
            Page<Category> page = new PageImpl<>(List.of(testCategory), pageable, 1);

            // Mock cacheService.get() to call the loader directly
            when(cacheService.get(
                    any(String.class),
                    any(ParameterizedTypeReference.class),
                    any(),
                    any()
            )).thenAnswer(invocation -> {
                java.util.function.Supplier<?> loader = invocation.getArgument(3);
                return loader.get();
            });
            when(categoryRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            PageResponse<CategorySummaryResponse> response = categoryService.getActiveCategories(pageable);

            assertNotNull(response);
            assertEquals(1, response.content().size());
            // Verify that cacheService.get was called with dynamic key (contains page info)
            verify(cacheService).get(
                    argThat(key -> key.startsWith("category:active:list:p0:s20:")),
                    any(ParameterizedTypeReference.class),
                    any(),
                    any()
            );
        }

        @Test
        void getActiveCategories_CacheHit() {
            Pageable pageable = PageRequest.of(0, 20, Sort.by("createdAt").descending());
            // PageResponse 沒有 first/last 建構子，改用直接創建 Mock 對象
            PageResponse<CategorySummaryResponse> cachedResponse = mock(PageResponse.class);
            when(cachedResponse.content()).thenReturn(List.of(CategorySummaryResponse.from(testCategory)));

            // Mock cacheService.get() to return cached value
            when(cacheService.get(
                    any(String.class),
                    any(ParameterizedTypeReference.class),
                    any(),
                    any()
            )).thenReturn(cachedResponse);

            PageResponse<CategorySummaryResponse> response = categoryService.getActiveCategories(pageable);

            assertNotNull(response);
            assertEquals(1, response.content().size());
            // Verify that cacheService was called with dynamic key (cache hit scenario)
            verify(cacheService).get(
                    argThat(key -> key.startsWith("category:active:list:p0:s20:")),
                    any(ParameterizedTypeReference.class),
                    any(),
                    any()
            );
            // Verify that repository was never called (cache hit)
            verify(categoryRepository, never()).findAll(any(Specification.class), any(Pageable.class));
        }
    }

    private Category createCategory(UUID id, String name, String description, boolean active) {
        Category category = new Category();
        ReflectionTestUtils.setField(category, "id", id);
        category.setName(name);
        category.setDescription(description);
        category.setSlaHoursLow(72);
        category.setSlaHoursMedium(48);
        category.setSlaHoursHigh(24);
        category.setSlaHoursUrgent(4);
        category.setActive(active);
        return category;
    }
}
