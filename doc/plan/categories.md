# Category 模組實作計劃書

## 1. 概述

### 1.1 實作目標

完成 Category 模組的完整實作，包含：
- Category CRUD 管理功能
- SLA 時數設定（內嵌於 Category）
- Admin API 與 Public API
- 與 Ticket 模組的 SLA 計算整合

### 1.2 實作順序

```
Phase 1 → Phase 2 → Phase 3 → Phase 4 → Phase 5
```

### 1.3 預估工時

| Phase | 工作內容 | 預估工時 |
|-------|----------|----------|
| Phase 1 | 資料庫變更 | 0.5 天 |
| Phase 2 | 基礎建設 | 1 天 |
| Phase 3 | Service 層 | 1 天 |
| Phase 4 | Admin API | 1 天 |
| Phase 5 | 測試 | 1 天 |
| **總計** | | **4.5 天** |

---

## 2. Phase 1：資料庫變更

### 2.1 工作項目

#### 2.1.1 修改 V1__create_initial_schema.sql

**檔案**: `src/main/resources/db/migration/V1__create_initial_schema.sql`

**變更內容**:
```sql
-- 將原本的 categories 表格定義替換為以下內容

CREATE TABLE categories (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(100) NOT NULL UNIQUE,
    description      TEXT,
    sla_hours_low    INTEGER      NOT NULL DEFAULT 72
                       CHECK (sla_hours_low BETWEEN 1 AND 720),
    sla_hours_medium INTEGER      NOT NULL DEFAULT 48
                       CHECK (sla_hours_medium BETWEEN 1 AND 720),
    sla_hours_high   INTEGER      NOT NULL DEFAULT 24
                       CHECK (sla_hours_high BETWEEN 1 AND 720),
    sla_hours_urgent INTEGER      NOT NULL DEFAULT 4
                       CHECK (sla_hours_urgent BETWEEN 1 AND 720),
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE categories IS '工單分類表，SLA 時數內嵌於各分類中';
COMMENT ON COLUMN categories.sla_hours_low IS 'Low Priority SLA 小時數（預設 72 小時 = 3 天）';
COMMENT ON COLUMN categories.sla_hours_medium IS 'Medium Priority SLA 小時數（預設 48 小時 = 2 天）';
COMMENT ON COLUMN categories.sla_hours_high IS 'High Priority SLA 小時數（預設 24 小時 = 1 天）';
COMMENT ON COLUMN categories.sla_hours_urgent IS 'Urgent Priority SLA 小時數（預設 4 小時）';
```

**注意**: 如需保留既有資料，需先建立修補 Migration；本專案為新專案可直接替換。

#### 2.1.2 新增 V3__seed_categories.sql

**檔案**: `src/main/resources/db/migration/V3__seed_categories.sql`

```sql
-- V3__seed_categories.sql
-- 預設分類資料，包含不同 SLA 設定

INSERT INTO categories (id, name, description, sla_hours_low, sla_hours_medium, sla_hours_high, sla_hours_urgent, is_active, created_at, updated_at)
VALUES
  ('a0000000-0000-0000-0000-000000000001', '技術問題', '軟硬體技術相關問題', 72, 48, 24, 4, TRUE, NOW(), NOW()),
  
  ('a0000000-0000-0000-0000-000000000002', '帳務相關', '帳單、付款、發票等問題', 96, 72, 48, 8, TRUE, NOW(), NOW()),
  
  ('a0000000-0000-0000-0000-000000000003', '產品諮詢', '產品功能、使用方式諮詢', 120, 72, 24, 4, TRUE, NOW(), NOW()),
  
  ('a0000000-0000-0000-0000-000000000004', '功能建議', '新功能或改進建議', 168, 120, 72, 24, TRUE, NOW(), NOW()),
  
  ('a0000000-0000-0000-0000-000000000005', '其他', '無法分類的問題', 72, 48, 24, 4, TRUE, NOW(), NOW());
```

### 2.2 驗收條件

- [ ] `V1__create_initial_schema.sql` 包含完整 categories 表格定義
- [ ] categories 表格有 4 個 SLA 小時數欄位
- [ ] 每個 SLA 欄位有 CHECK 約束（1-720）
- [ ] `V3__seed_categories.sql` 包含 5 筆預設資料
- [ ] 執行 `flyway:migrate` 成功

---

## 3. Phase 2：基礎建設

### 3.1 工作項目

#### 3.1.1 建立 Category Entity

**檔案**: `src/main/java/com/pk/support_ticket_api/categories/domain/Category.java`

```java
package com.pk.support_ticket_api.categories.domain;

import com.pk.support_ticket_api.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "categories")
public class Category extends BaseEntity {
    
    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;
    
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    
    @Column(name = "sla_hours_low", nullable = false)
    private Integer slaHoursLow = 72;
    
    @Column(name = "sla_hours_medium", nullable = false)
    private Integer slaHoursMedium = 48;
    
    @Column(name = "sla_hours_high", nullable = false)
    private Integer slaHoursHigh = 24;
    
    @Column(name = "sla_hours_urgent", nullable = false)
    private Integer slaHoursUrgent = 4;
    
    @Column(name = "is_active", nullable = false)
    private Boolean active = true;
}
```

#### 3.1.2 建立 CategoryRepository

**檔案**: `src/main/java/com/pk/support_ticket_api/categories/repository/CategoryRepository.java`

```java
package com.pk.support_ticket_api.categories.repository;

import com.pk.support_ticket_api.categories.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {
    
    boolean existsByName(String name);
    
    boolean existsByNameAndIdNot(String name, UUID id);
}
```

#### 3.1.3 建立 CategorySpecification

**檔案**: `src/main/java/com/pk/support_ticket_api/categories/domain/CategorySpecification.java`

```java
package com.pk.support_ticket_api.categories.domain;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public class CategorySpecification {
    
    private CategorySpecification() {}
    
    public static Specification<Category> withFilters(
            Boolean active,
            String keyword
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }
            
            if (keyword != null && !keyword.isBlank()) {
                String pattern = "%" + keyword.toLowerCase() + "%";
                predicates.add(cb.like(
                    cb.lower(root.get("name")), pattern));
            }
            
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
```

### 3.2 預計產出

```
src/main/java/com/pk/support_ticket_api/categories/
├── domain/
│   ├── Category.java
│   └── CategorySpecification.java
└── repository/
    └── CategoryRepository.java
```

### 3.3 驗收條件

- [ ] Category Entity 可正常編譯
- [ ] CategoryRepository 包含 `existsByName` 和 `existsByNameAndIdNot` 方法
- [ ] CategorySpecification 可正確組合查詢條件
- [ ] 執行 `./mvnw compile` 成功

---

## 4. Phase 3：Service 層

### 4.1 工作項目

#### 4.1.1 建立 CategoryService 介面

**檔案**: `src/main/java/com/pk/support_ticket_api/categories/service/CategoryService.java`

```java
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
```

#### 4.1.2 建立 CategoryServiceImpl

**檔案**: `src/main/java/com/pk/support_ticket_api/categories/service/CategoryServiceImpl.java`

```java
package com.pk.support_ticket_api.categories.service;

import com.pk.support_ticket_api.categories.domain.Category;
import com.pk.support_ticket_api.categories.domain.CategorySpecification;
import com.pk.support_ticket_api.categories.dto.CategoryResponse;
import com.pk.support_ticket_api.categories.dto.CategorySummaryResponse;
import com.pk.support_ticket_api.categories.dto.CreateCategoryRequest;
import com.pk.support_ticket_api.categories.dto.UpdateCategoryRequest;
import com.pk.support_ticket_api.categories.exception.CategoryException;
import com.pk.support_ticket_api.categories.repository.CategoryRepository;
import com.pk.support_ticket_api.common.exception.BusinessRuleException;
import com.pk.support_ticket_api.common.exception.ConflictException;
import com.pk.support_ticket_api.common.exception.ResourceNotFoundException;
import com.pk.support_ticket_api.common.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class CategoryServiceImpl implements CategoryService {
    
    private final CategoryRepository categoryRepository;
    
    @Override
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
    
    @Override
    @Transactional(readOnly = true)
    public PageResponse<CategorySummaryResponse> getActiveCategories(Pageable pageable) {
        Page<Category> page = categoryRepository.findAll(
            CategorySpecification.withFilters(true, null),
            pageable
        );
        
        return PageResponse.from(
            page,
            CategorySummaryResponse::from
        );
    }
    
    private Category findCategoryById(UUID id) {
        return categoryRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Category not found with id: " + id
            ));
    }
}
```

#### 4.1.3 建立 SlaCalculator

**檔案**: `src/main/java/com/pk/support_ticket_api/categories/service/SlaCalculator.java`

```java
package com.pk.support_ticket_api.categories.service;

import com.pk.support_ticket_api.categories.domain.Category;
import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * SLA 到期時間計算器
 * 根據 Category 定義的 SLA 時數與 Ticket Priority 計算到期時間
 */
@Component
public class SlaCalculator {
    
    /**
     * 計算 SLA 到期時間
     *
     * @param category  分類
     * @param priority  優先級
     * @param createdAt Ticket 建立時間
     * @return SLA 到期時間
     */
    public Instant calculateSlaDueAt(
            Category category,
            TicketPriority priority,
            Instant createdAt
    ) {
        int slaHours = getSlaHours(category, priority);
        return createdAt.plus(slaHours, ChronoUnit.HOURS);
    }
    
    /**
     * 根據 Priority 取得對應的 SLA 小時數
     */
    private int getSlaHours(Category category, TicketPriority priority) {
        return switch (priority) {
            case LOW -> category.getSlaHoursLow();
            case MEDIUM -> category.getSlaHoursMedium();
            case HIGH -> category.getSlaHoursHigh();
            case URGENT -> category.getSlaHoursUrgent();
        };
    }
}
```

#### 4.1.4 建立 DTOs

**檔案**: `src/main/java/com/pk/support_ticket_api/categories/dto/CreateCategoryRequest.java`

```java
package com.pk.support_ticket_api.categories.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(
    @NotBlank(message = "名稱為必填")
    @Size(min = 1, max = 100, message = "名稱長度需 1-100 字元")
    String name,
    
    @Size(max = 500, message = "描述長度最大 500 字元")
    String description,
    
    @NotNull(message = "SLA Low 小時數為必填")
    @Min(value = 1, message = "SLA 小時數最小為 1")
    @Max(value = 720, message = "SLA 小時數最大為 720（30天）")
    Integer slaHoursLow,
    
    @NotNull(message = "SLA Medium 小時數為必填")
    @Min(value = 1, message = "SLA 小時數最小為 1")
    @Max(value = 720, message = "SLA 小時數最大為 720（30天）")
    Integer slaHoursMedium,
    
    @NotNull(message = "SLA High 小時數為必填")
    @Min(value = 1, message = "SLA 小時數最小為 1")
    @Max(value = 720, message = "SLA 小時數最大為 720（30天）")
    Integer slaHoursHigh,
    
    @NotNull(message = "SLA Urgent 小時數為必填")
    @Min(value = 1, message = "SLA 小時數最小為 1")
    @Max(value = 720, message = "SLA 小時數最大為 720（30天）")
    Integer slaHoursUrgent
) {}
```

**檔案**: `src/main/java/com/pk/support_ticket_api/categories/dto/UpdateCategoryRequest.java`

```java
package com.pk.support_ticket_api.categories.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UpdateCategoryRequest(
    @Size(min = 1, max = 100, message = "名稱長度需 1-100 字元")
    String name,
    
    @Size(max = 500, message = "描述長度最大 500 字元")
    String description,
    
    @Min(value = 1, message = "SLA 小時數最小為 1")
    @Max(value = 720, message = "SLA 小時數最大為 720（30天）")
    Integer slaHoursLow,
    
    @Min(value = 1, message = "SLA 小時數最小為 1")
    @Max(value = 720, message = "SLA 小時數最大為 720（30天）")
    Integer slaHoursMedium,
    
    @Min(value = 1, message = "SLA 小時數最小為 1")
    @Max(value = 720, message = "SLA 小時數最大為 720（30天）")
    Integer slaHoursHigh,
    
    @Min(value = 1, message = "SLA 小時數最小為 1")
    @Max(value = 720, message = "SLA 小時數最大為 720（30天）")
    Integer slaHoursUrgent
) {}
```

**檔案**: `src/main/java/com/pk/support_ticket_api/categories/dto/CategoryResponse.java`

```java
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
```

**檔案**: `src/main/java/com/pk/support_ticket_api/categories/dto/CategorySummaryResponse.java`

```java
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
```

#### 4.1.5 建立 CategoryException

**檔案**: `src/main/java/com/pk/support_ticket_api/categories/exception/CategoryException.java`

```java
package com.pk.support_ticket_api.categories.exception;

public class CategoryException extends RuntimeException {
    
    public CategoryException(String message) {
        super(message);
    }
}
```

### 4.2 預計產出

```
src/main/java/com/pk/support_ticket_api/categories/
├── dto/
│   ├── CreateCategoryRequest.java
│   ├── UpdateCategoryRequest.java
│   ├── CategoryResponse.java
│   └── CategorySummaryResponse.java
├── exception/
│   └── CategoryException.java
└── service/
    ├── CategoryService.java
    ├── CategoryServiceImpl.java
    └── SlaCalculator.java
```

### 4.3 驗收條件

- [ ] CategoryService 包含所有 CRUD 方法
- [ ] SlaCalculator 正確計算不同 Priority 的到期時間
- [ ] 所有 DTO 有完整的 Bean Validation
- [ ] 執行 `./mvnw compile` 成功

---

## 5. Phase 4：Admin API

### 5.1 工作項目

#### 5.1.1 建立 CategoryAdminController

**檔案**: `src/main/java/com/pk/support_ticket_api/categories/web/CategoryAdminController.java`

```java
package com.pk.support_ticket_api.categories.web;

import com.pk.support_ticket_api.categories.dto.CategoryResponse;
import com.pk.support_ticket_api.categories.dto.CreateCategoryRequest;
import com.pk.support_ticket_api.categories.dto.UpdateCategoryRequest;
import com.pk.support_ticket_api.categories.service.CategoryService;
import com.pk.support_ticket_api.common.response.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/categories")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Categories", description = "分類管理 API（需 ADMIN 角色）")
public class CategoryAdminController {
    
    private final CategoryService categoryService;
    
    @GetMapping
    @Operation(summary = "分頁查詢分類列表")
    public ResponseEntity<PageResponse<CategoryResponse>> getAllCategories(
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String keyword,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable
    ) {
        PageResponse<CategoryResponse> response = 
            categoryService.getAllCategories(active, keyword, pageable);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/{id}")
    @Operation(summary = "取得單一分類")
    public ResponseEntity<CategoryResponse> getCategoryById(
            @PathVariable UUID id
    ) {
        CategoryResponse response = categoryService.getCategoryById(id);
        return ResponseEntity.ok(response);
    }
    
    @PostMapping
    @Operation(summary = "建立新分類")
    public ResponseEntity<CategoryResponse> createCategory(
            @Valid @RequestBody CreateCategoryRequest request
    ) {
        CategoryResponse response = categoryService.createCategory(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
    
    @PutMapping("/{id}")
    @Operation(summary = "更新分類")
    public ResponseEntity<CategoryResponse> updateCategory(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCategoryRequest request
    ) {
        CategoryResponse response = categoryService.updateCategory(id, request);
        return ResponseEntity.ok(response);
    }
    
    @PatchMapping("/{id}/deactivate")
    @Operation(summary = "停用分類")
    public ResponseEntity<CategoryResponse> deactivateCategory(
            @PathVariable UUID id
    ) {
        CategoryResponse response = categoryService.deactivateCategory(id);
        return ResponseEntity.ok(response);
    }
    
    @PatchMapping("/{id}/activate")
    @Operation(summary = "啟用分類")
    public ResponseEntity<CategoryResponse> activateCategory(
            @PathVariable UUID id
    ) {
        CategoryResponse response = categoryService.activateCategory(id);
        return ResponseEntity.ok(response);
    }
}
```

#### 5.1.2 建立 CategoryController（公開端點）

**檔案**: `src/main/java/com/pk/support_ticket_api/categories/web/CategoryController.java`

```java
package com.pk.support_ticket_api.categories.web;

import com.pk.support_ticket_api.categories.dto.CategorySummaryResponse;
import com.pk.support_ticket_api.categories.service.CategoryService;
import com.pk.support_ticket_api.common.response.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
@Tag(name = "Categories", description = "分類公開 API")
public class CategoryController {
    
    private final CategoryService categoryService;
    
    @GetMapping
    @Operation(summary = "取得啟用中的分類（公開端點）")
    public ResponseEntity<PageResponse<CategorySummaryResponse>> getActiveCategories(
            @PageableDefault(size = 20)
            Pageable pageable
    ) {
        PageResponse<CategorySummaryResponse> response = 
            categoryService.getActiveCategories(pageable);
        return ResponseEntity.ok(response);
    }
}
```

### 5.2 預計產出

```
src/main/java/com/pk/support_ticket_api/categories/
└── web/
    ├── CategoryAdminController.java
    └── CategoryController.java
```

### 5.3 驗收條件

- [ ] Admin API 所有端點可正常存取
- [ ] 非 ADMIN 角色存取 Admin API 回傳 403
- [ ] 公開端點不需驗證角色
- [ ] Swagger UI 可看到所有端點
- [ ] 執行 `./mvnw spring-boot:run` 成功啟動

---

## 6. Phase 5：測試

### 6.1 工作項目

#### 6.1.1 建立 CategoryServiceTest

**檔案**: `src/test/java/com/pk/support_ticket_api/categories/service/CategoryServiceTest.java`

```java
package com.pk.support_ticket_api.categories.service;

import com.pk.support_ticket_api.categories.domain.Category;
import com.pk.support_ticket_api.categories.dto.CategoryResponse;
import com.pk.support_ticket_api.categories.dto.CreateCategoryRequest;
import com.pk.support_ticket_api.categories.dto.UpdateCategoryRequest;
import com.pk.support_ticket_api.categories.repository.CategoryRepository;
import com.pk.support_ticket_api.categories.service.CategoryService;
import com.pk.support_ticket_api.categories.service.CategoryServiceImpl;
import com.pk.support_ticket_api.common.exception.BusinessRuleException;
import com.pk.support_ticket_api.common.exception.ConflictException;
import com.pk.support_ticket_api.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CategoryService 測試")
class CategoryServiceTest {
    
    @Mock
    private CategoryRepository categoryRepository;
    
    @InjectMocks
    private CategoryServiceImpl categoryService;
    
    private Category testCategory;
    private UUID testId;
    
    @BeforeEach
    void setUp() {
        testId = UUID.randomUUID();
        testCategory = new Category();
        testCategory.setId(testId);
        testCategory.setName("技術問題");
        testCategory.setDescription("技術相關問題");
        testCategory.setSlaHoursLow(72);
        testCategory.setSlaHoursMedium(48);
        testCategory.setSlaHoursHigh(24);
        testCategory.setSlaHoursUrgent(4);
        testCategory.setActive(true);
    }
    
    @Nested
    @DisplayName("建立分類")
    class CreateCategoryTests {
        
        @Test
        @DisplayName("成功建立分類")
        void createCategory_Success() {
            CreateCategoryRequest request = new CreateCategoryRequest(
                "新分類", "描述", 72, 48, 24, 4
            );
            
            when(categoryRepository.existsByName("新分類")).thenReturn(false);
            when(categoryRepository.save(any(Category.class))).thenReturn(testCategory);
            
            CategoryResponse response = categoryService.createCategory(request);
            
            assertThat(response).isNotNull();
            assertThat(response.name()).isEqualTo("技術問題");
            verify(categoryRepository).save(any(Category.class));
        }
        
        @Test
        @DisplayName("名稱重複時拋出 ConflictException")
        void createCategory_DuplicateName_ThrowsConflictException() {
            CreateCategoryRequest request = new CreateCategoryRequest(
                "技術問題", "描述", 72, 48, 24, 4
            );
            
            when(categoryRepository.existsByName("技術問題")).thenReturn(true);
            
            assertThatThrownBy(() -> categoryService.createCategory(request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");
            
            verify(categoryRepository, never()).save(any());
        }
    }
    
    @Nested
    @DisplayName("停用/啟用分類")
    class ActivateDeactivateTests {
        
        @Test
        @DisplayName("成功停用分類")
        void deactivateCategory_Success() {
            when(categoryRepository.findById(testId)).thenReturn(Optional.of(testCategory));
            when(categoryRepository.save(any(Category.class))).thenReturn(testCategory);
            
            CategoryResponse response = categoryService.deactivateCategory(testId);
            
            assertThat(testCategory.getActive()).isFalse();
            verify(categoryRepository).save(testCategory);
        }
        
        @Test
        @DisplayName("已是停用狀態拋出 BusinessRuleException")
        void deactivateCategory_AlreadyDeactivated_ThrowsException() {
            testCategory.setActive(false);
            when(categoryRepository.findById(testId)).thenReturn(Optional.of(testCategory));
            
            assertThatThrownBy(() -> categoryService.deactivateCategory(testId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already deactivated");
        }
        
        @Test
        @DisplayName("成功啟用分類")
        void activateCategory_Success() {
            testCategory.setActive(false);
            when(categoryRepository.findById(testId)).thenReturn(Optional.of(testCategory));
            when(categoryRepository.save(any(Category.class))).thenReturn(testCategory);
            
            CategoryResponse response = categoryService.activateCategory(testId);
            
            assertThat(testCategory.getActive()).isTrue();
            verify(categoryRepository).save(testCategory);
        }
    }
    
    @Nested
    @DisplayName("查詢分類")
    class GetCategoryTests {
        
        @Test
        @DisplayName("取得不存在的分類拋出 ResourceNotFoundException")
        void getCategoryById_NotFound_ThrowsException() {
            UUID nonExistentId = UUID.randomUUID();
            when(categoryRepository.findById(nonExistentId)).thenReturn(Optional.empty());
            
            assertThatThrownBy(() -> categoryService.getCategoryById(nonExistentId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("not found");
        }
    }
}
```

#### 6.1.2 建立 SlaCalculatorTest

**檔案**: `src/test/java/com/pk/support_ticket_api/categories/service/SlaCalculatorTest.java`

```java
package com.pk.support_ticket_api.categories.service;

import com.pk.support_ticket_api.categories.domain.Category;
import com.pk.support_ticket_api.categories.service.SlaCalculator;
import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SlaCalculator 測試")
class SlaCalculatorTest {
    
    private SlaCalculator slaCalculator;
    private Category testCategory;
    private Instant createdAt;
    
    @BeforeEach
    void setUp() {
        slaCalculator = new SlaCalculator();
        
        testCategory = new Category();
        testCategory.setId(UUID.randomUUID());
        testCategory.setName("技術問題");
        testCategory.setSlaHoursLow(72);
        testCategory.setSlaHoursMedium(48);
        testCategory.setSlaHoursHigh(24);
        testCategory.setSlaHoursUrgent(4);
        
        // 固定測試時間：2026-09-04 10:00:00 UTC
        createdAt = Instant.parse("2026-09-04T10:00:00Z");
    }
    
    @ParameterizedTest
    @EnumSource(TicketPriority.class)
    @DisplayName("根據 Priority 正確取得對應 SLA 小時數")
    void calculateSlaDueAt_CorrectHours(TicketPriority priority) {
        Instant expectedDueAt = switch (priority) {
            case LOW -> createdAt.plus(72, ChronoUnit.HOURS);
            case MEDIUM -> createdAt.plus(48, ChronoUnit.HOURS);
            case HIGH -> createdAt.plus(24, ChronoUnit.HOURS);
            case URGENT -> createdAt.plus(4, ChronoUnit.HOURS);
        };
        
        Instant result = slaCalculator.calculateSlaDueAt(testCategory, priority, createdAt);
        
        assertThat(result).isEqualTo(expectedDueAt);
    }
    
    @Test
    @DisplayName("LOW Priority 正確計算為 72 小時後")
    void calculateSlaDueAt_LowPriority_72Hours() {
        Instant result = slaCalculator.calculateSlaDueAt(
            testCategory, 
            TicketPriority.LOW, 
            createdAt
        );
        
        // 2026-09-04 10:00:00 + 72 小時 = 2026-09-07 10:00:00
        assertThat(result).isEqualTo(
            Instant.parse("2026-09-07T10:00:00Z")
        );
    }
    
    @Test
    @DisplayName("URGENT Priority 正確計算為 4 小時後")
    void calculateSlaDueAt_UrgentPriority_4Hours() {
        Instant result = slaCalculator.calculateSlaDueAt(
            testCategory, 
            TicketPriority.URGENT, 
            createdAt
        );
        
        // 2026-09-04 10:00:00 + 4 小時 = 2026-09-04 14:00:00
        assertThat(result).isEqualTo(
            Instant.parse("2026-09-04T14:00:00Z")
        );
    }
}
```

### 6.2 預計產出

```
src/test/java/com/pk/support_ticket_api/categories/
├── service/
│   ├── CategoryServiceTest.java
│   └── SlaCalculatorTest.java
```

### 6.3 驗收條件

- [ ] CategoryServiceTest 所有測試案例通過
- [ ] SlaCalculatorTest 所有測試案例通過
- [ ] 執行 `./mvnw test` 成功

---

## 7. 完整檔案清單

| Phase | 類型 | 檔案路徑 |
|-------|------|----------|
| 1 | Migration | `src/main/resources/db/migration/V1__create_initial_schema.sql`（修改） |
| 1 | Migration | `src/main/resources/db/migration/V3__seed_categories.sql`（新增） |
| 2 | Entity | `src/main/java/.../categories/domain/Category.java` |
| 2 | Specification | `src/main/java/.../categories/domain/CategorySpecification.java` |
| 2 | Repository | `src/main/java/.../categories/repository/CategoryRepository.java` |
| 3 | Service | `src/main/java/.../categories/service/CategoryService.java` |
| 3 | Service | `src/main/java/.../categories/service/CategoryServiceImpl.java` |
| 3 | Service | `src/main/java/.../categories/service/SlaCalculator.java` |
| 3 | DTO | `src/main/java/.../categories/dto/CreateCategoryRequest.java` |
| 3 | DTO | `src/main/java/.../categories/dto/UpdateCategoryRequest.java` |
| 3 | DTO | `src/main/java/.../categories/dto/CategoryResponse.java` |
| 3 | DTO | `src/main/java/.../categories/dto/CategorySummaryResponse.java` |
| 3 | Exception | `src/main/java/.../categories/exception/CategoryException.java` |
| 4 | Controller | `src/main/java/.../categories/web/CategoryAdminController.java` |
| 4 | Controller | `src/main/java/.../categories/web/CategoryController.java` |
| 5 | Test | `src/test/java/.../categories/service/CategoryServiceTest.java` |
| 5 | Test | `src/test/java/.../categories/service/SlaCalculatorTest.java` |

**總計**: 2 個修改檔案 + 15 個新增檔案

---

## 8. 實作檢查清單

### Phase 1：資料庫變更
- [ ] 修改 `V1__create_initial_schema.sql`
- [ ] 新增 `V3__seed_categories.sql`
- [ ] 執行 Flyway migrate

### Phase 2：基礎建設
- [ ] 建立 `Category.java`
- [ ] 建立 `CategorySpecification.java`
- [ ] 建立 `CategoryRepository.java`
- [ ] 編譯成功

### Phase 3：Service 層
- [ ] 建立 `CategoryService.java`
- [ ] 建立 `CategoryServiceImpl.java`
- [ ] 建立 `SlaCalculator.java`
- [ ] 建立所有 DTO
- [ ] 建立 `CategoryException.java`
- [ ] 編譯成功

### Phase 4：Admin API
- [ ] 建立 `CategoryAdminController.java`
- [ ] 建立 `CategoryController.java`
- [ ] 應用程式啟動成功
- [ ] Swagger UI 可存取

### Phase 5：測試
- [ ] 建立 `CategoryServiceTest.java`
- [ ] 建立 `SlaCalculatorTest.java`
- [ ] 所有測試通過

---

## 9. 與 Ticket 模組的整合時機

完成 Category 模組後，Ticket 模組實作時需：

1. **注入 SlaCalculator** 到 TicketService
2. **建立 Ticket 時呼叫** `slaCalculator.calculateSlaDueAt()`
3. **驗證 Category 啟用狀態** — 只允許 `active=true` 的 Category

### 整合範例（TicketService 建立時）

```java
@Autowired
private SlaCalculator slaCalculator;

@Autowired
private CategoryRepository categoryRepository;

public TicketResponse createTicket(CreateTicketRequest request, UUID customerId) {
    // 1. 驗證 Category 存在且啟用
    Category category = categoryRepository.findById(request.categoryId())
        .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
    
    if (!category.getActive()) {
        throw new BusinessRuleException("Cannot create ticket with inactive category");
    }
    
    // 2. 建立 Ticket Entity
    Ticket ticket = new Ticket();
    ticket.setTitle(request.title());
    ticket.setCategory(category);
    // ... 其他欄位
    
    // 3. 計算 SLA 到期時間
    Instant slaDueAt = slaCalculator.calculateSlaDueAt(
        category, 
        request.priority(),
        ticket.getCreatedAt()
    );
    ticket.setSlaDueAt(slaDueAt);
    
    // 4. 儲存
    return TicketResponse.from(ticketRepository.save(ticket));
}
```

---

## 10. 風險與注意事項

| 風險 | 說明 | 緩解措施 |
|------|------|----------|
| 既有資料遷移 | 修改 V1 會影響既有資料庫 | 本專案為新專案，無既有資料 |
| SLA 計算時區 | 不同時區可能導致計算錯誤 | 使用 UTC Instant 儲存 |
| Category 停用影響 | 停用後現有 Ticket 不受影響 | 由 TicketService 驗證新建 Ticket |

---

*文件版本: 1.0*
*建立日期: 2026-09-04*
*最後更新: 2026-09-04*
